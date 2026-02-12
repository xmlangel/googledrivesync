package uk.xmlangel.googledrivesync.sync

import android.content.Context
import android.os.FileObserver
import android.app.ActivityManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import uk.xmlangel.googledrivesync.data.drive.DriveServiceHelper
import uk.xmlangel.googledrivesync.data.local.*
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.data.model.SyncStatus
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manager for synchronization operations with bidirectional sync support
 */
class SyncManager internal constructor(
    private val context: Context,
    private val driveHelper: DriveServiceHelper = DriveServiceHelper(context),
    private val database: SyncDatabase = SyncDatabase.getInstance(context),
    private val syncFolderDao: SyncFolderDao = database.syncFolderDao(),
    private val syncItemDao: SyncItemDao = database.syncItemDao(),
    private val historyDao: SyncHistoryDao = database.syncHistoryDao(),
    private val dirtyLocalDao: DirtyLocalDao = database.dirtyLocalDao(),
    private val syncPreferences: SyncPreferences = SyncPreferences(context),
    private val logger: uk.xmlangel.googledrivesync.util.SyncLogger = uk.xmlangel.googledrivesync.util.SyncLogger(context)
) {
    enum class SyncOperationType {
        SYNC,
        FORCE_PULL
    }

    data class ChangesDebugStats(
        val processed: Int,
        val removed: Int,
        val folderMetadataUpdated: Int,
        val outOfScope: Int,
        val uploaded: Int,
        val downloaded: Int,
        val skipped: Int,
        val errors: Int,
        val conflicts: Int
    )

    data class SyncRunDiagnostics(
        val isInitialSync: Boolean,
        val dirtyLocalItems: Int,
        val trackedItems: Int,
        val changesProcessedSuccessfully: Boolean,
        val fullScanExecuted: Boolean,
        val fullScanReason: String,
        val folderSyncDirection: SyncDirection,
        val defaultSyncDirection: SyncDirection,
        val autoUploadEnabled: Boolean,
        val lastStartPageToken: String?,
        val changesDebugStats: ChangesDebugStats?,
        val extraNotes: List<String> = emptyList()
    )

    data class VerificationExecution(
        val result: String, // PASS | FAIL | SKIPPED
        val reportPath: String,
        val summary: String
    )
    data class ForcePullExecution(
        val downloadedFiles: Int,
        val failedFiles: Int,
        val ensuredFolders: Int,
        val removedLocalEntries: Int,
        val reportPath: String?,
        val summary: String
    )
    data class ForcePullPreview(
        val syncLocalRoot: String,
        val driveFolderId: String,
        val totalRemovalCandidates: Int,
        val sampleRemovalCandidates: List<String>,
        val hasMoreCandidates: Boolean,
        val summary: String
    )

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()
    
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    private val _currentOperation = MutableStateFlow<SyncOperationType?>(null)
    val currentOperation: StateFlow<SyncOperationType?> = _currentOperation.asStateFlow()
    private val _operationStartedAtMs = MutableStateFlow<Long?>(null)
    val operationStartedAtMs: StateFlow<Long?> = _operationStartedAtMs.asStateFlow()

    private val _lastSyncResult = MutableStateFlow<SyncResult?>(null)
    val lastSyncResult: StateFlow<SyncResult?> = _lastSyncResult.asStateFlow()
    
    private val _pendingConflicts = MutableStateFlow<List<SyncConflict>>(emptyList())
    val pendingConflicts: StateFlow<List<SyncConflict>> = _pendingConflicts.asStateFlow()

    private val _pendingUploads = MutableStateFlow<List<PendingUpload>>(emptyList())
    val pendingUploads: StateFlow<List<PendingUpload>> = _pendingUploads.asStateFlow()
    private val _skippedUploads = MutableStateFlow<List<PendingUpload>>(emptyList())
    val skippedUploads: StateFlow<List<PendingUpload>> = _skippedUploads.asStateFlow()

    private var currentFileIndex = AtomicInteger(0)
    private var totalSyncFiles = 0
    private var currentStatusMessage: String? = null
    private var lastChangesDebugStats: ChangesDebugStats? = null
    private var currentSyncDownloadFailedCount: Int = 0
    private var currentSyncDownloadSkippedNonDownloadableCount: Int = 0
    @Volatile private var currentSyncFolderId: String? = null
    @Volatile private var bootstrapDownloadOnlyFolderId: String? = null
    private val currentSyncDownloadFailureReasonCounts = linkedMapOf<String, Int>()
    private val currentSyncDownloadFailureSamples = mutableListOf<String>()
    
    private val folderObservers = mutableMapOf<String, RecursiveFileObserver>()
    private val debounceJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val networkCooldownUntilByFolder = mutableMapOf<String, Long>()
    private val pendingResyncFolderIds = linkedSetOf<String>()
    private val pendingUploadProcessingPaths = mutableSetOf<String>()
    private val cancelledFolderIds = mutableSetOf<String>()

    private fun beginOperation(type: SyncOperationType) {
        _currentOperation.value = type
        _operationStartedAtMs.value = System.currentTimeMillis()
    }

    private fun endOperation(type: SyncOperationType) {
        if (_currentOperation.value == type) {
            _currentOperation.value = null
            _operationStartedAtMs.value = null
        }
    }

    private fun getSyncDirection(folder: SyncFolderEntity): SyncDirection = folder.syncDirection
    private fun isUploadBlocked(folder: SyncFolderEntity): Boolean =
        getSyncDirection(folder) == SyncDirection.DOWNLOAD_ONLY || bootstrapDownloadOnlyFolderId == folder.id
    private fun isDownloadBlocked(folder: SyncFolderEntity): Boolean = getSyncDirection(folder) == SyncDirection.UPLOAD_ONLY
    private fun isDeleteLikeLocalEvent(eventType: Int): Boolean {
        return when (eventType and FileObserver.ALL_EVENTS) {
            FileObserver.DELETE,
            FileObserver.DELETE_SELF,
            FileObserver.MOVED_FROM -> true
            else -> false
        }
    }

    private suspend fun isDeletionStillPresent(path: String): Boolean {
        if (File(path).exists()) return false
        kotlinx.coroutines.delay(350)
        return !File(path).exists()
    }

    companion object {
        @Volatile
        private var INSTANCE: SyncManager? = null
        private const val BACKUP_DIR_NAME = "conflicts_backup"
        private const val DEFERRED_DELETE_DIR_NAME = "deferred_delete"
        private const val VERIFY_REPORT_FILE_NAME = "verify.md"
        private const val NETWORK_ERROR_COOLDOWN_MS = 3 * 60 * 1000L
        private const val DEFERRED_DELETE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val FOLDER_DELETED_STOP_MESSAGE = "동기화 중 폴더가 삭제되어 동기화를 중지했습니다."
        private const val LOCAL_FOLDER_MISSING_STOP_MESSAGE = "로컬 동기화 폴더가 삭제되어 동기화를 중지했습니다."
        private const val BOOTSTRAP_DOWNLOAD_ONLY_WARNING =
            "동기화 이력이 없어 이번 실행은 다운로드 전용으로 진행했습니다. 이 상태에서 양방향 동기화를 강행하면 중복 파일 업로드/삭제가 발생할 수 있습니다."

        fun getInstance(context: Context): SyncManager {
            return INSTANCE ?: synchronized(this) {
                val instance = SyncManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
    
    /**
     * Initialize Drive service and start folder monitoring
     */
    fun initialize(): Boolean {
        logger.log("SyncManager.initialize() 호출됨")
        val result = driveHelper.initializeDriveService()
        if (result) {
            startMonitoringFolders()
        }
        return result
    }
    
    /**
     * Start monitoring all enabled folders for local changes
     */
    fun startMonitoringFolders() {
        managerScope.launch {
            if (!syncPreferences.autoSyncEnabled || !syncPreferences.realtimeSyncEnabled) {
                stopMonitoringFolders()
                logger.log("자동/실시간 동기화 설정이 비활성화되어 감시를 시작하지 않습니다.")
                return@launch
            }
            val folders = syncFolderDao.getEnabledSyncFoldersOnce()
            folders.forEach { folder ->
                setupFolderObserver(folder)
            }
        }
    }

    private fun setupFolderObserver(folder: SyncFolderEntity) {
        if (!syncPreferences.autoSyncEnabled || !syncPreferences.realtimeSyncEnabled) return
        synchronized(folderObservers) {
            if (folderObservers.containsKey(folder.id)) {
                folderObservers[folder.id]?.stopWatching()
            }
            
            val observer = RecursiveFileObserver(folder.localPath) { event, path ->
                handleLocalChangeEvent(folder.id, event, path)
            }
            folderObservers[folder.id] = observer
            logger.log("로컬 실시간 감시 시작: ${folder.localPath}", folder.accountEmail)
        }
    }

    /**
     * Stop all folder observers
     */
    fun stopMonitoringFolders() {
        synchronized(folderObservers) {
            folderObservers.values.forEach { it.stopWatching() }
            folderObservers.clear()
        }
        synchronized(debounceJobs) {
            debounceJobs.values.forEach { it.cancel() }
            debounceJobs.clear()
        }
        logger.log("모든 로컬 실시간 감시 중단")
    }

    private class SyncStoppedException(message: String) : Exception(message)

    private fun markFolderCancelled(folderId: String) {
        synchronized(cancelledFolderIds) {
            cancelledFolderIds.add(folderId)
        }
    }

    private fun clearFolderCancelled(folderId: String) {
        synchronized(cancelledFolderIds) {
            cancelledFolderIds.remove(folderId)
        }
    }

    private fun isFolderCancelled(folderId: String): Boolean {
        return synchronized(cancelledFolderIds) {
            cancelledFolderIds.contains(folderId)
        }
    }

    private fun throwIfFolderCancelled(folderId: String, accountEmail: String?) {
        if (!isFolderCancelled(folderId)) return
        logger.log("[WARNING] $FOLDER_DELETED_STOP_MESSAGE (folderId=$folderId)", accountEmail)
        throw SyncStoppedException(FOLDER_DELETED_STOP_MESSAGE)
    }

    private suspend fun throwIfFolderDeletedInDb(folderId: String, accountEmail: String?) {
        if (syncFolderDao.getSyncFolderById(folderId) != null) return
        logger.log("[WARNING] $FOLDER_DELETED_STOP_MESSAGE (folderId=$folderId, db_deleted=true)", accountEmail)
        throw SyncStoppedException(FOLDER_DELETED_STOP_MESSAGE)
    }

    private fun throwIfLocalFolderMissing(folder: SyncFolderEntity) {
        val rootDir = File(folder.localPath)
        if (rootDir.exists() && rootDir.isDirectory) return
        logger.log("[WARNING] $LOCAL_FOLDER_MISSING_STOP_MESSAGE (path=${folder.localPath})", folder.accountEmail)
        throw SyncStoppedException(LOCAL_FOLDER_MISSING_STOP_MESSAGE)
    }

    suspend fun deleteSyncFolder(folder: SyncFolderEntity): Boolean {
        val wasSyncingTargetFolder = _isSyncing.value && currentSyncFolderId == folder.id
        markFolderCancelled(folder.id)
        synchronized(folderObservers) {
            folderObservers.remove(folder.id)?.stopWatching()
        }
        synchronized(debounceJobs) {
            debounceJobs.remove(folder.id)?.cancel()
        }
        synchronized(pendingResyncFolderIds) {
            pendingResyncFolderIds.remove(folder.id)
        }
        dirtyLocalDao.deleteDirtyItemsByFolder(folder.id)
        syncItemDao.deleteItemsByFolder(folder.id)
        syncFolderDao.deleteSyncFolder(folder)
        logger.log("동기화 폴더 삭제 처리 완료: folderId=${folder.id}, path=${folder.localPath}", folder.accountEmail)
        return wasSyncingTargetFolder
    }

    suspend fun clearLocalFolderContents(localPath: String): Boolean {
        return try {
            val root = File(localPath)
            if (!root.exists()) {
                root.mkdirs()
                return true
            }
            if (!root.isDirectory) {
                logger.log("[WARNING] 로컬 폴더 초기화 실패: 디렉터리가 아님 ($localPath)")
                return false
            }

            var allDeleted = true
            root.listFiles().orEmpty().forEach { child ->
                if (!child.deleteRecursively() && child.exists()) {
                    allDeleted = false
                }
            }
            if (!allDeleted) {
                logger.log("[WARNING] 로컬 폴더 초기화 중 일부 삭제 실패: $localPath")
            } else {
                logger.log("로컬 폴더 초기화 완료: $localPath")
            }
            allDeleted
        } catch (e: Exception) {
            logger.log("[ERROR] 로컬 폴더 초기화 예외: $localPath (${e.message ?: e.javaClass.simpleName})")
            false
        }
    }

    private fun handleLocalChangeEvent(folderId: String, event: Int, path: String?) {
        if (path == null) return
        if (!syncPreferences.autoSyncEnabled || !syncPreferences.realtimeSyncEnabled) return
        if (isBackupPath(path)) return
        if (isExcludedAbsolutePath(path)) return
        
        // Record dirty item in DB
        managerScope.launch {
            dirtyLocalDao.insertDirtyItem(
                DirtyLocalItemEntity(
                    localPath = path,
                    syncFolderId = folderId,
                    eventType = event
                )
            )
        }

        // Debounce sync trigger: Wait 5 seconds after the last local change before syncing
        synchronized(debounceJobs) {
            debounceJobs[folderId]?.cancel()
            debounceJobs[folderId] = managerScope.launch {
                kotlinx.coroutines.delay(5000)
                if (shouldDeferRealtimeSync()) {
                    logger.log(
                        "화면 꺼짐/백그라운드 상태로 실시간 즉시 동기화 보류 (dirty queue 유지, WorkManager에서 처리): $folderId"
                    )
                    return@launch
                }
                val remainingCooldownMs = getNetworkCooldownRemainingMs(folderId)
                if (remainingCooldownMs > 0) {
                    logger.log("네트워크 오류 쿨다운으로 자동 동기화 건너뜀: ${remainingCooldownMs / 1000}초 후 재시도 ($folderId)")
                    return@launch
                }
                logger.log("로컬 변경 감지됨 - 자동 동기화 트리거 ($folderId)")
                syncFolder(folderId)
            }
        }
    }

    private fun shouldDeferRealtimeSync(): Boolean {
        return !isDeviceInteractive() || !isAppInForeground()
    }

    private fun isDeviceInteractive(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    private fun isAppInForeground(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val packageName = context.packageName
        val processInfo = am.runningAppProcesses
            ?.firstOrNull { it.processName == packageName }
            ?: return true
        return processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }
    
    /**
     * Add a sync folder configuration
     */
    suspend fun addSyncFolder(
        accountId: String,
        accountEmail: String,
        localPath: String,
        driveFolderId: String,
        driveFolderName: String,
        direction: SyncDirection = SyncDirection.DOWNLOAD_ONLY
    ): SyncFolderEntity {
        val folder = SyncFolderEntity(
            id = UUID.randomUUID().toString(),
            accountId = accountId,
            accountEmail = accountEmail,
            localPath = localPath,
            driveFolderId = driveFolderId,
            driveFolderName = driveFolderName,
            syncDirection = direction
        )
        syncFolderDao.insertSyncFolder(folder)
        setupFolderObserver(folder)
        return folder
    }
    
    /**
     * Perform sync for a specific folder
     */
    fun syncAllFolders() {
        managerScope.launch {
            val folders = syncFolderDao.getEnabledSyncFoldersOnce()
            folders.forEach { folder ->
                syncFolder(folder.id, forceNetworkRetry = true)
            }
        }
    }

    fun dismissLastResult() {
        _lastSyncResult.value = null
    }

    fun dismissPendingUploads() {
        _pendingUploads.value = emptyList()
    }

    fun dismissSkippedUploads() {
        _skippedUploads.value = emptyList()
    }

    /**
     * Perform sync for a specific folder
     */
    suspend fun syncFolder(folderId: String, forceNetworkRetry: Boolean = false): SyncResult {
        if (_isSyncing.value) {
            synchronized(pendingResyncFolderIds) {
                pendingResyncFolderIds.add(folderId)
            }
            logger.log("[WARNING] 동기화 요청 큐잉됨: 이미 진행 중 (folderId=$folderId, 종료 후 재실행)")
            return SyncResult.Error("동기화가 이미 진행 중입니다. 종료 후 자동 재실행됩니다.")
        }

        if (!forceNetworkRetry) {
            val remainingCooldownMs = getNetworkCooldownRemainingMs(folderId)
            if (remainingCooldownMs > 0) {
                val msg = "네트워크 불안정으로 재시도 대기 중입니다 (${remainingCooldownMs / 1000}초 후)"
                logger.log("[WARNING] $msg")
                return SyncResult.Error(msg)
            }
        }
        
        _isSyncing.value = true
        beginOperation(SyncOperationType.SYNC)
        _lastSyncResult.value = null
        
        try {
            val folder = syncFolderDao.getSyncFolderById(folderId)
                ?: run {
                    val error = SyncResult.Error("동기화 폴더를 찾을 수 없습니다")
                    _lastSyncResult.value = error
                    logger.log("[ERROR] 동기화 실패: folderId=$folderId 찾을 수 없음")
                    return error
                }
            clearFolderCancelled(folderId)
            throwIfFolderDeletedInDb(folderId, folder.accountEmail)
            throwIfLocalFolderMissing(folder)
            currentSyncFolderId = folder.id
            
            // Initialize Drive service for this specific account
            if (!driveHelper.initializeDriveService(folder.accountEmail)) {
                val error = SyncResult.Error("계정 동기화 서비스를 초기화할 수 없습니다")
                _lastSyncResult.value = error
                logger.log("[ERROR] 동기화 실패: 계정 ${folder.accountEmail} 서비스를 초기화할 수 없습니다")
                return error
            }
            
            // Start history entry
            val historyId = historyDao.insertHistory(
                SyncHistoryEntity(
                    syncFolderId = folderId,
                    accountId = folder.accountId,
                    accountEmail = folder.accountEmail,
                    startedAt = System.currentTimeMillis()
                )
            )
            
            logger.log("동기화 시작: folderId=$folderId", folder.accountEmail)
            logger.log(
                "동기화 정책: folder.syncDirection=${folder.syncDirection}, prefs.defaultSyncDirection=${syncPreferences.defaultSyncDirection}, autoUploadEnabled=${syncPreferences.autoUploadEnabled}",
                folder.accountEmail
            )
            cleanupExpiredDeferredDeletes(folder)
            publishSyncProgress(
                currentFile = folder.driveFolderName,
                currentIndex = 0,
                totalFiles = 1,
                isUploading = false,
                statusMessage = "동기화 준비 중..."
            )
            
            var uploaded = 0
            var downloaded = 0
            var skipped = 0
            var errors = 0
            val syncStartedAt = System.currentTimeMillis()
            val conflicts = mutableListOf<SyncConflict>()
            var forceFullScanNextRun = false
            var runImmediateRecoveryFullScan = false
            lastChangesDebugStats = null
            resetCurrentSyncDownloadDiagnostics()
            
            // Phase 2: Drive Changes (Primary)
            var changesProcessedSuccessfully = false
            if (folder.lastStartPageToken != null) {
                throwIfFolderCancelled(folder.id, folder.accountEmail)
                throwIfFolderDeletedInDb(folder.id, folder.accountEmail)
                throwIfLocalFolderMissing(folder)
                try {
                    publishSyncProgress(
                        currentFile = folder.driveFolderName,
                        currentIndex = 0,
                        totalFiles = 1,
                        isUploading = false,
                        statusMessage = "차분 동기화(변경분 조회) 시작..."
                    )
                    val changesResult = syncChangesInternal(folder)
                    uploaded += changesResult.uploaded
                    downloaded += changesResult.downloaded
                    skipped += changesResult.skipped
                    errors += changesResult.errors
                    conflicts.addAll(changesResult.conflicts)
                    
                    logger.log("차분 동기화 완료 (업로드=${changesResult.uploaded}, 다운로드=${changesResult.downloaded}, 에러=${changesResult.errors})", folder.accountEmail)
                    changesProcessedSuccessfully = true
                } catch (e: Exception) {
                    if (isFatalNetworkError(e)) throw e
                    logger.log("차분 동기화 중 오류 발생 (전체 스캔으로 전환): ${e.message}", folder.accountEmail)
                    // If 410 Gone or other non-fatal error, we'll fall back to full scan
                    changesProcessedSuccessfully = false
                }
            }
            
            // Phase 3: Local Changes
            val dirtyLocalItems = dirtyLocalDao.getDirtyItemsByFolder(folderId)
            val isInitialSync = folder.lastSyncedAt == 0L
            val trackedItemCount = try {
                syncItemDao.getSyncItemsByFolder(folder.id).first().size
            } catch (_: Exception) {
                0
            }
            val isBootstrapDownloadOnly = trackedItemCount == 0
            if (isBootstrapDownloadOnly) {
                bootstrapDownloadOnlyFolderId = folder.id
                logger.log("[WARNING] $BOOTSTRAP_DOWNLOAD_ONLY_WARNING", folder.accountEmail)
            }
            val shouldForceFullScanForBaseline = trackedItemCount == 0
            val shouldRunFullScan = isInitialSync || !changesProcessedSuccessfully || shouldForceFullScanForBaseline
            val fullScanReason = when {
                isInitialSync -> "initial_sync"
                !changesProcessedSuccessfully -> "changes_api_fallback"
                shouldForceFullScanForBaseline -> "baseline_missing_tracked_items"
                else -> "not_required"
            }
            logger.log(
                "동기화 실행계획: initialSync=$isInitialSync, changesOk=$changesProcessedSuccessfully, dirty=${dirtyLocalItems.size}, trackedItems=$trackedItemCount, runFullScan=$shouldRunFullScan",
                folder.accountEmail
            )
            if (shouldForceFullScanForBaseline && folder.lastStartPageToken != null) {
                logger.log(
                    "기준 데이터가 없어 전체 스캔 강제 실행 (trackedItems=0, token=${folder.lastStartPageToken})",
                    folder.accountEmail
                )
            }
            
            if (dirtyLocalItems.isNotEmpty() && changesProcessedSuccessfully) {
                throwIfFolderCancelled(folder.id, folder.accountEmail)
                throwIfFolderDeletedInDb(folder.id, folder.accountEmail)
                throwIfLocalFolderMissing(folder)
                logger.log("부분 동기화 모드 (감지된 로컬 변경 건수: ${dirtyLocalItems.size})", folder.accountEmail)
                val targetedResult = syncDirtyItems(folder, dirtyLocalItems)
                uploaded += targetedResult.uploaded
                downloaded += targetedResult.downloaded
                skipped += targetedResult.skipped
                errors += targetedResult.errors
                conflicts.addAll(targetedResult.conflicts)
                
                // Only clear dirty rows that existed before this sync started.
                // New events that arrive during sync must remain queued for next run.
                logInFlightDirtyCount(folder, folderId, syncStartedAt, "partial_before_cleanup")
                dirtyLocalDao.deleteDirtyItemsByFolderBefore(folderId, syncStartedAt)
                logger.log("부분 동기화 완료: 업로드=$uploaded, 다운로드=$downloaded, 에러=$errors", folder.accountEmail)
            }
            
            // Phase 4: Full Scan (Fallback or Initial)
            // Only run full scan if:
            // 1. Initial sync
            // 2. Changes API failed/not available
            // 3. User explicitly requested it (TODO: add flag if needed)
            var fullScanExecuted = false
            if (shouldRunFullScan) {
                throwIfFolderCancelled(folder.id, folder.accountEmail)
                throwIfFolderDeletedInDb(folder.id, folder.accountEmail)
                throwIfLocalFolderMissing(folder)
                fullScanExecuted = true
                if (!isInitialSync) {
                    logger.log("데이터 정합성을 위해 전체 스캔을 실행합니다.", folder.accountEmail)
                }
                
                // Get local files
                val localItems = try {
                    scanLocalFolder(folder.localPath)
                } catch (e: Exception) {
                    logger.log("[ERROR] 로컬 폴더 스캔 실패: ${e.message}", folder.accountEmail)
                    emptyList()
                }
                
                // Get all drive files
                val driveItems = try {
                    driveHelper.listAllFiles(folder.driveFolderId) { count ->
                        publishSyncProgress(
                            currentFile = "Google Drive",
                            currentIndex = count.coerceAtLeast(0),
                            totalFiles = (count + 1).coerceAtLeast(1),
                            isUploading = false,
                            statusMessage = "드라이브 목록 조회 중 ($count)..."
                        )
                    }
                } catch (e: Exception) {
                    if (isFatalNetworkError(e)) throw e
                    if (isCooldownNetworkError(e)) {
                        markNetworkCooldown(folder.id, folder.accountEmail, e)
                    }
                    val exceptionMsg = e.message ?: e.javaClass.simpleName
                    val errorMsg = when (e) {
                        is java.net.UnknownHostException -> "네트워크 연결이 없거나 Google 서비스에 접속할 수 없습니다 (DNS 오류)"
                        is java.net.SocketTimeoutException -> "서버 응답 시간 초과"
                        else -> exceptionMsg
                    }
                    logger.log("[ERROR] 드라이브 목록 획득 실패: $errorMsg", folder.accountEmail)
                    errors++
                    emptyList()
                }
                
                // Calculate total files for progress tracking (Local only for efficiency)
                publishSyncProgress(
                    currentFile = File(folder.localPath).absolutePath.ifBlank { "로컬 폴더" },
                    currentIndex = 0,
                    totalFiles = 1,
                    isUploading = false,
                    statusMessage = "진행률 계산 중 (로컬 항목 수 집계)..."
                )
                totalSyncFiles = countLocalFilesRecursive(folder.localPath) { counted ->
                    publishSyncProgress(
                        currentFile = File(folder.localPath).absolutePath.ifBlank { "로컬 폴더" },
                        currentIndex = counted.coerceAtLeast(0),
                        totalFiles = (counted + 1).coerceAtLeast(1),
                        isUploading = false,
                        statusMessage = "진행률 계산 중 (로컬 항목 ${counted}개 집계)..."
                    )
                }
                currentFileIndex.set(0)
                
                logger.log("스캔 완료: 로컬=${localItems.size}개, 드라이브=${driveItems.size}개 (전체 예상 항목: $totalSyncFiles)", folder.accountEmail)
                
                // Use a unified recursive sync method
                if (localItems.isNotEmpty() || driveItems.isNotEmpty()) {
                    val result = syncDirectoryRecursive(
                        folder = folder,
                        localPath = folder.localPath,
                        driveFolderId = folder.driveFolderId,
                        localItems = localItems,
                        driveItems = driveItems
                    )
                    
                    uploaded += result.uploaded
                    downloaded += result.downloaded
                    skipped += result.skipped
                    errors += result.errors
                    conflicts.addAll(result.conflicts)
                    
                    // Add stashed uploads to pending list (deduplicated)
                    if (result.pendingUploads.isNotEmpty()) {
                        appendPendingUploadsDedup(result.pendingUploads)
                    }
                } // End if (localItems...)
                
                // After successful full scan, clear only pre-existing dirty rows.
                logInFlightDirtyCount(folder, folderId, syncStartedAt, "fullscan_before_cleanup")
                dirtyLocalDao.deleteDirtyItemsByFolderBefore(folderId, syncStartedAt)
            } else {
                logger.log(
                    "전체 스캔 생략: 변경분 처리 정상 + 기준데이터 존재 (trackedItems=$trackedItemCount)",
                    folder.accountEmail
                )
            }
            
            // Phase 5: Verification report (sync.py style)
            try {
                val verification = generateAndSaveVerificationReport(
                    folder = folder,
                    diagnostics = SyncRunDiagnostics(
                        isInitialSync = isInitialSync,
                        dirtyLocalItems = dirtyLocalItems.size,
                        trackedItems = trackedItemCount,
                        changesProcessedSuccessfully = changesProcessedSuccessfully,
                        fullScanExecuted = fullScanExecuted,
                        fullScanReason = fullScanReason,
                        folderSyncDirection = folder.syncDirection,
                        defaultSyncDirection = syncPreferences.defaultSyncDirection,
                        autoUploadEnabled = syncPreferences.autoUploadEnabled,
                        lastStartPageToken = folder.lastStartPageToken,
                        changesDebugStats = lastChangesDebugStats,
                        extraNotes = buildCurrentSyncDownloadDiagnosticsNotes()
                    )
                )
                val verifyMessage = "동기화 검증 결과: ${verification.result} (리포트: ${verification.reportPath})"
                if (verification.result == "PASS") {
                    logger.log(verifyMessage, folder.accountEmail)
                } else if (verification.result == "WARN") {
                    logger.log("[WARNING] $verifyMessage | ${verification.summary}", folder.accountEmail)
                } else {
                    logger.log("[ERROR] $verifyMessage | ${verification.summary}", folder.accountEmail)
                    if (verification.missingLocalFiles.isNotEmpty() || verification.missingLocalFolders.isNotEmpty()) {
                        forceFullScanNextRun = true
                        runImmediateRecoveryFullScan = true
                        logger.log(
                            "[WARNING] 검증에서 로컬 누락 항목이 발견되어 즉시 전체 스캔 재시도를 수행합니다.",
                            folder.accountEmail
                        )
                    }
                    if (verification.extraLocalFiles.isNotEmpty()) {
                        val queued = enqueueExtraLocalFilesAsPendingUpload(folder, verification.extraLocalFiles)
                        logger.log(
                            "검증 실패 보정: Extra Local Files를 업로드 대기 목록에 등록 ($queued/${verification.extraLocalFiles.size})",
                            folder.accountEmail
                        )
                        logPendingUploadQueueState(folder.accountEmail, "after_verify_repair")
                    }
                }
            } catch (e: Exception) {
                if (isVerificationSkippableNetworkError(e)) {
                    val skippedReport = saveSkippedVerificationReport(
                        folder = folder,
                        reason = e.message ?: e.javaClass.simpleName
                    )
                    logger.log(
                        "[WARNING] 동기화 검증을 건너뜀(네트워크): ${skippedReport.reportPath}",
                        folder.accountEmail
                    )
                } else {
                    logger.log("[ERROR] 동기화 검증 리포트 생성 실패: ${e.message ?: e.javaClass.simpleName}", folder.accountEmail)
                }
            }

            if (runImmediateRecoveryFullScan) {
                logger.log("검증 누락 보정: 동일 턴에서 전체 스캔 재실행", folder.accountEmail)

                val localItems = try {
                    scanLocalFolder(folder.localPath)
                } catch (e: Exception) {
                    logger.log("[ERROR] 보정 전체 스캔 로컬 폴더 스캔 실패: ${e.message}", folder.accountEmail)
                    emptyList()
                }

                val driveItems = try {
                    driveHelper.listAllFiles(folder.driveFolderId) { count ->
                        publishSyncProgress(
                            currentFile = "Google Drive",
                            currentIndex = count.coerceAtLeast(0),
                            totalFiles = (count + 1).coerceAtLeast(1),
                            isUploading = false,
                            statusMessage = "누락 보정 전체 스캔: 드라이브 목록 조회 중 ($count)..."
                        )
                    }
                } catch (e: Exception) {
                    if (isFatalNetworkError(e)) throw e
                    val exceptionMsg = e.message ?: e.javaClass.simpleName
                    logger.log("[ERROR] 보정 전체 스캔 드라이브 목록 획득 실패: $exceptionMsg", folder.accountEmail)
                    errors++
                    emptyList()
                }

                if (localItems.isNotEmpty() || driveItems.isNotEmpty()) {
                    val recovery = syncDirectoryRecursive(
                        folder = folder,
                        localPath = folder.localPath,
                        driveFolderId = folder.driveFolderId,
                        localItems = localItems,
                        driveItems = driveItems
                    )
                    uploaded += recovery.uploaded
                    downloaded += recovery.downloaded
                    skipped += recovery.skipped
                    errors += recovery.errors
                    conflicts.addAll(recovery.conflicts)
                    if (recovery.pendingUploads.isNotEmpty()) {
                        appendPendingUploadsDedup(recovery.pendingUploads)
                    }
                }

                logInFlightDirtyCount(folder, folderId, syncStartedAt, "recovery_fullscan_before_cleanup")
                dirtyLocalDao.deleteDirtyItemsByFolderBefore(folderId, syncStartedAt)

                try {
                    val postRecoveryVerification = generateAndSaveVerificationReport(
                        folder = folder,
                        diagnostics = SyncRunDiagnostics(
                            isInitialSync = false,
                            dirtyLocalItems = 0,
                            trackedItems = trackedItemCount,
                            changesProcessedSuccessfully = false,
                            fullScanExecuted = true,
                            fullScanReason = "verification_missing_retry",
                            folderSyncDirection = folder.syncDirection,
                            defaultSyncDirection = syncPreferences.defaultSyncDirection,
                            autoUploadEnabled = syncPreferences.autoUploadEnabled,
                            lastStartPageToken = folder.lastStartPageToken,
                            changesDebugStats = lastChangesDebugStats,
                            extraNotes = buildCurrentSyncDownloadDiagnosticsNotes()
                        )
                    )
                    logger.log(
                        "누락 보정 후 재검증: ${postRecoveryVerification.result} (${postRecoveryVerification.reportPath})",
                        folder.accountEmail
                    )
                    if (postRecoveryVerification.isPass ||
                        (postRecoveryVerification.missingLocalFiles.isEmpty() && postRecoveryVerification.missingLocalFolders.isEmpty())
                    ) {
                        forceFullScanNextRun = false
                    }
                } catch (e: Exception) {
                    logger.log("[ERROR] 누락 보정 재검증 실패: ${e.message ?: e.javaClass.simpleName}", folder.accountEmail)
                }
            }

            // Update history
            historyDao.completeHistory(
                id = historyId,
                completedAt = System.currentTimeMillis(),
                status = if (conflicts.isNotEmpty()) "CONFLICTS" else "SUCCESS",
                uploaded = uploaded,
                downloaded = downloaded,
                skipped = skipped,
                errors = errors
            )
            
            // Update folder last sync time
            syncFolderDao.updateLastSyncTime(folderId, System.currentTimeMillis())
            
            // Phase 2: Get and save the new start page token after sync.
            // If verification indicates missing local entries, clear token so next run performs full scan.
            try {
                if (forceFullScanNextRun) {
                    syncFolderDao.clearPageToken(folder.id)
                    logger.log("다음 동기화를 위해 Page Token 초기화됨(강제 전체 스캔)", folder.accountEmail)
                } else {
                    val newToken = driveHelper.getStartPageToken()
                    if (newToken != null) {
                        syncFolderDao.updatePageToken(folder.id, newToken)
                        logger.log("새 Page Token 저장됨: $newToken", folder.accountEmail)
                    }
                }
            } catch (e: Exception) {
                logger.log("Page Token 획득 실패: ${e.message}", folder.accountEmail)
            }
            
            logger.log("동기화 완료: 업로드=$uploaded, 다운로드=$downloaded, 스킵=$skipped, 에러=$errors, 충돌=${conflicts.size}", folder.accountEmail)
            logPendingUploadQueueState(folder.accountEmail, "sync_complete")
            
            val syncResult = if (conflicts.isNotEmpty() || _pendingUploads.value.isNotEmpty()) {
                if (conflicts.isNotEmpty()) _pendingConflicts.value = conflicts
                SyncResult.Conflict(conflicts) // We might need a new result type or just use Conflict if we want to show a dialog
            } else {
                SyncResult.Success(
                    uploaded = uploaded,
                    downloaded = downloaded,
                    skipped = skipped,
                    warningMessage = if (isBootstrapDownloadOnly) BOOTSTRAP_DOWNLOAD_ONLY_WARNING else null
                )
            }
            _lastSyncResult.value = syncResult
            return syncResult
            
        } catch (e: SyncStoppedException) {
            val errorResult = SyncResult.Error(e.message ?: FOLDER_DELETED_STOP_MESSAGE)
            _lastSyncResult.value = errorResult
            return errorResult
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCooldownNetworkError(e)) {
                val folderForCooldown = try { syncFolderDao.getSyncFolderById(folderId) } catch (_: Exception) { null }
                markNetworkCooldown(folderId, folderForCooldown?.accountEmail, e)
            }
            e.printStackTrace()
            val errorMessage = when (e) {
                is GoogleAuthIOException -> "Google 계정 인증 오류가 발생했습니다. 다시 로그인해 주세요."
                is java.net.UnknownHostException -> "네트워크 연결이 없거나 Google 서비스에 접속할 수 없습니다 (DNS 오류)"
                is java.net.SocketTimeoutException -> "네트워크 응답 시간이 초과되었습니다"
                is java.io.IOException -> "네트워크 또는 파일 I/O 오류: ${e.message ?: "상세 메시지 없음"}"
                else -> e.message ?: "알 수 없는 오류"
            }
            val errorResult = SyncResult.Error(errorMessage, e)
            _lastSyncResult.value = errorResult
            logger.log("[ERROR] 동기화 중 치명적 오류 발생: $errorMessage (${e.javaClass.simpleName})")
            return errorResult
        } finally {
            clearFolderCancelled(folderId)
            if (currentSyncFolderId == folderId) {
                currentSyncFolderId = null
            }
            if (bootstrapDownloadOnlyFolderId == folderId) {
                bootstrapDownloadOnlyFolderId = null
            }
            _isSyncing.value = false
            _syncProgress.value = null
            endOperation(SyncOperationType.SYNC)
            val nextFolderId = synchronized(pendingResyncFolderIds) {
                pendingResyncFolderIds.firstOrNull()?.also { pendingResyncFolderIds.remove(it) }
            }
            if (nextFolderId != null) {
                managerScope.launch {
                    kotlinx.coroutines.delay(500)
                    logger.log("큐잉된 동기화 재실행: folderId=$nextFolderId")
                    syncFolder(nextFolderId, forceNetworkRetry = true)
                }
            }
        }
    }
    
    /**
     * Resolve a pending upload with user's choice
     */
    suspend fun resolvePendingUpload(pendingUpload: PendingUpload, resolution: PendingUploadResolution): Boolean {
        return try {
            when (resolution) {
                PendingUploadResolution.UPLOAD -> {
                    val localPath = pendingUpload.localFile.absolutePath
                    val acquired = synchronized(pendingUploadProcessingPaths) {
                        if (pendingUploadProcessingPaths.contains(localPath)) {
                            false
                        } else {
                            pendingUploadProcessingPaths.add(localPath)
                            true
                        }
                    }
                    if (!acquired) {
                        logger.log(
                            "[WARNING] 업로드 대기 승인 중복 요청 무시: ${pendingUpload.localFile.absolutePath}",
                            pendingUpload.accountEmail
                        )
                        return false
                    }
                    try {
                    val folder = syncFolderDao.getSyncFolderById(pendingUpload.folderId) ?: return false
                    logger.log(
                        "업로드 대기 승인 처리 시작: ${pendingUpload.localFile.absolutePath} (isNew=${pendingUpload.isNewFile})",
                        pendingUpload.accountEmail
                    )
                    val existingByPath = syncItemDao.getSyncItemByLocalPath(pendingUpload.localFile.absolutePath)
                    val effectiveDriveFileId = existingByPath?.driveFileId ?: pendingUpload.driveFileId
                    // PendingUpload.isNewFile means user approved "create/upload as new item".
                    // Do not silently downgrade to update existing Drive ID.
                    val shouldCreate = pendingUpload.isNewFile

                    val result = if (shouldCreate) {
                        driveHelper.uploadFile(
                            pendingUpload.localFile.absolutePath,
                            pendingUpload.localFile.name,
                            pendingUpload.driveFolderId
                        )
                    } else {
                        val targetId = effectiveDriveFileId ?: return false
                        driveHelper.updateFile(
                            targetId,
                            pendingUpload.localFile.absolutePath
                        )
                    }

                    if (result != null) {
                        val alignedResult = alignDriveItemAfterPendingUpload(
                            pendingUpload = pendingUpload,
                            driveItem = result
                        )

                        if (shouldCreate) {
                            val expectedParentId = pendingUpload.driveFolderId
                            val currentParents = alignedResult.parentIds
                            if (!currentParents.contains(expectedParentId)) {
                                val removeParents = currentParents
                                    .filter { it != expectedParentId }
                                    .takeIf { it.isNotEmpty() }
                                    ?.joinToString(",")
                                val moved = driveHelper.updateMetadata(
                                    fileId = alignedResult.id,
                                    addParents = expectedParentId,
                                    removeParents = removeParents
                                )
                                logger.log(
                                    if (moved) {
                                        "[WARNING] 업로드 파일 부모 폴더 보정 완료: ${pendingUpload.localFile.absolutePath} (driveId=${alignedResult.id}, expectedParent=$expectedParentId)"
                                    } else {
                                        "[WARNING] 업로드 파일 부모 폴더 보정 실패: ${pendingUpload.localFile.absolutePath} (driveId=${alignedResult.id}, expectedParent=$expectedParentId)"
                                    },
                                    pendingUpload.accountEmail
                                )
                            }
                        }

                        if (existingByPath != null) {
                            updateSyncItem(
                                existingItem = existingByPath,
                                localFile = pendingUpload.localFile,
                                driveFileId = alignedResult.id,
                                driveModifiedTime = alignedResult.modifiedTime,
                                driveSize = alignedResult.size,
                                status = SyncStatus.SYNCED,
                                md5Checksum = alignedResult.md5Checksum
                            )
                        } else {
                            trackSyncItem(
                                folder,
                                pendingUpload.localFile,
                                alignedResult.id,
                                alignedResult.modifiedTime,
                                alignedResult.size,
                                SyncStatus.SYNCED,
                                alignedResult.md5Checksum
                            )
                        }
                        // Remove duplicate pending entries for this local path.
                        val path = pendingUpload.localFile.absolutePath
                        _pendingUploads.value = _pendingUploads.value.filter { it.localFile.absolutePath != path }
                        logger.log(
                            "업로드 대기 승인 처리 완료: ${pendingUpload.localFile.absolutePath} (driveId=${alignedResult.id}, parents=${alignedResult.parentIds.joinToString(",")})",
                            pendingUpload.accountEmail
                        )
                        true
                    } else {
                        logger.log(
                            "[WARNING] 업로드 대기 승인 처리 실패: ${pendingUpload.localFile.absolutePath} (결과가 null)",
                            pendingUpload.accountEmail
                        )
                        false
                    }
                    } finally {
                        synchronized(pendingUploadProcessingPaths) {
                            pendingUploadProcessingPaths.remove(localPath)
                        }
                    }
                }
                PendingUploadResolution.SKIP -> {
                    val path = pendingUpload.localFile.absolutePath
                    val skippedBatch = _pendingUploads.value.filter { it.localFile.absolutePath == path }
                    _pendingUploads.value = _pendingUploads.value.filter { it.localFile.absolutePath != path }
                    appendSkippedUploadsDedup(skippedBatch.ifEmpty { listOf(pendingUpload) })
                    logger.log("업로드 대기 항목 건너뜀: ${pendingUpload.localFile.absolutePath}", pendingUpload.accountEmail)
                    true
                }
            }
        } catch (e: Exception) {
            logger.log("[ERROR] 업로드 승인 처리 실패: ${pendingUpload.localFile.absolutePath}", pendingUpload.accountEmail)
            false
        }
    }

    private suspend fun alignDriveItemAfterPendingUpload(
        pendingUpload: PendingUpload,
        driveItem: uk.xmlangel.googledrivesync.data.drive.DriveItem
    ): uk.xmlangel.googledrivesync.data.drive.DriveItem {
        val expectedName = pendingUpload.localFile.name
        val expectedParentId = pendingUpload.driveFolderId
        val currentParents = driveItem.parentIds
        val needsNameFix = driveItem.name != expectedName
        val needsParentFix = !currentParents.contains(expectedParentId)
        if (!needsNameFix && !needsParentFix) return driveItem

        val removeParents = if (needsParentFix) {
            currentParents.filter { it != expectedParentId }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(",")
        } else {
            null
        }
        val fixed = driveHelper.updateMetadata(
            fileId = driveItem.id,
            newName = if (needsNameFix) expectedName else null,
            addParents = if (needsParentFix) expectedParentId else null,
            removeParents = removeParents
        )
        logger.log(
            if (fixed) {
                "[WARNING] 업로드 승인 후 메타데이터 정렬 완료: ${pendingUpload.localFile.absolutePath} (nameFix=$needsNameFix, parentFix=$needsParentFix)"
            } else {
                "[WARNING] 업로드 승인 후 메타데이터 정렬 실패: ${pendingUpload.localFile.absolutePath} (nameFix=$needsNameFix, parentFix=$needsParentFix)"
            },
            pendingUpload.accountEmail
        )
        return if (fixed) {
            driveHelper.getFile(driveItem.id) ?: driveItem
        } else {
            driveItem
        }
    }

    /**
     * Resolve a conflict with user's choice
     */
    suspend fun resolveConflict(conflict: SyncConflict, resolution: ConflictResolution): Boolean {
        return try {
            when (resolution) {
                ConflictResolution.USE_LOCAL -> {
                    // Upload local file to Drive
                    val updated = driveHelper.updateFile(
                        fileId = conflict.syncItem.driveFileId!!,
                        localPath = conflict.syncItem.localPath
                    )
                    if (updated == null) {
                        throw IllegalStateException("Drive update returned null")
                    }
                    syncItemDao.updateItemStatus(conflict.syncItem.id, SyncStatus.SYNCED)
                    true
                }
                
                ConflictResolution.USE_DRIVE -> {
                    // Download from Drive
                    val downloaded = driveHelper.downloadFile(
                        fileId = conflict.syncItem.driveFileId!!,
                        destinationPath = conflict.syncItem.localPath
                    )
                    if (!downloaded) {
                        throw IllegalStateException("Drive download failed")
                    }
                    syncItemDao.updateItemStatus(conflict.syncItem.id, SyncStatus.SYNCED)
                    true
                }
                
                ConflictResolution.KEEP_BOTH -> {
                    // Rename local file and download Drive version
                    val localFile = File(conflict.syncItem.localPath)
                    val newName = "${localFile.nameWithoutExtension}_local.${localFile.extension}"
                    val renamedPath = "${localFile.parent}/$newName"
                    val renamed = localFile.renameTo(File(renamedPath))
                    if (!renamed) {
                        throw IllegalStateException("Failed to rename local file for KEEP_BOTH")
                    }
                    
                    val downloaded = driveHelper.downloadFile(
                        fileId = conflict.syncItem.driveFileId!!,
                        destinationPath = conflict.syncItem.localPath
                    )
                    if (!downloaded) {
                        throw IllegalStateException("Drive download failed after KEEP_BOTH rename")
                    }
                    syncItemDao.updateItemStatus(conflict.syncItem.id, SyncStatus.SYNCED)
                    true
                }
                
                ConflictResolution.SKIP -> {
                    syncItemDao.updateItemStatus(conflict.syncItem.id, SyncStatus.SYNCED)
                    true
                }
            }
        } catch (e: Exception) {
            if (isFatalNetworkError(e)) throw e
            val exceptionMsg = e.message ?: e.javaClass.simpleName
            syncItemDao.updateItemError(
                conflict.syncItem.id,
                SyncStatus.ERROR,
                exceptionMsg
            )
            logger.log("[ERROR] 충돌 해결 실패: ${conflict.localFileName} ($exceptionMsg)", conflict.syncItem.accountEmail)
            false
        }
    }
    
    /**
     * Recursive method to sync a directory tree
     */
    private suspend fun syncDirectoryRecursive(
        folder: SyncFolderEntity,
        localPath: String,
        driveFolderId: String,
        localItems: List<File>,
        driveItems: List<uk.xmlangel.googledrivesync.data.drive.DriveItem>
    ): RecursiveSyncResult {
        throwIfFolderCancelled(folder.id, folder.accountEmail)
        throwIfLocalFolderMissing(folder)
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var errors = 0
        val conflicts = mutableListOf<SyncConflict>()
        val pendingUploads = mutableListOf<PendingUpload>()

        val localMap = localItems.associateBy { it.name }
        val driveIdToLocalItem = mutableMapOf<String, File>()
        val handledLocalPaths = mutableSetOf<String>()
        val handledDriveIds = mutableSetOf<String>()
        
        // Pre-build map of driveId -> localFile based on DB
        for (localFile in localItems) {
            val item = syncItemDao.getSyncItemByLocalPath(localFile.absolutePath)
            if (item?.driveFileId != null) {
                driveIdToLocalItem[item.driveFileId] = localFile
            }
        }

        // 1. Process all items from Drive (Source of truth for ID-based tracking)
        for (driveFile in driveItems) {
            throwIfFolderCancelled(folder.id, folder.accountEmail)
            throwIfLocalFolderMissing(folder)
            handledDriveIds.add(driveFile.id)
            
            val currentIndex = currentFileIndex.incrementAndGet()
            publishSyncProgress(
                currentFile = File(localPath, driveFile.name).absolutePath,
                currentIndex = currentIndex,
                totalFiles = totalSyncFiles,
                isUploading = false,
                statusMessage = currentStatusMessage ?: "동기화 처리 중..."
            )

            // Try to find the local counterpart
            var localFile = driveIdToLocalItem[driveFile.id]
            var existingItem = if (localFile != null) syncItemDao.getSyncItemByLocalPath(localFile.absolutePath) else syncItemDao.getSyncItemByDriveId(driveFile.id)

            // Detect Move/Rename (Same ID, different path/name)
            if (existingItem != null && existingItem.driveFileId == driveFile.id) {
                val currentLocalPath = File(localPath, driveFile.name).absolutePath
                if (existingItem.localPath != currentLocalPath) {
                    val oldLocalFile = File(existingItem.localPath)
                    if (oldLocalFile.exists()) {
                        val sanitizedNewName = uk.xmlangel.googledrivesync.util.FileUtils.sanitizeFileName(driveFile.name)
                        val newLocalFile = File(localPath, sanitizedNewName)
                        
                        val statusMsg = "이름/위치 변경 감지 (Drive): ${oldLocalFile.absolutePath} -> ${newLocalFile.absolutePath}"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                        
                        if (oldLocalFile.renameTo(newLocalFile)) {
                            handledLocalPaths.add(existingItem.localPath)
                            localFile = newLocalFile
                            existingItem = existingItem.copy(
                                syncFolderId = folder.id,
                                localPath = newLocalFile.absolutePath,
                                fileName = sanitizedNewName
                            )
                            syncItemDao.updateSyncItem(existingItem)
                        } else {
                            logger.log("[ERROR] 로컬 이동 실패: ${oldLocalFile.absolutePath}", folder.accountEmail)
                        }
                    } else if (localFile != null && localFile.name != driveFile.name) {
                        // Rename within the same folder (if oldPath doesn't exist but localFile does)
                        // This case is actually mostly covered by the above, but kept for robustness
                    }
                }
            }

            // If still no local file, try matching by name (for first-time sync or if DB was lost)
            if (localFile == null) {
                val potentialMatch = localMap[driveFile.name]
                if (potentialMatch != null && !handledLocalPaths.contains(potentialMatch.absolutePath)) {
                    val potentialItem = syncItemDao.getSyncItemByLocalPath(potentialMatch.absolutePath)
                    if (potentialItem == null || potentialItem.driveFileId == null) {
                        localFile = potentialMatch
                        val statusMsg = "기존 로컬 파일 연결: ${potentialMatch.absolutePath}"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                    }
                }
            }

            if (localFile != null && localFile.name.contains("_local")) {
                skipped++; continue
            }
            val candidateForExclude = localFile ?: File(localPath, driveFile.name)
            if (isExcludedPath(folder, candidateForExclude)) {
                skipped++; continue
            }
            
            if (localFile != null) {
                handledLocalPaths.add(localFile.absolutePath)
            }

            // Perform Sync
            if (driveFile.isFolder) {
                // Folder Sync
                val subLocalName = localFile?.name ?: uk.xmlangel.googledrivesync.util.FileUtils.sanitizeFileName(driveFile.name)
                val subLocalPath = if (localFile != null) localFile.absolutePath else File(localPath, subLocalName).absolutePath
                val dir = File(subLocalPath)
                
                // TYPE CHECK: If drive is folder but local is file, we cannot sync this branch
                if (dir.exists() && !dir.isDirectory) {
                    errors++
                    logger.log("[ERROR] 타입 불일치 (드라이브: 폴더, 로컬: 파일): ${dir.absolutePath}. 동기화를 건너뜁니다.", folder.accountEmail)
                    continue
                }

                if (!dir.exists()) {
                    if (!isDownloadBlocked(folder)) {
                        val statusMsg = "폴더 생성: ${dir.absolutePath}"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                        dir.mkdirs()
                    } else {
                        logger.log("정책으로 다운로드/폴더생성 건너뜀(UPLOAD_ONLY): ${dir.absolutePath}", folder.accountEmail)
                        skipped++; continue
                    }
                }

                val subLocalItems = scanLocalFolder(subLocalPath)
                val subDriveItems = driveHelper.listAllFiles(driveFile.id)
                
                val subResult = syncDirectoryRecursive(
                    folder = folder,
                    localPath = subLocalPath,
                    driveFolderId = driveFile.id,
                    localItems = subLocalItems,
                    driveItems = subDriveItems
                )
                
                // Track folder record to enable effective differential sync for subfolders
                if (existingItem == null) {
                    trackSyncItem(
                        folder, 
                        dir, 
                        driveFile.id, 
                        driveFile.modifiedTime, 
                        0L, 
                        SyncStatus.SYNCED, 
                        null
                    )
                }

                uploaded += subResult.uploaded
                downloaded += subResult.downloaded
                skipped += subResult.skipped
                errors += subResult.errors
                conflicts.addAll(subResult.conflicts)
                pendingUploads.addAll(subResult.pendingUploads)
                // Recursive items will be added in the final step of syncFolder
            } else {
                // File Sync
                if (localFile != null) {
                    // TYPE CHECK: If drive is file but local is directory, we cannot sync this item
                    if (localFile.isDirectory) {
                        errors++
                        logger.log("[ERROR] 타입 불일치 (드라이브: 파일, 로컬: 폴더): ${localFile.absolutePath}. 동기화를 건너뜁니다.", folder.accountEmail)
                        continue
                    }
                    val syncResult = processFilePair(folder, localFile, driveFile, existingItem)
                    uploaded += syncResult.uploaded
                    downloaded += syncResult.downloaded
                    skipped += syncResult.skipped
                    errors += syncResult.errors
                    conflicts.addAll(syncResult.conflicts)
                    pendingUploads.addAll(syncResult.pendingUploads)
                } else if (!isDownloadBlocked(folder)) {
                    val sanitizedName = uk.xmlangel.googledrivesync.util.FileUtils.sanitizeFileName(driveFile.name)
                    val statusMsg = "새 파일 다운로드: ${File(localPath, sanitizedName).absolutePath}"
                    logger.log(statusMsg, folder.accountEmail)
                    currentStatusMessage = statusMsg
                    val destPath = File(localPath, sanitizedName).absolutePath
                    val downloadResult = driveHelper.downloadFileDetailed(driveFile.id, destPath)
                    if (downloadResult.success) {
                        downloaded++
                        trackSyncItem(folder, File(destPath), driveFile.id, driveFile.modifiedTime, driveFile.size, SyncStatus.SYNCED, driveFile.md5Checksum)
                    } else if (downloadResult.skipped) {
                        skipped++
                        recordCurrentSyncDownloadFailure(
                            path = destPath,
                            reason = downloadResult.reason ?: "non_downloadable_google_type",
                            mimeType = downloadResult.mimeType,
                            isNonDownloadableSkip = true,
                            accountEmail = folder.accountEmail
                        )
                    } else {
                        errors++
                        recordCurrentSyncDownloadFailure(
                            path = destPath,
                            reason = downloadResult.reason ?: "download_failed",
                            mimeType = downloadResult.mimeType,
                            isNonDownloadableSkip = false,
                            accountEmail = folder.accountEmail
                        )
                        logger.log("[ERROR] 다운로드 실패: ${File(localPath, sanitizedName).absolutePath}", folder.accountEmail)
                    }
                } else {
                    logger.log("정책으로 다운로드 건너뜀(UPLOAD_ONLY): ${File(localPath, driveFile.name).absolutePath}", folder.accountEmail)
                    skipped++
                }
            }
        }

        // 2. Process remaining Local items (New Uploads or Deletions)
        for (localFile in localItems) {
            throwIfFolderCancelled(folder.id, folder.accountEmail)
            throwIfLocalFolderMissing(folder)
            if (handledLocalPaths.contains(localFile.absolutePath)) continue
            if (localFile.name.contains("_local")) {
                skipped++; continue
            }
            if (isExcludedPath(folder, localFile)) {
                skipped++; continue
            }

            val currentIndex = currentFileIndex.incrementAndGet()
            publishSyncProgress(
                currentFile = localFile.absolutePath,
                currentIndex = currentIndex,
                totalFiles = totalSyncFiles,
                isUploading = true,
                statusMessage = currentStatusMessage ?: "동기화 처리 중..."
            )

            val existingItem = syncItemDao.getSyncItemByLocalPath(localFile.absolutePath)
            
            if (existingItem?.driveFileId != null && !handledDriveIds.contains(existingItem.driveFileId)) {
                // Item exists in DB but not on Drive in THIS folder.
                // It might have been moved or deleted.
                try {
                    val driveFileMeta = driveHelper.getFile(existingItem.driveFileId!!)
                    if (driveFileMeta == null || driveFileMeta.parentIds.contains(driveFolderId)) {
                        // Gone from drive or still in this folder but trashed?
                        val reason = if (driveFileMeta == null) "서버에서 완전히 삭제됨" else "드라이브에서 삭제됨(휴지통)"
                        logger.log("$reason 감지: ${localFile.absolutePath}", folder.accountEmail)
                        
                        // Soft-delete to conflicts_backup/deferred_delete and keep for retention window.
                        val deleteResult = archiveForDeferredDeletion(folder, localFile, reason)
                        if (deleteResult) {
                            syncItemDao.deleteSyncItem(existingItem)
                        } else if (!localFile.exists()) {
                            // File already gone, just clean up DB
                            syncItemDao.deleteSyncItem(existingItem)
                        } else {
                            logger.log("[WARNING] 로컬 파일 삭제 실패: ${localFile.absolutePath}", folder.accountEmail)
                        }
                    } else {
                        // Moved to another folder!
                        val targetParentId = driveFileMeta.parentIds.firstOrNull() ?: ""
                        val targetFolder = syncFolderDao.getSyncFolderByDriveId(targetParentId)
                        
                        if (targetFolder != null) {
                            logger.log("이동 감지 (Drive): ${localFile.absolutePath} -> 타깃 폴더(${targetFolder.driveFolderName})로 이동됨. 삭제 보류", folder.accountEmail)
                            // Keep local file so it can be moved by target folder's sync instead of re-downloading
                            skipped++
                        } else {
                            logger.log("이동 감지 (Drive): ${localFile.absolutePath} -> 비동기화 폴더로 이동됨. 로컬 삭제", folder.accountEmail)
                            val deleteResult = archiveForDeferredDeletion(folder, localFile, "drive_moved_out_of_scope")
                            if (deleteResult || !localFile.exists()) {
                                syncItemDao.deleteSyncItem(existingItem)
                            } else {
                                logger.log("[WARNING] 로컬 파일 삭제 실패: ${localFile.absolutePath}", folder.accountEmail)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // In the interest of resilience, we log it and continue with next file if possible
                    errors++
                    val errorDetail = if (isFatalNetworkError(e)) "(네트워크 오류)" else ""
                    val exceptionMsg = e.message ?: e.javaClass.simpleName
                    logger.log("[ERROR] 드라이브 메타데이터 확인 실패 $errorDetail: ${localFile.absolutePath} ($exceptionMsg)", folder.accountEmail)
                    if (isFatalNetworkError(e)) throw e
                }
            } else if (localFile.isDirectory) {
                // New Local Folder
                if (isAlwaysDownloadPath(folder, localFile)) {
                    logger.log("정책(.obsidian): 로컬 신규 폴더 업로드 건너뜀(다운로드 우선): ${localFile.absolutePath}", folder.accountEmail)
                    skipped++
                    continue
                }
                if (!isUploadBlocked(folder)) {
                    val statusMsg = "새 폴더 업로드: ${localFile.absolutePath}"
                    logger.log(statusMsg, folder.accountEmail)
                    currentStatusMessage = statusMsg
                    try {
                        val created = driveHelper.createFolder(localFile.name, driveFolderId)
                        if (created != null) {
                            // Track folder record
                            trackSyncItem(folder, localFile, created.id, created.modifiedTime, 0L, SyncStatus.SYNCED, null)
                            
                            val subLocalItems = scanLocalFolder(localFile.absolutePath)
                            val subResult = syncDirectoryRecursive(folder, localFile.absolutePath, created.id, subLocalItems, emptyList())
                            uploaded += subResult.uploaded; downloaded += subResult.downloaded
                            skipped += subResult.skipped; errors += subResult.errors
                            conflicts.addAll(subResult.conflicts)
                            pendingUploads.addAll(subResult.pendingUploads)
                        } else {
                            errors++; logger.log("[ERROR] 폴더 업로드 실패: ${localFile.absolutePath}", folder.accountEmail)
                        }
                    } catch (e: Exception) {
                        if (isFatalNetworkError(e)) throw e
                        errors++; 
                        val exceptionMsg = e.message ?: e.javaClass.simpleName
                        logger.log("[ERROR] 폴더 업로드 실패: ${localFile.absolutePath} ($exceptionMsg)", folder.accountEmail)
                    }
                } else {
                    logger.log("정책으로 업로드 건너뜀(DOWNLOAD_ONLY): ${localFile.absolutePath}", folder.accountEmail)
                    skipped++
                }
            } else {
                // New Local File
                if (isAlwaysDownloadPath(folder, localFile)) {
                    logger.log("정책(.obsidian): 로컬 신규 파일 업로드 건너뜀(다운로드 우선): ${localFile.absolutePath}", folder.accountEmail)
                    skipped++
                    continue
                }
                if (!isUploadBlocked(folder)) {
                    if (syncPreferences.autoUploadEnabled) {
                        val statusMsg = "새 파일 업로드: ${localFile.absolutePath}"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                        try {
                            val created = driveHelper.uploadFile(localFile.absolutePath, localFile.name, driveFolderId)
                            if (created != null) {
                                trackSyncItem(folder, localFile, created.id, created.modifiedTime, created.size, SyncStatus.SYNCED, created.md5Checksum)
                                uploaded++
                            } else {
                                errors++; logger.log("[ERROR] 파일 업로드 실패: ${localFile.absolutePath} (결과가 null입니다)", folder.accountEmail)
                            }
                        } catch (e: Exception) {
                            if (isFatalNetworkError(e)) throw e
                            errors++; 
                            val exceptionMsg = e.message ?: e.javaClass.simpleName
                            logger.log("[ERROR] 파일 업로드 실패: ${localFile.absolutePath} ($exceptionMsg)", folder.accountEmail)
                        }
                    } else {
                        val statusMsg = "업로드 대기: ${localFile.absolutePath}"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                        
                        val pendingUpload = PendingUpload(
                            folderId = folder.id,
                            localFile = localFile,
                            driveFolderId = driveFolderId,
                            isNewFile = true,
                            accountEmail = folder.accountEmail
                        )
                        pendingUploads.add(pendingUpload)
                        skipped++
                    }
                } else {
                    logger.log("정책으로 업로드 건너뜀(DOWNLOAD_ONLY): ${localFile.absolutePath}", folder.accountEmail)
                    skipped++
                }
            }
        }
        return RecursiveSyncResult(uploaded, downloaded, skipped, errors, conflicts, pendingUploads)
    }

    private fun isFatalNetworkError(e: Exception): Boolean {
        return e is GoogleAuthIOException ||
               e is java.net.UnknownHostException || 
               e is java.net.SocketTimeoutException || 
               e is java.io.InterruptedIOException ||
               e is java.net.ConnectException ||
               e is java.net.SocketException ||
               e is java.net.NoRouteToHostException ||
               e is javax.net.ssl.SSLHandshakeException ||
               (e is java.io.IOException && e.message?.contains("Canceled") == true)
    }

    private fun isCooldownNetworkError(e: Throwable): Boolean {
        return e is java.net.UnknownHostException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException ||
            e is java.net.NoRouteToHostException ||
            (e.message?.contains("Unable to resolve host", ignoreCase = true) == true)
    }

    private fun getNetworkCooldownRemainingMs(folderId: String): Long {
        val now = System.currentTimeMillis()
        val until = synchronized(networkCooldownUntilByFolder) {
            networkCooldownUntilByFolder[folderId]
        } ?: return 0L
        return (until - now).coerceAtLeast(0L)
    }

    private fun markNetworkCooldown(folderId: String, accountEmail: String?, error: Throwable) {
        val until = System.currentTimeMillis() + NETWORK_ERROR_COOLDOWN_MS
        synchronized(networkCooldownUntilByFolder) {
            networkCooldownUntilByFolder[folderId] = until
        }
        val reason = error.message ?: error.javaClass.simpleName
        logger.log(
            "[WARNING] 네트워크 오류 쿨다운 시작(${NETWORK_ERROR_COOLDOWN_MS / 1000}초): folderId=$folderId, reason=$reason",
            accountEmail
        )
    }

    private suspend fun processFilePair(
        folder: SyncFolderEntity,
        localFile: File,
        driveFile: uk.xmlangel.googledrivesync.data.drive.DriveItem,
        existingItem: SyncItemEntity?
    ): RecursiveSyncResult {
        if (localFile.name.contains("_local")) {
            return RecursiveSyncResult(0, 0, 1, 0, emptyList())
        }
        
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var errors = 0
        val conflicts = mutableListOf<SyncConflict>()
        val alwaysDownload = isAlwaysDownloadPath(folder, localFile)

        val localModified = localFile.lastModified()
        val driveModified = driveFile.modifiedTime
        val localSize = localFile.length()
        val driveSize = driveFile.size
        
        // Use a 2-second threshold for modification time comparisons
        val localDrift = Math.abs(localModified - (existingItem?.localModifiedAt ?: 0L))
        val driveDrift = Math.abs(driveModified - (existingItem?.driveModifiedAt ?: 0L))
        
        val isLocalUpdated = (existingItem == null || (localDrift > 2000 && localModified > (existingItem.localModifiedAt)) || localSize != (existingItem.localSize))
        val isDriveUpdated = (existingItem == null || (driveDrift > 2000 && driveModified > (existingItem.driveModifiedAt)) || driveSize != (existingItem.driveSize))
        
        // Optimisation: If both sides match what we have in DB, skip everything including MD5
        if (existingItem != null && !isLocalUpdated && !isDriveUpdated) {
            return RecursiveSyncResult(0, 0, 1, 0, emptyList())
        }

        // v1.0.9: New Item Linking - If sizes match exactly, link without sync
        if (existingItem == null && localSize == driveSize) {
            val localMd5 = uk.xmlangel.googledrivesync.util.FileUtils.calculateMd5(localFile)
            if (localMd5 != null && localMd5 == driveFile.md5Checksum) {
                val statusMsg = "기존 파일 연결 (MD5 Match): ${localFile.absolutePath}"
                logger.log(statusMsg, folder.accountEmail)
                currentStatusMessage = statusMsg
                trackSyncItem(folder, localFile, driveFile.id, driveFile.modifiedTime, driveFile.size, SyncStatus.SYNCED, driveFile.md5Checksum)
                return RecursiveSyncResult(0, 0, 1, 0, emptyList())
            } else {
                logger.log("MD5 불일치 또는 확인 불가: ${localFile.absolutePath} (Local: $localMd5, Drive: ${driveFile.md5Checksum})", folder.accountEmail)
            }
        }

        // v1.0.9 + Phase 2: Content-based skipping (MD5 Match) for existing items
        // Only do this if at least one side is "updated" metadata-wise
        if (existingItem != null && localSize == driveSize) {
            val localMd5 = uk.xmlangel.googledrivesync.util.FileUtils.calculateMd5(localFile)
            if (localMd5 != null && localMd5 == driveFile.md5Checksum) {
                if (isLocalUpdated || isDriveUpdated) {
                    val statusMsg = "내용 일치 (MD5 Match): ${localFile.absolutePath}"
                    logger.log("$statusMsg - 메타데이터만 업데이트", folder.accountEmail)
                    currentStatusMessage = statusMsg
                    updateSyncItem(existingItem, localFile, driveFile.id, driveFile.modifiedTime, driveFile.size, SyncStatus.SYNCED, driveFile.md5Checksum)
                }
                return RecursiveSyncResult(0, 0, 1, 0, emptyList())
            }
        }
        
        if (isLocalUpdated || isDriveUpdated) {
            val dbLocalMod = existingItem?.localModifiedAt
            val dbDriveMod = existingItem?.driveModifiedAt
            val dbLocalSize = existingItem?.localSize
            val dbDriveSize = existingItem?.driveSize
            
            logger.log("변경 감지: ${localFile.absolutePath}\n" +
                "  Status: ${if (existingItem == null) "New Item (Not in DB)" else "Existing Item"}\n" +
                "  Local: $localModified (Size: $localSize) vs DB: $dbLocalMod (Size: $dbLocalSize)\n" +
                "  Drive: $driveModified (Size: $driveSize) vs DB: $dbDriveMod (Size: $dbDriveSize)", 
                folder.accountEmail)
        }
        
        try {
            when {
                isLocalUpdated && isDriveUpdated -> {
                    if (localFile.isDirectory) {
                        // Directory "conflict" - just update metadata
                        val statusMsg = "폴더 메타데이터 업데이트 (교차 수정): ${localFile.absolutePath}"
                        logger.log(statusMsg, folder.accountEmail)
                        updateSyncItem(
                            existingItem = existingItem ?: createSyncItem(folder, localFile, driveFile.id),
                            localFile = localFile,
                            driveFileId = driveFile.id,
                            driveModifiedTime = driveFile.modifiedTime,
                            driveSize = 0L,
                            status = SyncStatus.SYNCED
                        )
                        return RecursiveSyncResult(0, 0, 1, 0, emptyList())
                    }
                    val conflict = SyncConflict(
                        existingItem ?: createSyncItem(folder, localFile, driveFile.id),
                        localFile.name, localModified, localFile.length(),
                        driveFile.name, driveModified, driveFile.size
                    )
                    if (alwaysDownload) {
                        if (downloadDriveVersion(folder, localFile, driveFile, existingItem)) {
                            downloaded++
                        } else {
                            errors++
                        }
                        return RecursiveSyncResult(uploaded, downloaded, skipped, errors, conflicts)
                    }
                    val defaultResolution = syncPreferences.defaultConflictResolution
                    if (defaultResolution != null) {
                        // Enforce sync direction policy for automatic conflict resolution.
                        val effectiveResolution = when (defaultResolution) {
                            ConflictResolution.USE_LOCAL -> {
                                if (isUploadBlocked(folder)) {
                                    if (!isDownloadBlocked(folder)) ConflictResolution.USE_DRIVE else ConflictResolution.SKIP
                                } else {
                                    ConflictResolution.USE_LOCAL
                                }
                            }
                            ConflictResolution.USE_DRIVE -> {
                                if (isDownloadBlocked(folder)) {
                                    if (!isUploadBlocked(folder)) ConflictResolution.USE_LOCAL else ConflictResolution.SKIP
                                } else {
                                    ConflictResolution.USE_DRIVE
                                }
                            }
                            else -> defaultResolution
                        }

                        if (effectiveResolution != defaultResolution) {
                            logger.log(
                                "정책으로 충돌 기본해결 방식 조정: $defaultResolution -> $effectiveResolution (${localFile.absolutePath})",
                                folder.accountEmail
                            )
                        }

                        if (resolveConflict(conflict, effectiveResolution)) {
                            if (effectiveResolution == ConflictResolution.USE_LOCAL) uploaded++ else if (effectiveResolution == ConflictResolution.USE_DRIVE) downloaded++
                        } else {
                            errors++
                            // Detailed error already logged in resolveConflict
                        }
                    } else conflicts.add(conflict)
                }
                isLocalUpdated -> {
                    if (localFile.isDirectory) {
                        // Local directory updated (likely timestamp) - just update metadata
                        val statusMsg = "폴더 메타데이터 업데이트 (로컬): ${localFile.absolutePath}"
                        logger.log(statusMsg, folder.accountEmail)
                        updateSyncItem(
                            existingItem = existingItem ?: createSyncItem(folder, localFile, driveFile.id),
                            localFile = localFile,
                            driveFileId = driveFile.id,
                            driveModifiedTime = driveFile.modifiedTime,
                            driveSize = 0L,
                            status = SyncStatus.SYNCED
                        )
                        return RecursiveSyncResult(0, 0, 1, 0, emptyList())
                    }
                    if (alwaysDownload) {
                        if (downloadDriveVersion(folder, localFile, driveFile, existingItem)) {
                            downloaded++
                        } else {
                            errors++
                        }
                        return RecursiveSyncResult(uploaded, downloaded, skipped, errors, conflicts)
                    }
                    if (!isUploadBlocked(folder)) {
                        if (syncPreferences.autoUploadEnabled) {
                            val statusMsg = "파일 업데이트(업로드): ${localFile.absolutePath}"
                            logger.log(statusMsg, folder.accountEmail)
                            currentStatusMessage = statusMsg
                            try {
                                // Always use the resolved Drive target from this sync cycle.
                                // Existing DB mapping may be stale after remote moves/relinks.
                                val driveFileId = driveFile.id
                                val updated = driveHelper.updateFile(driveFileId, localFile.absolutePath, getMimeType(localFile.name))
                                if (updated != null) {
                                    updateSyncItem(
                                        existingItem = existingItem ?: createSyncItem(folder, localFile, driveFileId),
                                        localFile = localFile,
                                        driveFileId = updated.id,
                                        driveModifiedTime = updated.modifiedTime,
                                        driveSize = updated.size,
                                        status = SyncStatus.SYNCED,
                                        md5Checksum = updated.md5Checksum
                                    )
                                    uploaded++
                                } else {
                                    errors++; logger.log("[ERROR] 파일 업데이트 실패: ${localFile.absolutePath} (결과가 null입니다)", folder.accountEmail)
                                }
                            } catch (e: Exception) {
                                if (isFatalNetworkError(e)) {
                                    errors++
                                    val exceptionMsg = e.message ?: e.javaClass.simpleName
                                    logger.log("[ERROR] 파일 업데이트 치명적 네트워크 오류: ${localFile.absolutePath} ($exceptionMsg)", folder.accountEmail)
                                    throw e
                                } else {
                                    errors++
                                    val exceptionMsg = e.message ?: e.javaClass.simpleName
                                    logger.log("[ERROR] 파일 업데이트 예외 발생: ${localFile.absolutePath} ($exceptionMsg)", folder.accountEmail)
                                }
                            }
                        } else {
                            val statusMsg = "업로드 대기 (수정됨): ${localFile.absolutePath}"
                            logger.log(statusMsg, folder.accountEmail)
                            currentStatusMessage = statusMsg
                            
                            val pendingUpload = PendingUpload(
                                folderId = folder.id,
                                localFile = localFile,
                                driveFolderId = driveFile.id, 
                                isNewFile = false,
                                driveFileId = driveFile.id,
                                accountEmail = folder.accountEmail
                            )
                            return RecursiveSyncResult(0, 0, 1, 0, emptyList(), listOf(pendingUpload))
                        }
                    } else {
                        logger.log("정책으로 파일 업데이트 업로드 건너뜀(DOWNLOAD_ONLY): ${localFile.absolutePath}", folder.accountEmail)
                        skipped++
                    }
                }
                isDriveUpdated -> {
                    if (localFile.isDirectory) {
                        // Drive directory updated - just update metadata
                        val statusMsg = "폴더 메타데이터 업데이트 (Drive): ${localFile.absolutePath}"
                        logger.log(statusMsg, folder.accountEmail)
                        updateSyncItem(
                            existingItem = existingItem ?: createSyncItem(folder, localFile, driveFile.id),
                            localFile = localFile,
                            driveFileId = driveFile.id,
                            driveModifiedTime = driveFile.modifiedTime,
                            driveSize = 0L,
                            status = SyncStatus.SYNCED
                        )
                        return RecursiveSyncResult(0, 0, 1, 0, emptyList())
                    }
                    if (alwaysDownload || !isDownloadBlocked(folder)) {
                        val statusMsg = "파일 다운로드: ${localFile.absolutePath}"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                        try {
                            val downloadResult = driveHelper.downloadFileDetailed(driveFile.id, localFile.absolutePath)
                            if (downloadResult.success) {
                                downloaded++
                                updateSyncItem(
                                    existingItem = existingItem ?: createSyncItem(folder, localFile, driveFile.id, driveFile.md5Checksum),
                                    localFile = localFile,
                                    driveFileId = driveFile.id,
                                    driveModifiedTime = driveFile.modifiedTime,
                                    driveSize = driveFile.size,
                                    status = SyncStatus.SYNCED,
                                    md5Checksum = driveFile.md5Checksum
                                )
                            } else if (downloadResult.skipped) {
                                skipped++
                                recordCurrentSyncDownloadFailure(
                                    path = localFile.absolutePath,
                                    reason = downloadResult.reason ?: "non_downloadable_google_type",
                                    mimeType = downloadResult.mimeType,
                                    isNonDownloadableSkip = true,
                                    accountEmail = folder.accountEmail
                                )
                            } else {
                                errors++
                                recordCurrentSyncDownloadFailure(
                                    path = localFile.absolutePath,
                                    reason = downloadResult.reason ?: "download_failed",
                                    mimeType = downloadResult.mimeType,
                                    isNonDownloadableSkip = false,
                                    accountEmail = folder.accountEmail
                                )
                                logger.log("[ERROR] 파일 업데이트(다운로드) 실패: ${localFile.absolutePath}", folder.accountEmail)
                            }
                        } catch (e: Exception) {
                             if (isFatalNetworkError(e)) throw e
                             errors++
                             val exceptionMsg = e.message ?: e.javaClass.simpleName
                             logger.log("[ERROR] 파일 다운로드 예외 발생: ${localFile.absolutePath} ($exceptionMsg)", folder.accountEmail)
                        }
                    } else {
                        logger.log("정책으로 파일 다운로드 건너뜀(UPLOAD_ONLY): ${localFile.absolutePath}", folder.accountEmail)
                        skipped++
                    }
                }
                else -> skipped++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isFatalNetworkError(e)) throw e
            errors++
            val exceptionMsg = e.message ?: e.javaClass.simpleName
            logger.log("[ERROR] 파일 쌍 처리 중 치명적 오류: ${localFile.absolutePath} ($exceptionMsg)", folder.accountEmail)
        }
        return RecursiveSyncResult(uploaded, downloaded, skipped, errors, conflicts, emptyList())
    }

    internal data class RecursiveSyncResult(
        val uploaded: Int,
        val downloaded: Int,
        val skipped: Int,
        val errors: Int,
        val conflicts: List<SyncConflict>,
        val pendingUploads: List<PendingUpload> = emptyList()
    )

    /**
     * Count all files and folders recursively to get total sync count
     */
    /**
     * Count local files recursively using efficient walking for progress estimation
     */
    private fun countLocalFilesRecursive(path: String, onProgress: ((Int) -> Unit)? = null): Int {
        val root = File(path)
        if (!root.exists()) return 0
        
        var count = 0
        try {
            // Exclude conflicts_backup from progress estimation.
            root.walkTopDown()
                .onEnter { dir -> dir.name != BACKUP_DIR_NAME }
                .forEach { _ ->
                    count++
                    if (count % 200 == 0) {
                        onProgress?.invoke(count)
                    }
                }
        } catch (e: Exception) {
            // Fallback: if walk fails, count what we can
        }
        onProgress?.invoke(count)
        return count
    }

    /**
     * Scan local folder for files and directories
     */
    private fun scanLocalFolder(path: String): List<File> {
        val folder = File(path)
        if (!folder.exists() || !folder.isDirectory) {
            return emptyList()
        }
        return folder.listFiles()
            ?.filterNot { it.name == BACKUP_DIR_NAME }
            ?.toList()
            ?: emptyList()
    }
    
    /**
     * Update existing sync item in database
     */
    private suspend fun updateSyncItem(
        existingItem: SyncItemEntity,
        localFile: File,
        driveFileId: String?,
        driveModifiedTime: Long,
        driveSize: Long,
        status: SyncStatus,
        md5Checksum: String? = null
    ) {
        val updatedItem = existingItem.copy(
            driveFileId = driveFileId ?: existingItem.driveFileId,
            mimeType = getMimeType(localFile.name),
            localModifiedAt = localFile.lastModified(),
            driveModifiedAt = driveModifiedTime,
            localSize = localFile.length(),
            driveSize = driveSize,
            md5Checksum = md5Checksum ?: existingItem.md5Checksum,
            status = status,
            lastSyncedAt = System.currentTimeMillis()
        )
        syncItemDao.updateSyncItem(updatedItem)
    }
    
    /**
     * Track sync item in database
     */
    private suspend fun trackSyncItem(
        folder: SyncFolderEntity,
        localFile: File,
        driveFileId: String?,
        driveModifiedTime: Long,
        driveSize: Long,
        status: SyncStatus,
        md5Checksum: String? = null
    ) {
        val item = SyncItemEntity(
            id = UUID.randomUUID().toString(),
            syncFolderId = folder.id,
            accountId = folder.accountId,
            accountEmail = folder.accountEmail,
            localPath = localFile.absolutePath,
            driveFileId = driveFileId,
            fileName = localFile.name,
            mimeType = getMimeType(localFile.name),
            localModifiedAt = localFile.lastModified(),
            driveModifiedAt = driveModifiedTime,
            localSize = localFile.length(),
            driveSize = driveSize,
            md5Checksum = md5Checksum,
            status = status,
            lastSyncedAt = System.currentTimeMillis()
        )
        syncItemDao.insertSyncItem(item)
    }
    
    private fun createSyncItem(
        folder: SyncFolderEntity,
        localFile: File,
        driveFileId: String?,
        md5Checksum: String? = null
    ): SyncItemEntity {
        return SyncItemEntity(
            id = UUID.randomUUID().toString(),
            syncFolderId = folder.id,
            accountId = folder.accountId,
            accountEmail = folder.accountEmail,
            localPath = localFile.absolutePath,
            driveFileId = driveFileId,
            fileName = localFile.name,
            mimeType = getMimeType(localFile.name),
            localModifiedAt = localFile.lastModified(),
            driveModifiedAt = 0L,
            localSize = localFile.length(),
            driveSize = 0,
            md5Checksum = md5Checksum,
            status = SyncStatus.CONFLICT,
            lastSyncedAt = 0
        )
    }
    
    private fun getMimeType(fileName: String): String {
        return uk.xmlangel.googledrivesync.util.MimeTypeUtil.getMimeType(fileName)
    }

    /**
     * Phase 3: Targeted synchronization of specific dirty local paths
     */
    internal suspend fun syncDirtyItems(folder: SyncFolderEntity, dirtyItems: List<DirtyLocalItemEntity>): RecursiveSyncResult {
        throwIfFolderCancelled(folder.id, folder.accountEmail)
        throwIfLocalFolderMissing(folder)
        var uploaded = 0; var downloaded = 0; var skipped = 0; var errors = 0
        val conflicts = mutableListOf<SyncConflict>()
        val pendingUploads = mutableListOf<PendingUpload>()
        val skipReasonCounts = linkedMapOf<String, Int>()
        fun addSkipped(reason: String, count: Int = 1) {
            if (count <= 0) return
            skipped += count
            skipReasonCounts[reason] = (skipReasonCounts[reason] ?: 0) + count
        }
        
        // Use a set to avoid processing the same path multiple times
        val processedPaths = mutableSetOf<String>()
        
        // Optimization: Detect renames/moves by matching missing items with new items
        val missingItems = mutableListOf<SyncItemEntity>()
        val newPaths = mutableListOf<String>()
        
        for (dirtyItem in dirtyItems) {
            throwIfFolderCancelled(folder.id, folder.accountEmail)
            throwIfLocalFolderMissing(folder)
            val path = dirtyItem.localPath
            if (path.contains("_local")) continue
            if (isBackupPath(path)) continue
            if (isExcludedAbsolutePath(path)) continue
            val localFile = File(path)
            val existingItem = syncItemDao.getSyncItemByLocalPath(path)
            
            if (!localFile.exists() && existingItem != null) {
                missingItems.add(existingItem)
            } else if (localFile.exists() && existingItem == null) {
                newPaths.add(path)
            }
        }
        
        // Map missing items for rename/move matching
        val missingByDriveId = missingItems
            .mapNotNull { item -> item.driveFileId?.let { driveId -> driveId to item } }
            .toMap()
        val missingBySize = missingItems.groupBy { it.localSize }
        val handledNewPaths = mutableSetOf<String>()
        val handledMissingIds = mutableSetOf<String>()
        
        for (newPath in newPaths) {
            val localFile = File(newPath)
            if (isAlwaysDownloadPath(folder, localFile)) continue
            val size = localFile.length()
            val newParentDriveId = ensureDriveParentFolderId(folder, localFile) ?: folder.driveFolderId
            var localMd5: String? = null

            // Priority 1: match by Drive file ID (authoritative identity)
            val byIdDriveFile = driveHelper.findFile(localFile.name, newParentDriveId)
                ?.takeIf { !it.isFolder }
            var byIdMatch = byIdDriveFile
                ?.let { found -> missingByDriveId[found.id] }
                ?.takeIf { !handledMissingIds.contains(it.id) }

            // Safety: even for ID-based candidate, verify content when hashes are available.
            if (byIdMatch != null && localFile.isFile) {
                localMd5 = uk.xmlangel.googledrivesync.util.FileUtils.calculateMd5(localFile)
                val driveMd5FromIdMatch = byIdDriveFile?.md5Checksum ?: byIdMatch.md5Checksum
                if (localMd5 != null && driveMd5FromIdMatch != null && localMd5 != driveMd5FromIdMatch) {
                    logger.log(
                        "[WARNING] ID 매칭 후보 내용 불일치로 rename/move 처리 보류: ${localFile.absolutePath} (driveId=${byIdMatch.driveFileId})",
                        folder.accountEmail
                    )
                    byIdMatch = null
                }
            }

            // Fallback: content match (size + MD5) for local-only rename/move inference
            val candidates = missingBySize[size].orEmpty()
            val byMd5Match = if (byIdMatch == null && candidates.isNotEmpty()) {
                if (localMd5 == null) {
                    localMd5 = uk.xmlangel.googledrivesync.util.FileUtils.calculateMd5(localFile)
                }
                localMd5?.let { md5 ->
                    candidates.find { it.md5Checksum == md5 && !handledMissingIds.contains(it.id) }
                }
            } else {
                null
            }

            val match = byIdMatch ?: byMd5Match
            
            if (match != null) {
                // Potential rename/move detected!
                val driveFileId = match.driveFileId
                if (driveFileId != null) {
                    if (isUploadBlocked(folder)) {
                        logger.log(
                            "정책으로 로컬 이름변경/이동 서버 반영 건너뜀(DOWNLOAD_ONLY): ${match.localPath} -> ${localFile.absolutePath}",
                            folder.accountEmail
                        )
                        addSkipped("policy_blocked_upload")
                        continue
                    }
                    logger.log("로컬 이름변경/이동 감지: ${match.localPath} -> ${localFile.absolutePath}", folder.accountEmail)
                    
                    val driveItem = driveHelper.getFile(driveFileId)
                    val currentParents = driveItem?.parentIds ?: emptyList<String>()
                    val removeParents = currentParents
                        .filter { p -> p != newParentDriveId }
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(",")
                    
                    val success = driveHelper.updateMetadata(
                        fileId = driveFileId,
                        newName = if (match.fileName != localFile.name) localFile.name else null,
                        addParents = if (!currentParents.contains(newParentDriveId)) newParentDriveId else null,
                        removeParents = removeParents
                    )
                    
                    if (success) {
                        // Update track in DB
                        val md5ForTrack = localMd5
                            ?: uk.xmlangel.googledrivesync.util.FileUtils.calculateMd5(localFile)
                        syncItemDao.deleteSyncItem(match)
                        trackSyncItem(folder, localFile, driveFileId, System.currentTimeMillis(), size, SyncStatus.SYNCED, md5ForTrack)
                        handledNewPaths.add(newPath)
                        handledMissingIds.add(match.id)
                        uploaded++ // Treat as an "upload" or "update" for count
                        logger.log("로컬 이름변경/이동 서버 반영 완료: ${localFile.absolutePath}", folder.accountEmail)
                        continue
                    }
                }
            }
        }

        for (dirtyItem in dirtyItems) {
            val path = dirtyItem.localPath
            if (processedPaths.contains(path) || handledNewPaths.contains(path)) continue
            processedPaths.add(path)
            
            // Skip files containing "_local"
            if (path.contains("_local")) continue
            if (isBackupPath(path)) continue
            if (isExcludedAbsolutePath(path)) {
                addSkipped("excluded_path")
                continue
            }
            
            try {
                val localFile = File(path)
                val existingItem = syncItemDao.getSyncItemByLocalPath(path)
                
                if (localFile.exists()) {
                    val alwaysDownload = isAlwaysDownloadPath(folder, localFile)
                    // Item exists locally - might be new or updated
                    val parentFolderId = ensureDriveParentFolderId(folder, localFile) ?: folder.driveFolderId
                    val driveFile = if (existingItem?.driveFileId != null) {
                        val trackedFile = driveHelper.getFile(existingItem.driveFileId)
                        when {
                            trackedFile == null -> {
                                logger.log(
                                    "[WARNING] 추적 Drive ID 조회 실패 - 이름 재탐색 없이 처리: ${localFile.absolutePath} (trackedId=${existingItem.driveFileId})",
                                    folder.accountEmail
                                )
                                null
                            }
                            else -> {
                                if (!trackedFile.parentIds.contains(parentFolderId)) {
                                    logger.log(
                                        "[WARNING] 추적 Drive ID 부모 불일치 - ID 우선 유지: ${localFile.absolutePath} (trackedId=${existingItem.driveFileId})",
                                        folder.accountEmail
                                    )
                                }
                                trackedFile
                            }
                        }
                    } else {
                        // Not in DB, try to find on Drive by name
                        driveHelper.findFile(localFile.name, parentFolderId)
                    }
                    
                    if (driveFile != null) {
                        val isTypeMatched = (localFile.isDirectory && driveFile.isFolder) ||
                            (localFile.isFile && !driveFile.isFolder)
                        if (!isTypeMatched) {
                            val localType = if (localFile.isDirectory) "DIR" else "FILE"
                            val driveType = if (driveFile.isFolder) "DIR" else "FILE"
                            logger.log(
                                "[WARNING] 부분 동기화 타입 불일치로 건너뜀: ${localFile.absolutePath} (local=$localType, drive=$driveType, driveId=${driveFile.id})",
                                folder.accountEmail
                            )
                            addSkipped("type_mismatch")
                            continue
                        }
                        val result = processFilePair(folder, localFile, driveFile, existingItem)
                        uploaded += result.uploaded
                        downloaded += result.downloaded
                        addSkipped("no_effect_or_metadata_only", result.skipped)
                        errors += result.errors
                        conflicts.addAll(result.conflicts)
                        pendingUploads.addAll(result.pendingUploads)
                    } else if (existingItem != null && existingItem.driveFileId != null) {
                        if (alwaysDownload) {
                            logger.log(
                                "정책(.obsidian): 추적 항목 복구 업로드 건너뜀(다운로드 우선): ${localFile.absolutePath}",
                                folder.accountEmail
                            )
                            addSkipped("obsidian_always_download")
                            continue
                        }
                        // Tracked Drive ID is stale or inaccessible.
                        // For local-changed items, repair by uploading as a new file when policy allows.
                        if (isUploadBlocked(folder)) {
                            logger.log(
                                "[WARNING] 추적 Drive 항목 조회 실패 + 정책상 업로드 불가: ${localFile.absolutePath} (driveId=${existingItem.driveFileId})",
                                folder.accountEmail
                            )
                            addSkipped("tracked_drive_item_not_found_upload_blocked")
                            continue
                        }

                        if (syncPreferences.autoUploadEnabled) {
                            val parentFolderId = ensureDriveParentFolderId(folder, localFile) ?: folder.driveFolderId
                            logger.log(
                                "[WARNING] 추적 Drive 항목 조회 실패 - 로컬 파일 재업로드로 링크 복구: ${localFile.absolutePath}",
                                folder.accountEmail
                            )
                            val uploadedFile = driveHelper.uploadFile(
                                localPath = localFile.absolutePath,
                                fileName = localFile.name,
                                parentFolderId = parentFolderId,
                                mimeType = getMimeType(localFile.name)
                            )
                            if (uploadedFile != null) {
                                updateSyncItem(
                                    existingItem = existingItem,
                                    localFile = localFile,
                                    driveFileId = uploadedFile.id,
                                    driveModifiedTime = uploadedFile.modifiedTime,
                                    driveSize = uploadedFile.size,
                                    status = SyncStatus.SYNCED,
                                    md5Checksum = uploadedFile.md5Checksum
                                )
                                uploaded++
                            } else {
                                errors++
                                logger.log("[ERROR] 링크 복구 재업로드 실패: ${localFile.absolutePath}", folder.accountEmail)
                            }
                        } else {
                            logger.log(
                                "[WARNING] 추적 Drive 항목 조회 실패 - 업로드 대기 등록: ${localFile.absolutePath}",
                                folder.accountEmail
                            )
                            val pendingUpload = PendingUpload(
                                folderId = folder.id,
                                localFile = localFile,
                                driveFolderId = folder.driveFolderId,
                                isNewFile = true,
                                driveFileId = null,
                                accountEmail = folder.accountEmail
                            )
                            pendingUploads.add(pendingUpload)
                            addSkipped("tracked_drive_item_not_found_pending_upload")
                        }
                    } else {
                        // New local item - upload file or create folder
                        if (alwaysDownload) {
                            logger.log("정책(.obsidian): 로컬 신규 항목 업로드 건너뜀(다운로드 우선): ${localFile.absolutePath}", folder.accountEmail)
                            addSkipped("obsidian_always_download")
                            continue
                        }
                        if (isUploadBlocked(folder)) {
                            logger.log("정책으로 로컬 신규 항목 업로드 건너뜀(DOWNLOAD_ONLY): ${localFile.absolutePath}", folder.accountEmail)
                            addSkipped("policy_blocked_upload")
                            continue
                        }
                        val parentFolderId = ensureDriveParentFolderId(folder, localFile) ?: folder.driveFolderId
                        if (localFile.isDirectory) {
                            val createdFolder = driveHelper.createFolder(localFile.name, parentFolderId)
                            if (createdFolder != null) {
                                trackSyncItem(folder, localFile, createdFolder.id, createdFolder.modifiedTime, 0L, SyncStatus.SYNCED)
                                uploaded++
                                logger.log("새 폴더 생성 완료: ${localFile.absolutePath}", folder.accountEmail)
                            } else {
                                errors++; logger.log("[ERROR] 폴더 생성 실패: ${localFile.absolutePath}", folder.accountEmail)
                            }
                        } else {
                            val uploadedFile = driveHelper.uploadFile(localFile.absolutePath, localFile.name, parentFolderId, getMimeType(localFile.name))
                            if (uploadedFile != null) {
                                trackSyncItem(folder, localFile, uploadedFile.id, uploadedFile.modifiedTime, uploadedFile.size, SyncStatus.SYNCED, uploadedFile.md5Checksum)
                                uploaded++
                            } else {
                                errors++; logger.log("[ERROR] 파일 업로드 실패: ${localFile.absolutePath} (결과가 null입니다)", folder.accountEmail)
                            }
                        }
                    }
                } else if (existingItem != null && !handledMissingIds.contains(existingItem.id)) {
                    if (isAlwaysDownloadPath(folder, File(existingItem.localPath))) {
                        logger.log("정책(.obsidian): 로컬 삭제 서버 반영 건너뜀(다운로드 우선): ${existingItem.localPath}", folder.accountEmail)
                        addSkipped("obsidian_always_download")
                        continue
                    }
                    // File deleted locally - handle deletion
                    if (!isDeleteLikeLocalEvent(dirtyItem.eventType)) {
                        logger.log(
                            "로컬 파일 부재 감지(삭제 이벤트 아님) - 서버 삭제 보류: ${existingItem.localPath} (event=${dirtyItem.eventType})",
                            folder.accountEmail
                        )
                        addSkipped("missing_without_delete_event")
                        continue
                    }
                    if (!isDeletionStillPresent(path)) {
                        logger.log(
                            "로컬 파일 부재가 일시적이라 서버 삭제 보류: ${existingItem.localPath}",
                            folder.accountEmail
                        )
                        addSkipped("transient_missing_after_delete_event")
                        continue
                    }
                    if (existingItem.driveFileId != null) {
                        if (isUploadBlocked(folder)) {
                            logger.log("정책으로 로컬 삭제 서버 반영 건너뜀(DOWNLOAD_ONLY): ${existingItem.localPath}", folder.accountEmail)
                            addSkipped("policy_blocked_delete")
                            continue
                        }
                        logger.log("로컬 삭제 감지됨: ${existingItem.localPath}. 서버에 반영 중...", folder.accountEmail)
                        try {
                            if (driveHelper.delete(existingItem.driveFileId!!)) {
                                syncItemDao.deleteSyncItem(existingItem)
                                logger.log("로컬 삭제 서버 반영 완료: ${existingItem.localPath}", folder.accountEmail)
                            } else {
                                logger.log("[WARNING] 로컬 삭제 서버 반영 실패: ${existingItem.localPath}", folder.accountEmail)
                            }
                        } catch (e: Exception) {
                            if (isFatalNetworkError(e)) throw e
                            logger.log("[ERROR] 로컬 삭제 서버 반영 중 예외 발생: ${existingItem.localPath} (${e.message})", folder.accountEmail)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFatalNetworkError(e)) throw e
                val exceptionMsg = e.message ?: e.javaClass.simpleName
                logger.log("[ERROR] 타겟 항목 처리 예외 (${path}): $exceptionMsg", folder.accountEmail)
                errors++
            }
        }

        if (skipReasonCounts.isNotEmpty()) {
            val reasons = skipReasonCounts.entries.joinToString(", ") { "${it.key}=${it.value}" }
            logger.log("부분 동기화 스킵 사유: $reasons", folder.accountEmail)
        }
        
        return RecursiveSyncResult(uploaded, downloaded, skipped, errors, conflicts, pendingUploads)
    }

    private suspend fun ensureDriveParentFolderId(folder: SyncFolderEntity, file: File): String? {
        val parentFile = file.parentFile ?: return null
        val rootPath = File(folder.localPath).absolutePath
        val parentPath = parentFile.absolutePath

        if (parentPath == rootPath) return folder.driveFolderId
        if (!parentPath.startsWith("$rootPath${File.separator}")) return null

        val relativePath = parentPath.removePrefix("$rootPath${File.separator}")
        if (relativePath.isBlank()) return folder.driveFolderId

        var currentDriveFolderId = folder.driveFolderId
        var currentLocalPath = rootPath

        for (segment in relativePath.split(File.separatorChar).filter { it.isNotBlank() }) {
            currentLocalPath = File(currentLocalPath, segment).absolutePath
            val trackedFolder = syncItemDao.getSyncItemByLocalPath(currentLocalPath)

            if (trackedFolder?.driveFileId != null) {
                val trackedDriveItem = driveHelper.getFile(trackedFolder.driveFileId)
                if (trackedDriveItem?.isFolder == true) {
                    currentDriveFolderId = trackedDriveItem.id
                    continue
                }
            }

            val existingDriveFolder = driveHelper.findFolder(segment, currentDriveFolderId)
            val resolvedDriveFolder = existingDriveFolder ?: driveHelper.createFolder(segment, currentDriveFolderId)
            if (resolvedDriveFolder == null) {
                logger.log("[ERROR] 부모 폴더 보장 실패: $currentLocalPath", folder.accountEmail)
                return null
            }

            val localFolder = File(currentLocalPath)
            if (!localFolder.exists()) {
                localFolder.mkdirs()
            }

            val existingTracking = syncItemDao.getSyncItemByLocalPath(currentLocalPath)
            if (existingTracking == null) {
                trackSyncItem(
                    folder = folder,
                    localFile = localFolder,
                    driveFileId = resolvedDriveFolder.id,
                    driveModifiedTime = resolvedDriveFolder.modifiedTime,
                    driveSize = 0L,
                    status = SyncStatus.SYNCED,
                    md5Checksum = null
                )
            } else {
                updateSyncItem(
                    existingItem = existingTracking,
                    localFile = localFolder,
                    driveFileId = resolvedDriveFolder.id,
                    driveModifiedTime = resolvedDriveFolder.modifiedTime,
                    driveSize = 0L,
                    status = SyncStatus.SYNCED,
                    md5Checksum = existingTracking.md5Checksum
                )
            }

            currentDriveFolderId = resolvedDriveFolder.id
        }

        return currentDriveFolderId
    }

    private data class VerificationReportResult(
        val result: String,
        val isPass: Boolean,
        val reportPath: String,
        val summary: String,
        val missingLocalFiles: List<String> = emptyList(),
        val missingLocalFolders: List<String> = emptyList(),
        val extraLocalFiles: List<String> = emptyList()
    )

    private data class SizeMismatch(
        val path: String,
        val driveSize: Long,
        val localSize: Long
    )

    private data class PathPrefixMismatch(
        val prefix: String,
        val matchedCount: Int,
        val missingCount: Int
    )

    private data class RootCount(
        val root: String,
        val count: Int
    )

    private data class DriveFileNode(
        val relativePath: String,
        val item: uk.xmlangel.googledrivesync.data.drive.DriveItem
    )

    private data class DriveFolderNode(
        val relativePath: String,
        val item: uk.xmlangel.googledrivesync.data.drive.DriveItem
    )

    private suspend fun generateAndSaveVerificationReport(
        folder: SyncFolderEntity,
        diagnostics: SyncRunDiagnostics? = null
    ): VerificationReportResult {
        val rootDir = File(folder.localPath)
        val backupDir = File(rootDir, BACKUP_DIR_NAME).apply { mkdirs() }
        val reportFile = File(backupDir, VERIFY_REPORT_FILE_NAME)
        val verificationTotalStages = 4

        publishVerificationProgress(
            currentFile = "검증 리포트",
            currentStage = 1,
            totalStages = verificationTotalStages,
            statusMessage = "검증 스캔(Drive) 시작..."
        )
        val driveFiles = linkedMapOf<String, Long>()
        val driveFileIds = linkedMapOf<String, String>()
        val driveFolders = linkedSetOf<String>()
        collectDriveTree(
            driveFolderId = folder.driveFolderId,
            prefix = "",
            files = driveFiles,
            fileIds = driveFileIds,
            folders = driveFolders
        ) { scannedCount ->
            publishVerificationProgress(
                currentFile = "검증 리포트",
                currentStage = 1,
                totalStages = verificationTotalStages,
                statusMessage = "검증 스캔(Drive) 진행 중... 항목 ${scannedCount}개"
            )
        }

        publishVerificationProgress(
            currentFile = "검증 리포트",
            currentStage = 2,
            totalStages = verificationTotalStages,
            statusMessage = "검증 스캔(Local) 시작..."
        )
        val localFiles = linkedMapOf<String, Long>()
        val localFolders = linkedSetOf<String>()
        collectLocalTree(
            rootDir = rootDir,
            files = localFiles,
            folders = localFolders
        ) { scannedCount ->
            publishVerificationProgress(
                currentFile = "검증 리포트",
                currentStage = 2,
                totalStages = verificationTotalStages,
                statusMessage = "검증 스캔(Local) 진행 중... 항목 ${scannedCount}개"
            )
        }

        publishVerificationProgress(
            currentFile = "검증 리포트",
            currentStage = 3,
            totalStages = verificationTotalStages,
            statusMessage = "검증 비교/차이 분석 중..."
        )
        val driveFilePaths = driveFiles.keys
        val localFilePaths = localFiles.keys
        val driveFolderPaths = driveFolders
        val localFolderPaths = localFolders

        val missingLocalFiles = (driveFilePaths - localFilePaths).sorted()
        val extraLocalFiles = (localFilePaths - driveFilePaths).sorted()
        val missingLocalFolders = (driveFolderPaths - localFolderPaths).sorted()
        val extraLocalFolders = (localFolderPaths - driveFolderPaths).sorted()

        var sizeMismatches = (driveFilePaths intersect localFilePaths)
            .mapNotNull { path ->
                val driveSize = driveFiles[path]
                val localSize = localFiles[path]
                if (driveSize != null && localSize != null && driveSize != localSize) {
                    SizeMismatch(path = path, driveSize = driveSize, localSize = localSize)
                } else {
                    null
                }
            }
            .sortedBy { it.path }

        val obsidianSingleMismatchPath = sizeMismatches.singleOrNull()
            ?.path
            ?.takeIf { it.startsWith(".obsidian/") }
        var obsidianMismatchPolicyNote: String? = null
        if (obsidianSingleMismatchPath != null) {
            val downloadResult = retryDownloadVerificationMismatch(
                mismatchPath = obsidianSingleMismatchPath,
                rootDir = rootDir,
                driveFileIds = driveFileIds
            )
            if (downloadResult) {
                localFiles[obsidianSingleMismatchPath] = File(rootDir, obsidianSingleMismatchPath).length()
            }
            sizeMismatches = (driveFilePaths intersect localFilePaths)
                .mapNotNull { path ->
                    val driveSize = driveFiles[path]
                    val localSize = localFiles[path]
                    if (driveSize != null && localSize != null && driveSize != localSize) {
                        SizeMismatch(path = path, driveSize = driveSize, localSize = localSize)
                    } else {
                        null
                    }
                }
                .sortedBy { it.path }

            if (sizeMismatches.singleOrNull()?.path == obsidianSingleMismatchPath) {
                // Obsidian state file can keep changing while app is open; treat this single mismatch as ignorable after retry.
                sizeMismatches = emptyList()
                obsidianMismatchPolicyNote =
                    "single .obsidian size mismatch persisted after retry; treated as PASS by policy"
                logger.log("[WARNING] 검증 정책 적용: $obsidianMismatchPolicyNote ($obsidianSingleMismatchPath)", folder.accountEmail)
            } else {
                obsidianMismatchPolicyNote =
                    "single .obsidian size mismatch resolved by immediate redownload"
                logger.log("검증 보정 완료: $obsidianMismatchPolicyNote ($obsidianSingleMismatchPath)", folder.accountEmail)
            }
        }

        val pathPrefixMismatch = detectPathPrefixMismatch(
            missingLocalFiles = missingLocalFiles,
            extraLocalFiles = extraLocalFiles,
            missingLocalFolders = missingLocalFolders,
            extraLocalFolders = extraLocalFolders
        )

        val basePass = missingLocalFiles.isEmpty() &&
            extraLocalFiles.isEmpty() &&
            missingLocalFolders.isEmpty() &&
            extraLocalFolders.isEmpty() &&
            sizeMismatches.isEmpty()
        val result = if (obsidianMismatchPolicyNote?.contains("treated as PASS by policy") == true) {
            "WARN"
        } else if (basePass) {
            "PASS"
        } else {
            "FAIL"
        }
        val isPass = result != "FAIL"

        var summary = buildVerificationSummary(
            missingLocalFiles = missingLocalFiles,
            extraLocalFiles = extraLocalFiles,
            missingLocalFolders = missingLocalFolders,
            extraLocalFolders = extraLocalFolders,
            sizeMismatches = sizeMismatches,
            pathPrefixMismatch = pathPrefixMismatch
        )
        if (obsidianMismatchPolicyNote != null) {
            summary = if (summary == "PASS" || result == "WARN") {
                "WARN | $obsidianMismatchPolicyNote"
            } else {
                "$summary | $obsidianMismatchPolicyNote"
            }
        }

        if (!isPass) {
            val missingFileRoots = buildTopRootBreakdown(missingLocalFiles)
            val missingFolderRoots = buildTopRootBreakdown(missingLocalFolders)
            val extraFileRoots = buildTopRootBreakdown(extraLocalFiles)
            val extraFolderRoots = buildTopRootBreakdown(extraLocalFolders)
            val sampleMissingFiles = missingLocalFiles.take(3).joinToString(" | ").ifBlank { "none" }
            val sampleMissingFolders = missingLocalFolders.take(3).joinToString(" | ").ifBlank { "none" }
            logger.log(
                "[DEBUG] 검증 차이 요약: driveFiles=${driveFiles.size}, localFiles=${localFiles.size}, driveFolders=${driveFolders.size}, localFolders=${localFolders.size}, missingFiles=${missingLocalFiles.size}, missingFolders=${missingLocalFolders.size}, extraFiles=${extraLocalFiles.size}, extraFolders=${extraLocalFolders.size}, sizeMismatch=${sizeMismatches.size}",
                folder.accountEmail
            )
            logger.log(
                "[DEBUG] 검증 루트 분포: missingFileRoots=${missingFileRoots.joinToString(",") { "${it.root}:${it.count}" }}, missingFolderRoots=${missingFolderRoots.joinToString(",") { "${it.root}:${it.count}" }}, extraFileRoots=${extraFileRoots.joinToString(",") { "${it.root}:${it.count}" }}, extraFolderRoots=${extraFolderRoots.joinToString(",") { "${it.root}:${it.count}" }}",
                folder.accountEmail
            )
            logger.log(
                "[DEBUG] 검증 샘플: missingFiles=$sampleMissingFiles ; missingFolders=$sampleMissingFolders",
                folder.accountEmail
            )
        }

        val report = buildVerificationMarkdown(
            folder = folder,
            result = result,
            driveFiles = driveFiles,
            driveFolders = driveFolders,
            localFiles = localFiles,
            localFolders = localFolders,
            missingLocalFiles = missingLocalFiles,
            extraLocalFiles = extraLocalFiles,
            missingLocalFolders = missingLocalFolders,
            extraLocalFolders = extraLocalFolders,
            sizeMismatches = sizeMismatches,
            pathPrefixMismatch = pathPrefixMismatch,
            summary = summary,
            diagnostics = diagnostics
        )
        reportFile.writeText(report, Charsets.UTF_8)

        publishVerificationProgress(
            currentFile = "검증 리포트",
            currentStage = 4,
            totalStages = verificationTotalStages,
            statusMessage = "검증 리포트 저장/Drive 업로드 중..."
        )
        uploadVerificationReportToDrive(folder, reportFile)

        return VerificationReportResult(
            result = result,
            isPass = isPass,
            reportPath = reportFile.absolutePath,
            summary = summary,
            missingLocalFiles = missingLocalFiles,
            missingLocalFolders = missingLocalFolders,
            extraLocalFiles = extraLocalFiles
        )
    }

    private fun publishVerificationProgress(
        currentFile: String,
        currentStage: Int,
        totalStages: Int,
        statusMessage: String
    ) {
        publishSyncProgress(
            currentFile = currentFile,
            currentIndex = currentStage.coerceAtLeast(0),
            totalFiles = totalStages.coerceAtLeast(1),
            isUploading = false,
            statusMessage = statusMessage
        )
    }

    private suspend fun enqueueExtraLocalFilesAsPendingUpload(folder: SyncFolderEntity, relativePaths: List<String>): Int {
        val root = File(folder.localPath).absoluteFile
        val newPending = mutableListOf<PendingUpload>()
        for (relativePath in relativePaths) {
            if (isAlwaysDownloadRelativePath(relativePath)) continue
            val localFile = File(root, relativePath)
            if (!localFile.exists()) continue
            if (!localFile.isFile) continue
            if (isBackupPath(localFile.absolutePath)) continue
            if (isExcludedAbsolutePath(localFile.absolutePath)) continue
            val parentFolderId = ensureDriveParentFolderId(folder, localFile) ?: folder.driveFolderId
            newPending += PendingUpload(
                folderId = folder.id,
                localFile = localFile,
                driveFolderId = parentFolderId,
                isNewFile = true,
                driveFileId = null,
                accountEmail = folder.accountEmail
            )
        }
        if (newPending.isEmpty()) return 0
        return appendPendingUploadsDedup(newPending)
    }

    private fun pendingUploadKey(upload: PendingUpload): String {
        // Deduplicate by folder + local path to prevent duplicate upload requests for the same file.
        return "${upload.folderId}|${upload.localFile.absolutePath}"
    }

    private fun appendPendingUploadsDedup(newUploads: List<PendingUpload>): Int {
        if (newUploads.isEmpty()) return 0
        val existingKeys = _pendingUploads.value.map { pendingUploadKey(it) }.toMutableSet()
        val deduped = newUploads.filter { existingKeys.add(pendingUploadKey(it)) }
        if (deduped.isNotEmpty()) {
            _pendingUploads.value = _pendingUploads.value + deduped
        }
        return deduped.size
    }

    private fun appendSkippedUploadsDedup(newUploads: List<PendingUpload>): Int {
        if (newUploads.isEmpty()) return 0
        val existingKeys = _skippedUploads.value.map { pendingUploadKey(it) }.toMutableSet()
        val deduped = newUploads.filter { existingKeys.add(pendingUploadKey(it)) }
        if (deduped.isNotEmpty()) {
            _skippedUploads.value = _skippedUploads.value + deduped
        }
        return deduped.size
    }

    fun restoreSkippedUploadsToPending(targets: List<PendingUpload>): Int {
        if (targets.isEmpty()) return 0
        val restored = appendPendingUploadsDedup(targets)
        val targetKeys = targets.map { pendingUploadKey(it) }.toSet()
        _skippedUploads.value = _skippedUploads.value.filterNot { pendingUploadKey(it) in targetKeys }
        return restored
    }

    private fun publishSyncProgress(
        currentFile: String,
        currentIndex: Int,
        totalFiles: Int,
        isUploading: Boolean,
        statusMessage: String
    ) {
        _syncProgress.value = SyncProgress(
            currentFile = currentFile,
            currentIndex = currentIndex.coerceAtLeast(0),
            totalFiles = totalFiles.coerceAtLeast(1),
            bytesTransferred = 0L,
            totalBytes = 0L,
            isUploading = isUploading,
            statusMessage = statusMessage
        )
    }

    private suspend fun collectDriveTree(
        driveFolderId: String,
        prefix: String,
        files: MutableMap<String, Long>,
        fileIds: MutableMap<String, String>,
        folders: MutableSet<String>,
        progressCounter: AtomicInteger = AtomicInteger(0),
        onProgress: ((Int) -> Unit)? = null
    ) {
        val items = driveHelper.listAllFiles(driveFolderId)
        for (item in items) {
            val relativePath = if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
            if (relativePath.split('/').contains(BACKUP_DIR_NAME)) continue
            if (isVerificationArtifactRelativePath(relativePath)) continue
            if (isExcludedRelativePath(relativePath)) continue
            val scanned = progressCounter.incrementAndGet()
            if (scanned % 100 == 0) {
                onProgress?.invoke(scanned)
            }
            if (item.isFolder) {
                folders.add(relativePath)
                collectDriveTree(item.id, relativePath, files, fileIds, folders, progressCounter, onProgress)
            } else {
                files[relativePath] = item.size
                fileIds[relativePath] = item.id
            }
        }
        onProgress?.invoke(progressCounter.get())
    }

    private suspend fun retryDownloadVerificationMismatch(
        mismatchPath: String,
        rootDir: File,
        driveFileIds: Map<String, String>
    ): Boolean {
        val driveFileId = driveFileIds[mismatchPath] ?: return false
        val localFile = File(rootDir, mismatchPath)
        localFile.parentFile?.mkdirs()
        val result = driveHelper.downloadFileDetailed(driveFileId, localFile.absolutePath)
        return result.success
    }

    private fun collectLocalTree(
        rootDir: File,
        files: MutableMap<String, Long>,
        folders: MutableSet<String>,
        onProgress: ((Int) -> Unit)? = null
    ) {
        if (!rootDir.exists() || !rootDir.isDirectory) return

        var scanned = 0
        rootDir.walkTopDown().forEach { path ->
            if (path.absolutePath == rootDir.absolutePath) return@forEach
            val relativePath = path.relativeTo(rootDir).invariantSeparatorsPath
            if (relativePath.isBlank()) return@forEach

            val segments = relativePath.split('/')
            if (segments.contains(BACKUP_DIR_NAME)) return@forEach
            if (isVerificationArtifactRelativePath(relativePath)) return@forEach
            if (isExcludedRelativePath(relativePath)) return@forEach

            scanned++
            if (scanned % 200 == 0) {
                onProgress?.invoke(scanned)
            }

            if (path.isDirectory) {
                folders.add(relativePath)
            } else if (path.isFile) {
                files[relativePath] = path.length()
            }
        }
        onProgress?.invoke(scanned)
    }

    private fun buildVerificationMarkdown(
        folder: SyncFolderEntity,
        result: String,
        driveFiles: Map<String, Long>,
        driveFolders: Set<String>,
        localFiles: Map<String, Long>,
        localFolders: Set<String>,
        missingLocalFiles: List<String>,
        extraLocalFiles: List<String>,
        missingLocalFolders: List<String>,
        extraLocalFolders: List<String>,
        sizeMismatches: List<SizeMismatch>,
        pathPrefixMismatch: PathPrefixMismatch?,
        summary: String,
        diagnostics: SyncRunDiagnostics?
    ): String {
        val lines = mutableListOf<String>()
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        lines += "# Sync Verification Report"
        lines += ""
        lines += "- Generated at: $generatedAt"
        lines += "- Sync folder name: ${folder.driveFolderName}"
        lines += "- Sync local root: ${File(folder.localPath).absolutePath}"
        lines += "- Sync Drive folder ID: ${folder.driveFolderId}"
        lines += "- Result: $result"
        lines += "- Drive files: ${driveFiles.size}"
        lines += "- Local files: ${localFiles.size}"
        lines += "- Drive folders: ${driveFolders.size}"
        lines += "- Local folders: ${localFolders.size}"
        lines += "- Summary: $summary"
        lines += ""
        lines += "## Sync Diagnostics"
        if (diagnostics == null) {
            lines += "- none"
        } else {
            lines += "- Policy: folderSyncDirection=${diagnostics.folderSyncDirection}, defaultSyncDirection=${diagnostics.defaultSyncDirection}, autoUploadEnabled=${diagnostics.autoUploadEnabled}"
            lines += "- ExecutionPlan: initialSync=${diagnostics.isInitialSync}, dirtyLocalItems=${diagnostics.dirtyLocalItems}, trackedItems=${diagnostics.trackedItems}, changesProcessedSuccessfully=${diagnostics.changesProcessedSuccessfully}, fullScanExecuted=${diagnostics.fullScanExecuted}, fullScanReason=${diagnostics.fullScanReason}"
            lines += "- StartPageToken(before sync): ${diagnostics.lastStartPageToken ?: "none"}"
            diagnostics.changesDebugStats?.let { stats ->
                lines += "- ChangesStats: processed=${stats.processed}, removed=${stats.removed}, folderMetadataUpdated=${stats.folderMetadataUpdated}, outOfScope=${stats.outOfScope}, uploaded=${stats.uploaded}, downloaded=${stats.downloaded}, skipped=${stats.skipped}, errors=${stats.errors}, conflicts=${stats.conflicts}"
            } ?: run {
                lines += "- ChangesStats: none"
            }
            diagnostics.extraNotes.forEach { note ->
                lines += "- Note: $note"
            }
        }
        lines += ""

        val obsidianExcluded = isObsidianExcludedByUser()
        val driveObsidianFiles = driveFiles.keys.count { it.startsWith(".obsidian/") }
        val localObsidianFiles = localFiles.keys.count { it.startsWith(".obsidian/") }
        val missingObsidianFiles = missingLocalFiles.count { it.startsWith(".obsidian/") }
        val extraObsidianFiles = extraLocalFiles.count { it.startsWith(".obsidian/") }
        val missingObsidianFolders = missingLocalFolders.count { it == ".obsidian" || it.startsWith(".obsidian/") }
        val extraObsidianFolders = extraLocalFolders.count { it == ".obsidian" || it.startsWith(".obsidian/") }
        val obsidianSizeMismatches = sizeMismatches.count { it.path.startsWith(".obsidian/") }
        val obsidianCheckResult = when {
            obsidianExcluded -> "SKIPPED (excluded)"
            missingObsidianFiles == 0 &&
                extraObsidianFiles == 0 &&
                missingObsidianFolders == 0 &&
                extraObsidianFolders == 0 &&
                obsidianSizeMismatches == 0 -> "PASS"
            else -> "WARN"
        }
        lines += "## Obsidian Sync Check"
        lines += "- Exclusion setting: ${if (obsidianExcluded) "ON" else "OFF"}"
        lines += "- Effective policy: ${if (obsidianExcluded) "excluded from sync" else "server-priority download (upload/delete blocked)"}"
        lines += "- Drive .obsidian files: $driveObsidianFiles"
        lines += "- Local .obsidian files: $localObsidianFiles"
        lines += "- Missing .obsidian files: $missingObsidianFiles"
        lines += "- Extra .obsidian files: $extraObsidianFiles"
        lines += "- Missing .obsidian folders: $missingObsidianFolders"
        lines += "- Extra .obsidian folders: $extraObsidianFolders"
        lines += "- .obsidian size mismatches: $obsidianSizeMismatches"
        lines += "- Check result: $obsidianCheckResult"
        lines += ""

        lines += "## Failure Reasons"
        if (result == "PASS") {
            lines += "- none"
        } else {
            if (missingLocalFolders.isNotEmpty()) lines += "- Missing local folders: ${missingLocalFolders.size}"
            if (extraLocalFolders.isNotEmpty()) lines += "- Extra local folders: ${extraLocalFolders.size}"
            if (missingLocalFiles.isNotEmpty()) lines += "- Missing local files: ${missingLocalFiles.size}"
            if (extraLocalFiles.isNotEmpty()) lines += "- Extra local files: ${extraLocalFiles.size}"
            if (sizeMismatches.isNotEmpty()) lines += "- File size mismatches: ${sizeMismatches.size}"
            if (pathPrefixMismatch != null) {
                lines += "- Suspected local root prefix mismatch: local paths are prefixed with '${pathPrefixMismatch.prefix}/' for ${pathPrefixMismatch.matchedCount}/${pathPrefixMismatch.missingCount} missing paths"
            }
        }
        lines += ""

        val missingFileRoots = buildTopRootBreakdown(missingLocalFiles)
        val missingFolderRoots = buildTopRootBreakdown(missingLocalFolders)
        val extraFileRoots = buildTopRootBreakdown(extraLocalFiles)
        val extraFolderRoots = buildTopRootBreakdown(extraLocalFolders)
        lines += "## Debug Hints"
        if (result == "PASS") {
            lines += "- none"
        } else {
            if (missingFileRoots.isNotEmpty()) {
                lines += "- Missing file top roots: ${missingFileRoots.joinToString(", ") { "${it.root}=${it.count}" }}"
            }
            if (missingFolderRoots.isNotEmpty()) {
                lines += "- Missing folder top roots: ${missingFolderRoots.joinToString(", ") { "${it.root}=${it.count}" }}"
            }
            if (extraFileRoots.isNotEmpty()) {
                lines += "- Extra file top roots: ${extraFileRoots.joinToString(", ") { "${it.root}=${it.count}" }}"
            }
            if (extraFolderRoots.isNotEmpty()) {
                lines += "- Extra folder top roots: ${extraFolderRoots.joinToString(", ") { "${it.root}=${it.count}" }}"
            }
            if (pathPrefixMismatch == null && missingLocalFiles.size >= 100 && extraLocalFiles.isEmpty()) {
                lines += "- Hint: Many drive files are missing locally. Check download errors, permission/storage issues, or sync direction policy."
            }
        }
        lines += ""

        appendVerificationSection(lines, "Missing Local Folders (Drive only)", missingLocalFolders)
        appendVerificationSection(lines, "Extra Local Folders (Local only)", extraLocalFolders)
        appendVerificationSection(lines, "Missing Local Files (Drive only)", missingLocalFiles)
        appendVerificationSection(lines, "Extra Local Files (Local only)", extraLocalFiles)

        lines += "## Size Mismatches"
        if (sizeMismatches.isEmpty()) {
            lines += "- none"
        } else {
            sizeMismatches.forEach { mismatch ->
                lines += "- ${mismatch.path} (drive: ${mismatch.driveSize}, local: ${mismatch.localSize})"
            }
        }
        lines += ""
        return lines.joinToString("\n")
    }

    private fun buildVerificationSummary(
        missingLocalFiles: List<String>,
        extraLocalFiles: List<String>,
        missingLocalFolders: List<String>,
        extraLocalFolders: List<String>,
        sizeMismatches: List<SizeMismatch>,
        pathPrefixMismatch: PathPrefixMismatch?
    ): String {
        if (missingLocalFiles.isEmpty() &&
            extraLocalFiles.isEmpty() &&
            missingLocalFolders.isEmpty() &&
            extraLocalFolders.isEmpty() &&
            sizeMismatches.isEmpty()
        ) {
            return "no differences"
        }

        val parts = mutableListOf<String>()
        if (missingLocalFolders.isNotEmpty()) parts += "missing folders=${missingLocalFolders.size}"
        if (extraLocalFolders.isNotEmpty()) parts += "extra folders=${extraLocalFolders.size}"
        if (missingLocalFiles.isNotEmpty()) parts += "missing files=${missingLocalFiles.size}"
        if (extraLocalFiles.isNotEmpty()) parts += "extra files=${extraLocalFiles.size}"
        if (sizeMismatches.isNotEmpty()) parts += "size mismatches=${sizeMismatches.size}"
        pathPrefixMismatch?.let {
            parts += "suspected root prefix='${it.prefix}/' (${it.matchedCount}/${it.missingCount})"
        }
        val missingFileRoots = buildTopRootBreakdown(missingLocalFiles)
        val missingFolderRoots = buildTopRootBreakdown(missingLocalFolders)
        if (missingFileRoots.isNotEmpty()) {
            parts += "missing file roots=${missingFileRoots.joinToString(",") { "${it.root}:${it.count}" }}"
        }
        if (missingFolderRoots.isNotEmpty()) {
            parts += "missing folder roots=${missingFolderRoots.joinToString(",") { "${it.root}:${it.count}" }}"
        }

        val samples = mutableListOf<String>()
        missingLocalFiles.take(2).forEach { samples += "missing file: $it" }
        extraLocalFiles.take(2).forEach { samples += "extra file: $it" }
        missingLocalFolders.take(2).forEach { samples += "missing folder: $it" }
        extraLocalFolders.take(2).forEach { samples += "extra folder: $it" }
        sizeMismatches.firstOrNull()?.let { samples += "size mismatch: ${it.path}" }
        pathPrefixMismatch?.let { samples += "root prefix mismatch: ${it.prefix}/" }

        return if (samples.isEmpty()) {
            parts.joinToString(", ")
        } else {
            "${parts.joinToString(", ")} | sample: ${samples.joinToString("; ")}"
        }
    }

    private fun buildTopRootBreakdown(paths: List<String>, limit: Int = 3): List<RootCount> {
        if (paths.isEmpty()) return emptyList()
        val counts = mutableMapOf<String, Int>()
        for (path in paths) {
            val root = path.substringBefore("/", missingDelimiterValue = path)
            val key = if (root.isBlank()) "(root)" else root
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { RootCount(root = it.key, count = it.value) }
    }

    private fun resetCurrentSyncDownloadDiagnostics() {
        currentSyncDownloadFailedCount = 0
        currentSyncDownloadSkippedNonDownloadableCount = 0
        currentSyncDownloadFailureReasonCounts.clear()
        currentSyncDownloadFailureSamples.clear()
    }

    private fun recordCurrentSyncDownloadFailure(
        path: String,
        reason: String,
        mimeType: String?,
        isNonDownloadableSkip: Boolean,
        accountEmail: String
    ) {
        if (isNonDownloadableSkip) {
            currentSyncDownloadSkippedNonDownloadableCount++
        } else {
            currentSyncDownloadFailedCount++
        }
        currentSyncDownloadFailureReasonCounts[reason] =
            (currentSyncDownloadFailureReasonCounts[reason] ?: 0) + 1
        if (currentSyncDownloadFailureSamples.size < 10) {
            currentSyncDownloadFailureSamples += "$path => $reason (mime=${mimeType ?: "unknown"})"
        }
        logger.log(
            "[DEBUG] 일반 동기화 다운로드 이슈: path=$path, reason=$reason, mime=${mimeType ?: "unknown"}, skippedNonDownloadable=$isNonDownloadableSkip",
            accountEmail
        )
    }

    private fun buildCurrentSyncDownloadDiagnosticsNotes(): List<String> {
        val reasonSummary = if (currentSyncDownloadFailureReasonCounts.isEmpty()) {
            "none"
        } else {
            currentSyncDownloadFailureReasonCounts.entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString(", ") { "${it.key}:${it.value}" }
        }
        val notes = mutableListOf<String>()
        notes += "syncDownloadFailed=$currentSyncDownloadFailedCount"
        notes += "syncDownloadSkippedNonDownloadable=$currentSyncDownloadSkippedNonDownloadableCount"
        notes += "syncDownloadFailureReasons=$reasonSummary"
        val queue = _pendingUploads.value
        val queueSample = queue.take(3).joinToString(" | ") { it.localFile.absolutePath }.ifBlank { "none" }
        notes += "pendingUploadQueueCount=${queue.size}"
        notes += "pendingUploadQueueSample=$queueSample"
        if (currentSyncDownloadFailureSamples.isNotEmpty()) {
            notes += "syncDownloadFailureSamples=${currentSyncDownloadFailureSamples.joinToString(" | ")}"
        }
        return notes
    }

    private fun logPendingUploadQueueState(accountEmail: String?, phase: String) {
        val queue = _pendingUploads.value
        val sample = queue.take(5).joinToString(" | ") { it.localFile.absolutePath }.ifBlank { "none" }
        logger.log("업로드 대기 큐 상태($phase): count=${queue.size}, sample=$sample", accountEmail)
    }

    private fun detectPathPrefixMismatch(
        missingLocalFiles: List<String>,
        extraLocalFiles: List<String>,
        missingLocalFolders: List<String>,
        extraLocalFolders: List<String>
    ): PathPrefixMismatch? {
        val missingPaths = (missingLocalFiles + missingLocalFolders).toSet()
        val extraPaths = (extraLocalFiles + extraLocalFolders).toSet()
        if (missingPaths.isEmpty() || extraPaths.isEmpty()) return null

        val candidatePrefixes = extraPaths
            .mapNotNull { path ->
                val idx = path.indexOf('/')
                if (idx <= 0) null else path.substring(0, idx)
            }
            .toSet()
        if (candidatePrefixes.isEmpty()) return null

        var bestPrefix: String? = null
        var bestMatchCount = 0

        for (prefix in candidatePrefixes) {
            var matches = 0
            for (missingPath in missingPaths) {
                if (extraPaths.contains("$prefix/$missingPath")) {
                    matches++
                }
            }

            if (matches > bestMatchCount) {
                bestMatchCount = matches
                bestPrefix = prefix
            }
        }

        if (bestPrefix == null || bestMatchCount < 10) return null

        val minimumMeaningfulMatches = maxOf(10, (missingPaths.size * 0.6).toInt())
        if (bestMatchCount < minimumMeaningfulMatches) return null

        return PathPrefixMismatch(
            prefix = bestPrefix,
            matchedCount = bestMatchCount,
            missingCount = missingPaths.size
        )
    }

    private fun appendVerificationSection(lines: MutableList<String>, title: String, items: List<String>) {
        lines += "## $title"
        if (items.isEmpty()) {
            lines += "- none"
        } else {
            items.forEach { lines += "- $it" }
        }
        lines += ""
    }

    private fun isBackupPath(path: String): Boolean {
        return path.split(File.separatorChar).any { it == BACKUP_DIR_NAME }
    }

    private suspend fun logInFlightDirtyCount(
        folder: SyncFolderEntity,
        folderId: String,
        syncStartedAt: Long,
        phase: String
    ) {
        val inFlightDirty = dirtyLocalDao.countDirtyItemsByFolderAfter(folderId, syncStartedAt)
        logger.log(
            "동기화 중 유입 dirty 건수($phase): $inFlightDirty (syncStartedAt=$syncStartedAt)",
            folder.accountEmail
        )
    }

    private fun cleanupExpiredDeferredDeletes(folder: SyncFolderEntity) {
        val rootDir = File(folder.localPath)
        val deferredRoot = File(File(rootDir, BACKUP_DIR_NAME), DEFERRED_DELETE_DIR_NAME)
        if (!deferredRoot.exists() || !deferredRoot.isDirectory) return

        val cutoff = System.currentTimeMillis() - DEFERRED_DELETE_RETENTION_MS
        deferredRoot.listFiles().orEmpty().forEach { archived ->
            val archivedAt = archived.lastModified()
            if (archivedAt in 1..cutoff) {
                if (archived.deleteRecursively()) {
                    logger.log("보관 만료 삭제(30일): ${archived.absolutePath}", folder.accountEmail)
                }
            }
        }
    }

    private fun archiveForDeferredDeletion(folder: SyncFolderEntity, target: File, reason: String): Boolean {
        if (!target.exists()) return true
        if (isBackupPath(target.absolutePath)) return true

        val rootDir = File(folder.localPath).absoluteFile
        val deferredRoot = File(File(rootDir, BACKUP_DIR_NAME), DEFERRED_DELETE_DIR_NAME).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val batchDir = File(deferredRoot, "${timestamp}_${UUID.randomUUID().toString().substring(0, 8)}").apply { mkdirs() }
        val relativePath = try {
            target.absoluteFile.relativeTo(rootDir).invariantSeparatorsPath
        } catch (_: IllegalArgumentException) {
            target.name
        }
        val archiveTarget = File(batchDir, relativePath)
        archiveTarget.parentFile?.mkdirs()

        val copied = try {
            if (target.isDirectory) {
                target.copyRecursively(archiveTarget, overwrite = true)
            } else {
                target.copyTo(archiveTarget, overwrite = true)
            }
            true
        } catch (e: Exception) {
            logger.log("[WARNING] 삭제 보관 복사 실패: ${target.absolutePath} ($reason, ${e.message})", folder.accountEmail)
            false
        }
        if (!copied) return false

        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!deleted && target.exists()) {
            logger.log("[WARNING] 보관 후 원본 삭제 실패: ${target.absolutePath} ($reason)", folder.accountEmail)
            return false
        }
        batchDir.setLastModified(System.currentTimeMillis())
        logger.log("삭제 보류 보관 완료: $relativePath (사유=$reason, 보관=30일)", folder.accountEmail)
        return true
    }

    private fun isExcludedRelativePath(path: String): Boolean {
        if (isAlwaysDownloadRelativePath(path) && !isObsidianExcludedByUser()) return false
        return SyncExclusions.isExcludedRelativePath(path, syncPreferences.userExcludedPaths)
    }

    private fun isAlwaysDownloadRelativePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').trim().trimStart('/')
        return normalized == ".obsidian" || normalized.startsWith(".obsidian/")
    }

    private fun isAlwaysDownloadPath(folder: SyncFolderEntity, file: File): Boolean {
        return try {
            val relPath = file.relativeTo(File(folder.localPath)).invariantSeparatorsPath
            isAlwaysDownloadRelativePath(relPath)
        } catch (_: IllegalArgumentException) {
            val normalized = file.absolutePath.replace('\\', '/')
            val root = File(folder.localPath).absolutePath.replace('\\', '/').trimEnd('/')
            normalized.startsWith("$root/.obsidian/")
        }
    }

    private fun isVerificationArtifactRelativePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.substringAfterLast('/') == VERIFY_REPORT_FILE_NAME
    }

    private fun isExcludedAbsolutePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if ((normalized.contains("/.obsidian/") || normalized.endsWith("/.obsidian")) &&
            !isObsidianExcludedByUser()
        ) {
            return false
        }
        return SyncExclusions.isExcludedAbsolutePath(path, syncPreferences.userExcludedPaths)
    }

    private fun isObsidianExcludedByUser(): Boolean {
        val rules = syncPreferences.userExcludedPaths
        return rules.any { token ->
            val sep = token.indexOf(':')
            if (sep <= 0) return@any false
            val type = token.substring(0, sep).lowercase()
            val value = SyncExclusions.normalizeRelativePath(token.substring(sep + 1))
            if (type !in setOf("file", "directory")) return@any false
            value == ".obsidian" || value.startsWith(".obsidian/")
        }
    }

    private fun isExcludedPath(folder: SyncFolderEntity, file: File): Boolean {
        return try {
            val relPath = file.relativeTo(File(folder.localPath)).invariantSeparatorsPath
            isExcludedRelativePath(relPath)
        } catch (_: IllegalArgumentException) {
            isExcludedAbsolutePath(file.absolutePath)
        }
    }

    private suspend fun downloadDriveVersion(
        folder: SyncFolderEntity,
        localFile: File,
        driveFile: uk.xmlangel.googledrivesync.data.drive.DriveItem,
        existingItem: SyncItemEntity?
    ): Boolean {
        val statusMsg = "정책(.obsidian) 다운로드 우선 적용: ${localFile.absolutePath}"
        logger.log(statusMsg, folder.accountEmail)
        currentStatusMessage = statusMsg
        return try {
            val result = driveHelper.downloadFileDetailed(driveFile.id, localFile.absolutePath)
            if (!result.success) {
                if (result.skipped) {
                    recordCurrentSyncDownloadFailure(
                        path = localFile.absolutePath,
                        reason = result.reason ?: "non_downloadable_google_type",
                        mimeType = result.mimeType,
                        isNonDownloadableSkip = true,
                        accountEmail = folder.accountEmail
                    )
                } else {
                    recordCurrentSyncDownloadFailure(
                        path = localFile.absolutePath,
                        reason = result.reason ?: "download_failed",
                        mimeType = result.mimeType,
                        isNonDownloadableSkip = false,
                        accountEmail = folder.accountEmail
                    )
                    logger.log("[ERROR] 정책(.obsidian) 다운로드 실패: ${localFile.absolutePath}", folder.accountEmail)
                }
                return false
            }
            updateSyncItem(
                existingItem = existingItem ?: createSyncItem(folder, localFile, driveFile.id, driveFile.md5Checksum),
                localFile = localFile,
                driveFileId = driveFile.id,
                driveModifiedTime = driveFile.modifiedTime,
                driveSize = driveFile.size,
                status = SyncStatus.SYNCED,
                md5Checksum = driveFile.md5Checksum
            )
            true
        } catch (e: Exception) {
            if (isFatalNetworkError(e)) throw e
            val exceptionMsg = e.message ?: e.javaClass.simpleName
            logger.log("[ERROR] 정책(.obsidian) 다운로드 예외: ${localFile.absolutePath} ($exceptionMsg)", folder.accountEmail)
            false
        }
    }

    private fun saveSkippedVerificationReport(
        folder: SyncFolderEntity,
        reason: String
    ): VerificationReportResult {
        val rootDir = File(folder.localPath)
        val backupDir = File(rootDir, BACKUP_DIR_NAME).apply { mkdirs() }
        val reportFile = File(backupDir, VERIFY_REPORT_FILE_NAME)
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val report = buildString {
            appendLine("# Sync Verification Report")
            appendLine()
            appendLine("- Generated at: $generatedAt")
            appendLine("- Sync folder name: ${folder.driveFolderName}")
            appendLine("- Sync local root: ${File(folder.localPath).absolutePath}")
            appendLine("- Sync Drive folder ID: ${folder.driveFolderId}")
            appendLine("- Result: SKIPPED")
            appendLine("- Reason: $reason")
            appendLine()
            appendLine("## Failure Reasons")
            appendLine("- verification skipped due to network condition")
            appendLine()
        }
        reportFile.writeText(report, Charsets.UTF_8)
        return VerificationReportResult(
            result = "SKIPPED",
            isPass = true,
            reportPath = reportFile.absolutePath,
            summary = "verification skipped due to network condition"
        )
    }

    private suspend fun uploadVerificationReportToDrive(folder: SyncFolderEntity, reportFile: File) {
        try {
            if (!reportFile.exists()) return

            val deviceId = getDeviceScopedId()
            val accountSegment = sanitizeDriveSegment(folder.accountId)
            val deviceSegment = sanitizeDriveSegment(deviceId)
            val segments = listOf(BACKUP_DIR_NAME, accountSegment, deviceSegment)
            val driveParentId = ensureDriveFolderPath(folder.driveFolderId, segments)
            if (driveParentId == null) {
                logger.log("[WARNING] 검증 리포트 Drive 업로드 경로 생성 실패", folder.accountEmail)
                return
            }

            val remoteFileName = VERIFY_REPORT_FILE_NAME
            val existing = driveHelper.findFile(remoteFileName, driveParentId)
            if (existing != null) {
                val updated = driveHelper.updateFile(
                    fileId = existing.id,
                    localPath = reportFile.absolutePath,
                    mimeType = "text/markdown"
                )
                if (updated != null) {
                    logger.log(
                        "검증 리포트 Drive 업데이트 완료: ${segments.joinToString("/")}/$remoteFileName",
                        folder.accountEmail
                    )
                } else {
                    logger.log("[WARNING] 검증 리포트 Drive 업데이트 실패", folder.accountEmail)
                }
            } else {
                val created = driveHelper.uploadFile(
                    localPath = reportFile.absolutePath,
                    fileName = remoteFileName,
                    parentFolderId = driveParentId,
                    mimeType = "text/markdown"
                )
                if (created != null) {
                    logger.log(
                        "검증 리포트 Drive 업로드 완료: ${segments.joinToString("/")}/$remoteFileName",
                        folder.accountEmail
                    )
                } else {
                    logger.log("[WARNING] 검증 리포트 Drive 업로드 실패", folder.accountEmail)
                }
            }
        } catch (e: Exception) {
            if (isFatalNetworkError(e)) throw e
            val exceptionMsg = e.message ?: e.javaClass.simpleName
            logger.log("[WARNING] 검증 리포트 Drive 업로드 예외: $exceptionMsg", folder.accountEmail)
        }
    }

    private suspend fun ensureDriveFolderPath(rootFolderId: String, segments: List<String>): String? {
        var parentId = rootFolderId
        for (segment in segments) {
            val existing = driveHelper.findFolder(segment, parentId)
            val resolved = existing ?: driveHelper.createFolder(segment, parentId)
            if (resolved == null) return null
            parentId = resolved.id
        }
        return parentId
    }

    private fun getDeviceScopedId(): String {
        val rawId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        return sanitizeDriveSegment(rawId ?: "unknown_device")
    }

    private fun sanitizeDriveSegment(value: String): String {
        return value
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "unknown" }
    }

    private fun isVerificationSkippableNetworkError(e: Throwable): Boolean {
        return e is java.net.UnknownHostException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException ||
            e is java.net.NoRouteToHostException ||
            e is javax.net.ssl.SSLHandshakeException ||
            (e is java.io.IOException && e.message?.contains("Unable to resolve host", ignoreCase = true) == true)
    }

    suspend fun forcePullFromServer(folderId: String): ForcePullExecution {
        logger.log("강제 서버 동기화 요청: folderId=$folderId")
        if (_isSyncing.value) {
            logger.log("[WARNING] 강제 서버 동기화 거부: 이미 동기화 진행 중")
            return ForcePullExecution(
                downloadedFiles = 0,
                failedFiles = 0,
                ensuredFolders = 0,
                removedLocalEntries = 0,
                reportPath = null,
                summary = "다른 동기화 작업이 진행 중입니다."
            )
        }

        val folder = syncFolderDao.getSyncFolderById(folderId)
            ?: return ForcePullExecution(
                downloadedFiles = 0,
                failedFiles = 0,
                ensuredFolders = 0,
                removedLocalEntries = 0,
                reportPath = null,
                summary = "동기화 폴더를 찾을 수 없습니다."
            ).also {
                logger.log("[ERROR] 강제 서버 동기화 실패: folderId=${folderId}를 찾을 수 없음")
            }

        if (!driveHelper.initializeDriveService(folder.accountEmail)) {
            logger.log("[ERROR] 강제 서버 동기화 실패: Drive 서비스 초기화 실패", folder.accountEmail)
            return ForcePullExecution(
                downloadedFiles = 0,
                failedFiles = 0,
                ensuredFolders = 0,
                removedLocalEntries = 0,
                reportPath = null,
                summary = "Drive 서비스 초기화에 실패했습니다."
            )
        }

        _isSyncing.value = true
        beginOperation(SyncOperationType.FORCE_PULL)
        _lastSyncResult.value = null

        try {
        logger.log("강제 서버 동기화 시작: local=${folder.localPath}, driveFolderId=${folder.driveFolderId}", folder.accountEmail)
        val rootDir = File(folder.localPath)
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }

        publishSyncProgress(
            currentFile = folder.driveFolderName,
            currentIndex = 0,
            totalFiles = 1,
            isUploading = false,
            statusMessage = "강제 동기화 준비 중..."
        )

        val driveFiles = mutableListOf<DriveFileNode>()
        val driveFolders = mutableListOf<DriveFolderNode>()
        collectDriveNodesRecursive(
            driveFolderId = folder.driveFolderId,
            prefix = "",
            files = driveFiles,
            folders = driveFolders
        )
        logger.log(
            "강제 서버 동기화 스캔 완료: driveFiles=${driveFiles.size}, driveFolders=${driveFolders.size}",
            folder.accountEmail
        )

        var ensuredFolders = 0
        var downloadedFiles = 0
        var failedFiles = 0
        var skippedNonDownloadableFiles = 0
        var removedLocalEntries = 0
        val failureReasonCounts = linkedMapOf<String, Int>()
        val failedFileSamples = mutableListOf<String>()

        val serverFilePaths = driveFiles.map { it.relativePath }.toSet()
        val serverFolderPaths = driveFolders.map { it.relativePath }.toSet()
        val totalSteps = (driveFiles.size + driveFolders.size + 3).coerceAtLeast(1)
        var currentStep = 0

        // "로컬 무시 + 서버 기준 강제 동기화"를 위해 서버에 없는 로컬 항목 제거.
        publishSyncProgress(
            currentFile = "로컬 정리",
            currentIndex = (++currentStep),
            totalFiles = totalSteps,
            isUploading = false,
            statusMessage = "서버에 없는 로컬 항목 정리 중..."
        )
        rootDir.walkBottomUp().forEach { localPath ->
            if (localPath.absolutePath == rootDir.absolutePath) return@forEach
            val relPath = localPath.relativeTo(rootDir).invariantSeparatorsPath
            if (relPath.split('/').contains(BACKUP_DIR_NAME)) return@forEach

            if (localPath.isFile && relPath !in serverFilePaths) {
                if (archiveForDeferredDeletion(folder, localPath, "force_pull_not_on_server")) {
                    removedLocalEntries++
                }
                return@forEach
            }

            if (localPath.isDirectory && relPath !in serverFolderPaths) {
                if (localPath.listFiles().isNullOrEmpty()) {
                    if (localPath.delete()) {
                        removedLocalEntries++
                    }
                } else if (archiveForDeferredDeletion(folder, localPath, "force_pull_not_on_server")) {
                    removedLocalEntries++
                }
            }
        }
        logger.log("강제 서버 동기화 로컬 정리 완료: removedLocalEntries=$removedLocalEntries", folder.accountEmail)

        // Rebuild tracked items to match the server pull result.
        syncItemDao.deleteItemsByFolder(folder.id)

        val sortedFolders = driveFolders.sortedBy { it.relativePath.split('/').size }
        for (folderNode in sortedFolders) {
            publishSyncProgress(
                currentFile = folderNode.relativePath,
                currentIndex = (++currentStep),
                totalFiles = totalSteps,
                isUploading = false,
                statusMessage = "폴더 구조 동기화 중..."
            )
            val localDir = File(rootDir, folderNode.relativePath)
            if (localDir.exists() && localDir.isFile) {
                archiveForDeferredDeletion(folder, localDir, "force_pull_type_conflict_file_to_folder")
            }
            if (!localDir.exists() && localDir.mkdirs()) {
                ensuredFolders++
            }
            trackSyncItem(
                folder = folder,
                localFile = localDir,
                driveFileId = folderNode.item.id,
                driveModifiedTime = folderNode.item.modifiedTime,
                driveSize = 0L,
                status = SyncStatus.SYNCED,
                md5Checksum = null
            )
        }
        logger.log("강제 서버 동기화 폴더 보장 완료: ensuredFolders=$ensuredFolders", folder.accountEmail)

        for (fileNode in driveFiles.sortedBy { it.relativePath }) {
            publishSyncProgress(
                currentFile = fileNode.relativePath,
                currentIndex = (++currentStep),
                totalFiles = totalSteps,
                isUploading = false,
                statusMessage = "파일 다운로드 중..."
            )
            val localFile = File(rootDir, fileNode.relativePath)
            if (localFile.exists() && localFile.isDirectory) {
                archiveForDeferredDeletion(folder, localFile, "force_pull_type_conflict_folder_to_file")
            }
            localFile.parentFile?.mkdirs()

            val downloadResult = driveHelper.downloadFileDetailed(fileNode.item.id, localFile.absolutePath)
            if (downloadResult.success) {
                downloadedFiles++
                trackSyncItem(
                    folder = folder,
                    localFile = localFile,
                    driveFileId = fileNode.item.id,
                    driveModifiedTime = fileNode.item.modifiedTime,
                    driveSize = fileNode.item.size,
                    status = SyncStatus.SYNCED,
                    md5Checksum = fileNode.item.md5Checksum
                )
            } else {
                if (downloadResult.skipped) {
                    skippedNonDownloadableFiles++
                    val reasonKey = downloadResult.reason ?: "skipped"
                    failureReasonCounts[reasonKey] = (failureReasonCounts[reasonKey] ?: 0) + 1
                    if (failedFileSamples.size < 10) {
                        failedFileSamples += "${fileNode.relativePath} => ${reasonKey} (mime=${downloadResult.mimeType ?: "unknown"})"
                    }
                    logger.log(
                        "[WARNING] 강제 서버 동기화 비다운로드 항목 스킵: ${fileNode.relativePath} (reason=${downloadResult.reason}, mime=${downloadResult.mimeType})",
                        folder.accountEmail
                    )
                } else {
                    failedFiles++
                    val reasonKey = downloadResult.reason ?: "download_failed"
                    failureReasonCounts[reasonKey] = (failureReasonCounts[reasonKey] ?: 0) + 1
                    if (failedFileSamples.size < 10) {
                        failedFileSamples += "${fileNode.relativePath} => ${reasonKey} (mime=${downloadResult.mimeType ?: "unknown"})"
                    }
                    logger.log(
                        "[WARNING] 강제 서버 동기화 다운로드 실패: ${fileNode.relativePath} (reason=${downloadResult.reason}, mime=${downloadResult.mimeType})",
                        folder.accountEmail
                    )
                }
            }
        }
        logger.log(
            "강제 서버 동기화 다운로드 단계 완료: downloadedFiles=$downloadedFiles, failedFiles=$failedFiles, skippedNonDownloadable=$skippedNonDownloadableFiles",
            folder.accountEmail
        )

        dirtyLocalDao.deleteDirtyItemsByFolder(folderId)
        syncFolderDao.updateLastSyncTime(folderId, System.currentTimeMillis())
        try {
            driveHelper.getStartPageToken()?.let { token ->
                syncFolderDao.updatePageToken(folderId, token)
            }
        } catch (_: Exception) {
        }

        publishSyncProgress(
            currentFile = "검증 리포트",
            currentIndex = (++currentStep),
            totalFiles = totalSteps,
            isUploading = false,
            statusMessage = "강제 동기화 검증 리포트 생성 중..."
        )
        val reasonSummary = if (failureReasonCounts.isEmpty()) {
            "none"
        } else {
            failureReasonCounts.entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString(", ") { "${it.key}:${it.value}" }
        }
        val diagnosticsNotes = buildList {
            add("forcePullDownloaded=$downloadedFiles")
            add("forcePullFailed=$failedFiles")
            add("forcePullSkippedNonDownloadable=$skippedNonDownloadableFiles")
            add("forcePullRemovedLocalEntries=$removedLocalEntries")
            add("forcePullFailureReasons=$reasonSummary")
            if (failedFileSamples.isNotEmpty()) {
                add("forcePullFailedSamples=${failedFileSamples.joinToString(" | ")}")
            }
        }
        val verificationReportPath = try {
            generateAndSaveVerificationReport(
                folder = folder,
                diagnostics = SyncRunDiagnostics(
                    isInitialSync = false,
                    dirtyLocalItems = 0,
                    trackedItems = 0,
                    changesProcessedSuccessfully = false,
                    fullScanExecuted = true,
                    fullScanReason = "force_pull_server_authoritative",
                    folderSyncDirection = folder.syncDirection,
                    defaultSyncDirection = syncPreferences.defaultSyncDirection,
                    autoUploadEnabled = syncPreferences.autoUploadEnabled,
                    lastStartPageToken = folder.lastStartPageToken,
                    changesDebugStats = null,
                    extraNotes = diagnosticsNotes
                )
            ).reportPath
        } catch (_: Exception) {
            null
        }

        val summary = "서버 기준 강제 동기화 완료: 다운로드=$downloadedFiles, 실패=$failedFiles, 비다운로드스킵=$skippedNonDownloadableFiles, 폴더보장=$ensuredFolders, 로컬정리=$removedLocalEntries"
        logger.log(summary, folder.accountEmail)

        return ForcePullExecution(
            downloadedFiles = downloadedFiles,
            failedFiles = failedFiles,
            ensuredFolders = ensuredFolders,
            removedLocalEntries = removedLocalEntries,
            reportPath = verificationReportPath,
            summary = summary
        )
        } finally {
            logger.log("강제 서버 동기화 종료", folder.accountEmail)
            _isSyncing.value = false
            _syncProgress.value = null
            endOperation(SyncOperationType.FORCE_PULL)
        }
    }

    suspend fun previewForcePullFromServer(folderId: String, sampleLimit: Int = 10): ForcePullPreview {
        val folder = syncFolderDao.getSyncFolderById(folderId)
            ?: return ForcePullPreview(
                syncLocalRoot = "",
                driveFolderId = "",
                totalRemovalCandidates = 0,
                sampleRemovalCandidates = emptyList(),
                hasMoreCandidates = false,
                summary = "동기화 폴더를 찾을 수 없습니다."
            )

        if (!driveHelper.initializeDriveService(folder.accountEmail)) {
            return ForcePullPreview(
                syncLocalRoot = File(folder.localPath).absolutePath,
                driveFolderId = folder.driveFolderId,
                totalRemovalCandidates = 0,
                sampleRemovalCandidates = emptyList(),
                hasMoreCandidates = false,
                summary = "Drive 서비스 초기화에 실패했습니다."
            )
        }

        val rootDir = File(folder.localPath)
        if (!rootDir.exists()) {
            return ForcePullPreview(
                syncLocalRoot = rootDir.absolutePath,
                driveFolderId = folder.driveFolderId,
                totalRemovalCandidates = 0,
                sampleRemovalCandidates = emptyList(),
                hasMoreCandidates = false,
                summary = "로컬 동기화 경로가 존재하지 않습니다. 삭제 예정 항목이 없습니다."
            )
        }

        val driveFiles = mutableListOf<DriveFileNode>()
        val driveFolders = mutableListOf<DriveFolderNode>()
        collectDriveNodesRecursive(
            driveFolderId = folder.driveFolderId,
            prefix = "",
            files = driveFiles,
            folders = driveFolders
        )

        val serverFilePaths = driveFiles.map { it.relativePath }.toSet()
        val serverFolderPaths = driveFolders.map { it.relativePath }.toSet()
        val deletionCandidates = mutableListOf<String>()
        val virtuallyDeletedAbsPaths = mutableSetOf<String>()

        rootDir.walkBottomUp().forEach { localPath ->
            if (localPath.absolutePath == rootDir.absolutePath) return@forEach

            val relPath = localPath.relativeTo(rootDir).invariantSeparatorsPath
            if (relPath.split('/').contains(BACKUP_DIR_NAME)) return@forEach

            if (localPath.isFile && relPath !in serverFilePaths) {
                deletionCandidates += "[F] $relPath"
                virtuallyDeletedAbsPaths += localPath.absolutePath
                return@forEach
            }

            if (localPath.isDirectory && relPath !in serverFolderPaths) {
                val children = localPath.listFiles().orEmpty()
                val emptyAfterPlannedDeletion = children.all { child ->
                    child.absolutePath in virtuallyDeletedAbsPaths
                }
                if (emptyAfterPlannedDeletion) {
                    deletionCandidates += "[D] $relPath"
                    virtuallyDeletedAbsPaths += localPath.absolutePath
                }
            }
        }

        val normalizedLimit = sampleLimit.coerceAtLeast(1)
        val sample = deletionCandidates.take(normalizedLimit)
        val hasMore = deletionCandidates.size > sample.size
        val summary = "삭제 예정 ${deletionCandidates.size}건 (샘플 ${sample.size}건 표시${if (hasMore) ", 외 ${deletionCandidates.size - sample.size}건" else ""})"

        return ForcePullPreview(
            syncLocalRoot = rootDir.absolutePath,
            driveFolderId = folder.driveFolderId,
            totalRemovalCandidates = deletionCandidates.size,
            sampleRemovalCandidates = sample,
            hasMoreCandidates = hasMore,
            summary = summary
        )
    }

    private suspend fun collectDriveNodesRecursive(
        driveFolderId: String,
        prefix: String,
        files: MutableList<DriveFileNode>,
        folders: MutableList<DriveFolderNode>
    ) {
        val items = driveHelper.listAllFiles(driveFolderId)
        for (item in items) {
            val relativePath = if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
            if (isExcludedRelativePath(relativePath)) continue
            if (item.isFolder) {
                folders += DriveFolderNode(relativePath = relativePath, item = item)
                collectDriveNodesRecursive(
                    driveFolderId = item.id,
                    prefix = relativePath,
                    files = files,
                    folders = folders
                )
            } else {
                files += DriveFileNode(relativePath = relativePath, item = item)
            }
        }
    }

    suspend fun runVerificationOnly(folderId: String): VerificationExecution {
        val folder = syncFolderDao.getSyncFolderById(folderId)
            ?: return VerificationExecution(
                result = "FAIL",
                reportPath = "",
                summary = "sync folder not found"
            )

        if (!driveHelper.initializeDriveService(folder.accountEmail)) {
            return VerificationExecution(
                result = "FAIL",
                reportPath = "",
                summary = "Drive service initialization failed"
            )
        }

        return try {
            val verification = generateAndSaveVerificationReport(folder)
            VerificationExecution(
                result = verification.result,
                reportPath = verification.reportPath,
                summary = verification.summary
            )
        } catch (e: Exception) {
            if (isVerificationSkippableNetworkError(e)) {
                val skipped = saveSkippedVerificationReport(
                    folder = folder,
                    reason = e.message ?: e.javaClass.simpleName
                )
                VerificationExecution(
                    result = "SKIPPED",
                    reportPath = skipped.reportPath,
                    summary = skipped.summary
                )
            } else {
                throw e
            }
        }
    }

    /**
     * Phase 2: Incremental sync using Changes API (Internal implementation)
     */
    private suspend fun syncChangesInternal(folder: SyncFolderEntity): RecursiveSyncResult {
        throwIfFolderCancelled(folder.id, folder.accountEmail)
        throwIfLocalFolderMissing(folder)
        val pageToken = folder.lastStartPageToken ?: return RecursiveSyncResult(0, 0, 0, 1, emptyList())
        logger.log("차분 동기화 시작 (Token: $pageToken)", folder.accountEmail)
        
        var uploaded = 0; var downloaded = 0; var skipped = 0; var errors = 0
        val conflicts = mutableListOf<SyncConflict>()
        val pendingUploads = mutableListOf<PendingUpload>()
        var processedChanges = 0
        var estimatedTotal = 1
        var removedChanges = 0
        var outOfScopeChanges = 0
        var metadataChanges = 0
        
        // Build set of all known folder IDs to check for descendants
        val knownFolderIds = mutableSetOf(folder.driveFolderId)
        
        // Add all folders already tracked in the DB for this sync folder
        // This ensures subfolders created in previous sessions are recognized
        try {
            val dbFolders = syncItemDao.getSyncItemsByFolder(folder.id).first()
                .filter { it.mimeType == DriveServiceHelper.MIME_TYPE_FOLDER && it.driveFileId != null }
                .map { it.driveFileId!! }
            knownFolderIds.addAll(dbFolders)
            if (dbFolders.isNotEmpty()) {
                logger.log("차분 동기화: DB에서 ${dbFolders.size}개의 하위 폴더 로드됨", folder.accountEmail)
            }
        } catch (e: Exception) {
            logger.log("차분 동기화 폴더 목록 로드 실패: ${e.message}", folder.accountEmail)
        }
        
        try {
            var currentPageToken = pageToken
            var nextStartPageToken: String? = null
            
            do {
                val result = driveHelper.getChanges(currentPageToken)
                estimatedTotal = (estimatedTotal + result.changes.size).coerceAtLeast(1)
                for (change in result.changes) {
                    throwIfFolderCancelled(folder.id, folder.accountEmail)
                    throwIfLocalFolderMissing(folder)
                    processedChanges++
                    val progressName = change.file?.name ?: change.fileId
                    publishSyncProgress(
                        currentFile = progressName,
                        currentIndex = processedChanges,
                        totalFiles = estimatedTotal,
                        isUploading = false,
                        statusMessage = "차분 동기화 처리 중... ($processedChanges/$estimatedTotal)"
                    )
                    try {
                        val driveFile = change.file
                        val driveFileId = change.fileId
                        
                        if (change.removed) {
                            removedChanges++
                            val existingItem = syncItemDao.getSyncItemByDriveId(driveFileId)
                            if (existingItem != null) {
                                val localFile = File(existingItem.localPath)
                                logger.log("서버 삭제 감지 (API): ${existingItem.localPath}", folder.accountEmail)
                                
                                val deleteResult = if (localFile.exists()) {
                                    archiveForDeferredDeletion(folder, localFile, "changes_api_removed")
                                } else true 

                                if (deleteResult || !localFile.exists()) {
                                    syncItemDao.deleteSyncItem(existingItem)
                                    skipped++
                                } else {
                                    logger.log("[WARNING] 로컬 파일 삭제 실패 (API): ${existingItem.localPath}", folder.accountEmail)
                                }
                            }
                        } else if (driveFile != null) {
                            if (driveFile.isFolder) {
                                // Potentially add to known folders if it's within our sync hierarchy
                                if (driveFile.parentIds.any { it in knownFolderIds }) {
                                    knownFolderIds.add(driveFile.id)
                                }
                            }

                            // Calculate parent path for this item (for rename/move detection or creation)
                            val parentId = driveFile.parentIds.firstOrNull { it in knownFolderIds }
                            val parentPath = if (parentId != null) {
                                if (parentId == folder.driveFolderId) {
                                    folder.localPath
                                } else {
                                    syncItemDao.getSyncItemByDriveId(parentId)?.localPath
                                }
                            } else null

                            val existingItem = syncItemDao.getSyncItemByDriveId(driveFileId)
                            if (existingItem != null) {
                                val currentLocalFile = File(existingItem.localPath)
                                val targetLocalFile = if (parentPath != null) File(parentPath, driveFile.name) else currentLocalFile
                                
                                // Handle Rename or Move locally
                                if (currentLocalFile.absolutePath != targetLocalFile.absolutePath) {
                                    logger.log("이동/이름변경 감지 (API): ${existingItem.localPath} -> ${targetLocalFile.absolutePath}", folder.accountEmail)
                                    if (currentLocalFile.exists()) {
                                        if (currentLocalFile.renameTo(targetLocalFile)) {
                                            val updatedItem = existingItem.copy(
                                                localPath = targetLocalFile.absolutePath,
                                                fileName = driveFile.name
                                            )
                                            syncItemDao.updateSyncItem(updatedItem)
                                        } else {
                                            logger.log("[ERROR] 로컬 이동 실패: ${existingItem.localPath}", folder.accountEmail)
                                        }
                                    }
                                }
                                
                                val localFile = if (targetLocalFile.exists()) targetLocalFile else currentLocalFile
                                
                                // Skip local files containing "_local"
                                if (localFile.name.contains("_local")) {
                                    skipped++; continue
                                }
                                if (isExcludedPath(folder, localFile)) {
                                    skipped++; continue
                                }
                                
                                val pairResult = if (driveFile.isFolder) {
                                    metadataChanges++
                                    // It's a folder, ensure it exists and update metadata
                                    if (!localFile.exists()) localFile.mkdirs()
                                    updateSyncItem(
                                        existingItem = existingItem,
                                        localFile = localFile,
                                        driveFileId = driveFile.id,
                                        driveModifiedTime = driveFile.modifiedTime,
                                        driveSize = 0L,
                                        status = SyncStatus.SYNCED
                                    )
                                    knownFolderIds.add(driveFile.id)
                                    RecursiveSyncResult(0, 0, 1, 0, emptyList())
                                } else {
                                    processFilePair(folder, localFile, driveFile, existingItem)
                                }
                                uploaded += pairResult.uploaded; downloaded += pairResult.downloaded
                                skipped += pairResult.skipped; errors += pairResult.errors
                                conflicts.addAll(pairResult.conflicts)
                                pendingUploads.addAll(pairResult.pendingUploads)
                            } else if (parentPath != null) {
                                // Match by parent: it's a new item in a known folder
                                val localFile = File(parentPath, driveFile.name)
                                
                                // Skip local files containing "_local"
                                if (localFile.name.contains("_local")) {
                                    skipped++; continue
                                }
                                
                                val pairResult = if (driveFile.isFolder) {
                                    metadataChanges++
                                    // It's a new folder, create it
                                    if (!localFile.exists()) localFile.mkdirs()
                                    trackSyncItem(folder, localFile, driveFile.id, driveFile.modifiedTime, 0L, SyncStatus.SYNCED, null)
                                    knownFolderIds.add(driveFile.id)
                                    RecursiveSyncResult(0, 0, 1, 0, emptyList())
                                } else {
                                    processFilePair(folder, localFile, driveFile, null)
                                }
                                uploaded += pairResult.uploaded; downloaded += pairResult.downloaded
                                skipped += pairResult.skipped; errors += pairResult.errors
                                conflicts.addAll(pairResult.conflicts)
                                pendingUploads.addAll(pairResult.pendingUploads)
                            } else {
                                outOfScopeChanges++
                            }
                        }
                    } catch (e: Exception) {
                        if (isFatalNetworkError(e)) throw e
                        errors++
                        val exceptionMsg = e.message ?: e.javaClass.simpleName
                        logger.log("[ERROR] 차분 동기화 개별 항목 처리 실패: $exceptionMsg", folder.accountEmail)
                    }
                }
                currentPageToken = result.nextPageToken ?: ""
                nextStartPageToken = result.newStartPageToken
                
                // Save progress after each batch to enable resumption if interrupted
                val tokenToSave = if (currentPageToken.isNotEmpty()) currentPageToken else nextStartPageToken
                if (tokenToSave != null) {
                    syncFolderDao.updatePageToken(folder.id, tokenToSave)
                }
            } while (currentPageToken.isNotEmpty())
            
            lastChangesDebugStats = ChangesDebugStats(
                processed = processedChanges,
                removed = removedChanges,
                folderMetadataUpdated = metadataChanges,
                outOfScope = outOfScopeChanges,
                uploaded = uploaded,
                downloaded = downloaded,
                skipped = skipped,
                errors = errors,
                conflicts = conflicts.size
            )
            logger.log(
                "차분 동기화 통계: processed=$processedChanges, removed=$removedChanges, folderMeta=$metadataChanges, outOfScope=$outOfScopeChanges, uploaded=$uploaded, downloaded=$downloaded, skipped=$skipped, errors=$errors, conflicts=${conflicts.size}",
                folder.accountEmail
            )
            return RecursiveSyncResult(uploaded, downloaded, skipped, errors, conflicts, pendingUploads)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCooldownNetworkError(e)) {
                markNetworkCooldown(folder.id, folder.accountEmail, e)
            }
            logger.log("[ERROR] 차분 동기화 실패: ${e.message}", folder.accountEmail)
            lastChangesDebugStats = ChangesDebugStats(
                processed = processedChanges,
                removed = removedChanges,
                folderMetadataUpdated = metadataChanges,
                outOfScope = outOfScopeChanges,
                uploaded = uploaded,
                downloaded = downloaded,
                skipped = skipped,
                errors = errors + 1,
                conflicts = conflicts.size
            )
            return RecursiveSyncResult(uploaded, downloaded, skipped, errors + 1, conflicts, pendingUploads)
        }
    }
}
