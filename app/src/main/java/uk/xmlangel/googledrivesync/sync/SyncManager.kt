package uk.xmlangel.googledrivesync.sync

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.xmlangel.googledrivesync.data.drive.DriveServiceHelper
import uk.xmlangel.googledrivesync.data.local.*
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.data.model.SyncStatus
import java.io.File
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
    private val syncPreferences: SyncPreferences = SyncPreferences(context),
    private val logger: uk.xmlangel.googledrivesync.util.SyncLogger = uk.xmlangel.googledrivesync.util.SyncLogger(context)
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()
    
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncResult = MutableStateFlow<SyncResult?>(null)
    val lastSyncResult: StateFlow<SyncResult?> = _lastSyncResult.asStateFlow()
    
    private val _pendingConflicts = MutableStateFlow<List<SyncConflict>>(emptyList())
    val pendingConflicts: StateFlow<List<SyncConflict>> = _pendingConflicts.asStateFlow()

    private val _pendingUploads = MutableStateFlow<List<PendingUpload>>(emptyList())
    val pendingUploads: StateFlow<List<PendingUpload>> = _pendingUploads.asStateFlow()

    private var currentFileIndex = AtomicInteger(0)
    private var totalSyncFiles = 0
    private var currentStatusMessage: String? = null

    companion object {
        @Volatile
        private var INSTANCE: SyncManager? = null

        fun getInstance(context: Context): SyncManager {
            return INSTANCE ?: synchronized(this) {
                val instance = SyncManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
    
    /**
     * Initialize Drive service
     */
    fun initialize(): Boolean {
        return driveHelper.initializeDriveService()
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
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL
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
        return folder
    }
    
    /**
     * Perform sync for a specific folder
     */
    fun syncAllFolders() {
        managerScope.launch {
            val folders = syncFolderDao.getEnabledSyncFoldersOnce()
            folders.forEach { folder ->
                syncFolder(folder.id)
            }
        }
    }

    fun dismissLastResult() {
        _lastSyncResult.value = null
    }

    fun dismissPendingUploads() {
        _pendingUploads.value = emptyList()
    }

    /**
     * Perform sync for a specific folder
     */
    suspend fun syncFolder(folderId: String): SyncResult {
        if (_isSyncing.value) {
            logger.log("[ERROR] 동기화 실패: 이미 진행 중입니다")
            return SyncResult.Error("동기화가 이미 진행 중입니다")
        }
        
        _isSyncing.value = true
        _lastSyncResult.value = null
        
        try {
            val folder = syncFolderDao.getSyncFolderById(folderId)
                ?: run {
                    val error = SyncResult.Error("동기화 폴더를 찾을 수 없습니다")
                    _lastSyncResult.value = error
                    logger.log("[ERROR] 동기화 실패: folderId=$folderId 찾을 수 없음")
                    return error
                }
            
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
            
            var uploaded = 0
            var downloaded = 0
            var skipped = 0
            var errors = 0
            val conflicts = mutableListOf<SyncConflict>()
            
            // Get local files (now including folders)
            val localItems = scanLocalFolder(folder.localPath)
            
            // Get all drive files using the new pagination helper
            val driveItems = driveHelper.listAllFiles(folder.driveFolderId)
            
            logger.log("스캔 완료: 로컬=${localItems.size}개, 드라이브=${driveItems.size}개", folder.accountEmail)
            
            // Calculate total files for progress tracking
            totalSyncFiles = countAllFiles(folder.localPath, folder.driveFolderId)
            currentFileIndex.set(0)
            
            // Use a unified recursive sync method
            val result = syncDirectoryRecursive(
                folder = folder,
                localPath = folder.localPath,
                driveFolderId = folder.driveFolderId,
                localItems = localItems,
                driveItems = driveItems
            )
            
            uploaded = result.uploaded
            downloaded = result.downloaded
            skipped = result.skipped
            errors = result.errors
            conflicts.addAll(result.conflicts)
            
            // Add stashed uploads to pending list
            if (result.pendingUploads.isNotEmpty()) {
                _pendingUploads.value = _pendingUploads.value + result.pendingUploads
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
            
            logger.log("동기화 완료: 업로드=$uploaded, 다운로드=$downloaded, 스킵=$skipped, 에러=$errors, 충돌=${conflicts.size}", folder.accountEmail)
            
            val syncResult = if (conflicts.isNotEmpty() || _pendingUploads.value.isNotEmpty()) {
                if (conflicts.isNotEmpty()) _pendingConflicts.value = conflicts
                SyncResult.Conflict(conflicts) // We might need a new result type or just use Conflict if we want to show a dialog
            } else {
                SyncResult.Success(uploaded, downloaded, skipped)
            }
            _lastSyncResult.value = syncResult
            return syncResult
            
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMessage = when (e) {
                is java.net.UnknownHostException -> "네트워크 연결이 없거나 Google 서비스에 접속할 수 없습니다 (DNS 오류)"
                is java.net.SocketTimeoutException -> "네트워크 응답 시간이 초과되었습니다"
                is java.io.IOException -> "네트워크 또는 파일 I/O 오류: ${e.message}"
                else -> e.message ?: "알 수 없는 오류"
            }
            val errorResult = SyncResult.Error(errorMessage, e)
            _lastSyncResult.value = errorResult
            logger.log("[ERROR] 동기화 중 치명적 오류 발생: $errorMessage (${e.javaClass.simpleName})")
            return errorResult
        } finally {
            _isSyncing.value = false
            _syncProgress.value = null
        }
    }
    
    /**
     * Resolve a pending upload with user's choice
     */
    suspend fun resolvePendingUpload(pendingUpload: PendingUpload, resolution: PendingUploadResolution): Boolean {
        return try {
            when (resolution) {
                PendingUploadResolution.UPLOAD -> {
                    val folder = syncFolderDao.getSyncFolderById(pendingUpload.folderId) ?: return false
                    
                    val result = if (pendingUpload.isNewFile) {
                        driveHelper.uploadFile(
                            pendingUpload.localFile.absolutePath,
                            pendingUpload.localFile.name,
                            pendingUpload.driveFolderId
                        )
                    } else {
                        driveHelper.updateFile(
                            pendingUpload.driveFileId!!,
                            pendingUpload.localFile.absolutePath
                        )
                    }

                    if (result != null) {
                        if (pendingUpload.isNewFile) {
                            trackSyncItem(
                                folder,
                                pendingUpload.localFile,
                                result.id,
                                result.modifiedTime,
                                result.size,
                                SyncStatus.SYNCED,
                                result.md5Checksum
                            )
                        } else {
                            val existingItem = syncItemDao.getSyncItemByDriveId(pendingUpload.driveFileId!!)
                            if (existingItem != null) {
                                updateSyncItem(
                                    existingItem,
                                    pendingUpload.localFile,
                                    result.id,
                                    result.modifiedTime,
                                    result.size,
                                    SyncStatus.SYNCED,
                                    result.md5Checksum
                                )
                            }
                        }
                        // Remove from pending list
                        _pendingUploads.value = _pendingUploads.value.filter { it != pendingUpload }
                        true
                    } else {
                        false
                    }
                }
                PendingUploadResolution.SKIP -> {
                    _pendingUploads.value = _pendingUploads.value.filter { it != pendingUpload }
                    true
                }
            }
        } catch (e: Exception) {
            logger.log("[ERROR] 업로드 승인 처리 실패: ${pendingUpload.localFile.name}", pendingUpload.accountEmail)
            false
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
                    driveHelper.updateFile(
                        fileId = conflict.syncItem.driveFileId!!,
                        localPath = conflict.syncItem.localPath
                    )
                    syncItemDao.updateItemStatus(conflict.syncItem.id, SyncStatus.SYNCED)
                    true
                }
                
                ConflictResolution.USE_DRIVE -> {
                    // Download from Drive
                    driveHelper.downloadFile(
                        fileId = conflict.syncItem.driveFileId!!,
                        destinationPath = conflict.syncItem.localPath
                    )
                    syncItemDao.updateItemStatus(conflict.syncItem.id, SyncStatus.SYNCED)
                    true
                }
                
                ConflictResolution.KEEP_BOTH -> {
                    // Rename local file and download Drive version
                    val localFile = File(conflict.syncItem.localPath)
                    val newName = "${localFile.nameWithoutExtension}_local.${localFile.extension}"
                    val renamedPath = "${localFile.parent}/$newName"
                    localFile.renameTo(File(renamedPath))
                    
                    driveHelper.downloadFile(
                        fileId = conflict.syncItem.driveFileId!!,
                        destinationPath = conflict.syncItem.localPath
                    )
                    syncItemDao.updateItemStatus(conflict.syncItem.id, SyncStatus.SYNCED)
                    true
                }
                
                ConflictResolution.SKIP -> {
                    syncItemDao.updateItemStatus(conflict.syncItem.id, SyncStatus.SYNCED)
                    true
                }
            }
        } catch (e: Exception) {
            syncItemDao.updateItemError(
                conflict.syncItem.id,
                SyncStatus.ERROR,
                e.message ?: "Unknown error"
            )
            logger.log("[ERROR] 충돌 해결 실패: ${conflict.localFileName}", conflict.syncItem.accountEmail)
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
            handledDriveIds.add(driveFile.id)
            
            val currentIndex = currentFileIndex.incrementAndGet()
            _syncProgress.value = SyncProgress(
                currentFile = driveFile.name,
                currentIndex = currentIndex,
                totalFiles = totalSyncFiles,
                bytesTransferred = 0,
                totalBytes = driveFile.size,
                isUploading = false,
                statusMessage = currentStatusMessage
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
                        
                        val statusMsg = "이름/위치 변경 감지 (Drive): ${oldLocalFile.name} -> $sanitizedNewName"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                        
                        if (oldLocalFile.renameTo(newLocalFile)) {
                            handledLocalPaths.add(existingItem.localPath)
                            localFile = newLocalFile
                            existingItem = existingItem.copy(
                                localPath = newLocalFile.absolutePath,
                                fileName = sanitizedNewName
                            )
                            syncItemDao.updateSyncItem(existingItem)
                        } else {
                            logger.log("[ERROR] 로컬 이동 실패: ${oldLocalFile.name}", folder.accountEmail)
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
                        val statusMsg = "기존 로컬 파일 연결: ${driveFile.name}"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                    }
                }
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
                
                if (!dir.exists()) {
                    if (syncPreferences.defaultSyncDirection != SyncDirection.UPLOAD_ONLY) {
                        val statusMsg = "폴더 생성: ${driveFile.name}"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                        dir.mkdirs()
                    } else {
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
                    val syncResult = processFilePair(folder, localFile, driveFile, existingItem)
                    uploaded += syncResult.uploaded
                    downloaded += syncResult.downloaded
                    skipped += syncResult.skipped
                    errors += syncResult.errors
                    conflicts.addAll(syncResult.conflicts)
                    pendingUploads.addAll(syncResult.pendingUploads)
                } else if (syncPreferences.defaultSyncDirection != SyncDirection.UPLOAD_ONLY) {
                    val sanitizedName = uk.xmlangel.googledrivesync.util.FileUtils.sanitizeFileName(driveFile.name)
                    val statusMsg = "새 파일 다운로드: $sanitizedName"
                    logger.log(statusMsg, folder.accountEmail)
                    currentStatusMessage = statusMsg
                    val destPath = File(localPath, sanitizedName).absolutePath
                    if (driveHelper.downloadFile(driveFile.id, destPath)) {
                        downloaded++
                        trackSyncItem(folder, File(destPath), driveFile.id, driveFile.modifiedTime, driveFile.size, SyncStatus.SYNCED, driveFile.md5Checksum)
                    } else {
                        errors++; logger.log("[ERROR] 다운로드 실패: $sanitizedName", folder.accountEmail)
                    }
                } else {
                    skipped++
                }
            }
        }

        // 2. Process remaining Local items (New Uploads or Deletions)
        for (localFile in localItems) {
            if (handledLocalPaths.contains(localFile.absolutePath)) continue
            if (!localFile.exists()) continue // Skip if renamed or deleted already
            
            val currentIndex = currentFileIndex.incrementAndGet()
            _syncProgress.value = SyncProgress(
                currentFile = localFile.name,
                currentIndex = currentIndex,
                totalFiles = totalSyncFiles,
                bytesTransferred = 0,
                totalBytes = localFile.length(),
                isUploading = true,
                statusMessage = currentStatusMessage
            )

            val existingItem = syncItemDao.getSyncItemByLocalPath(localFile.absolutePath)
            
            if (existingItem?.driveFileId != null && !handledDriveIds.contains(existingItem.driveFileId)) {
                // Item exists in DB but not on Drive in THIS folder.
                // It might have been moved or deleted.
                try {
                    val driveFileMeta = driveHelper.getFileMetadata(existingItem.driveFileId!!)
                    if (driveFileMeta.parentIds.contains(driveFolderId)) {
                        // Still in this folder but trashed? Or just missing from list?
                        logger.log("드라이브에서 삭제됨 감지: ${localFile.name}", folder.accountEmail)
                        if (localFile.delete()) {
                            syncItemDao.deleteSyncItem(existingItem)
                        }
                    } else {
                        // Moved to another folder!
                        logger.log("이동 감지 (Drive): ${localFile.name} -> 다른 폴더로 이동됨", folder.accountEmail)
                    }
                } catch (e: Exception) {
                    if (isFatalNetworkError(e)) throw e
                    // Not on Drive at all or error
                    logger.log("드라이브에서 삭제됨 감지: ${localFile.name}", folder.accountEmail)
                    if (localFile.delete()) {
                        syncItemDao.deleteSyncItem(existingItem)
                    }
                }
            } else if (localFile.isDirectory) {
                // New Local Folder
                if (syncPreferences.defaultSyncDirection != SyncDirection.DOWNLOAD_ONLY) {
                    val statusMsg = "새 폴더 업로드: ${localFile.name}"
                    logger.log(statusMsg, folder.accountEmail)
                    currentStatusMessage = statusMsg
                    try {
                        val created = driveHelper.createFolder(localFile.name, driveFolderId)
                        if (created != null) {
                            val subLocalItems = scanLocalFolder(localFile.absolutePath)
                            val subResult = syncDirectoryRecursive(folder, localFile.absolutePath, created.id, subLocalItems, emptyList())
                            uploaded += subResult.uploaded; downloaded += subResult.downloaded
                            skipped += subResult.skipped; errors += subResult.errors
                            conflicts.addAll(subResult.conflicts)
                            pendingUploads.addAll(subResult.pendingUploads)
                        } else {
                            errors++; logger.log("[ERROR] 폴더 업로드 실패: ${localFile.name}", folder.accountEmail)
                        }
                    } catch (e: Exception) {
                        if (isFatalNetworkError(e)) throw e
                        errors++; logger.log("[ERROR] 폴더 업로드 실패: ${localFile.name} (${e.message})", folder.accountEmail)
                    }
                } else skipped++
            } else {
                // New Local File
                if (syncPreferences.defaultSyncDirection != SyncDirection.DOWNLOAD_ONLY) {
                    val statusMsg = "업로드 대기: ${localFile.name}"
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
                } else skipped++
            }
        }
        return RecursiveSyncResult(uploaded, downloaded, skipped, errors, conflicts, pendingUploads)
    }

    private fun isFatalNetworkError(e: Exception): Boolean {
        return e is java.net.UnknownHostException || 
               e is java.net.SocketTimeoutException || 
               e is java.io.InterruptedIOException ||
               (e is java.io.IOException && e.message?.contains("Canceled") == true)
    }

    private suspend fun processFilePair(
        folder: SyncFolderEntity,
        localFile: File,
        driveFile: uk.xmlangel.googledrivesync.data.drive.DriveItem,
        existingItem: SyncItemEntity?
    ): RecursiveSyncResult {
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var errors = 0
        val conflicts = mutableListOf<SyncConflict>()

        val localModified = localFile.lastModified()
        val driveModified = driveFile.modifiedTime
        val localSize = localFile.length()
        val driveSize = driveFile.size
        
        // Use a 2-second threshold for modification time comparisons
        val localDrift = Math.abs(localModified - (existingItem?.localModifiedAt ?: 0L))
        val driveDrift = Math.abs(driveModified - (existingItem?.driveModifiedAt ?: 0L))
        
        val isLocalUpdated = (existingItem == null || (localDrift > 2000 && localModified > (existingItem.localModifiedAt)) || localSize != (existingItem.localSize))
        val isDriveUpdated = (existingItem == null || (driveDrift > 2000 && driveModified > (existingItem.driveModifiedAt)) || driveSize != (existingItem.driveSize))
        
        // v1.0.9: New Item Linking - If sizes match exactly, link without sync
        if (existingItem == null && localSize == driveSize) {
            val localMd5 = uk.xmlangel.googledrivesync.util.FileUtils.calculateMd5(localFile)
            if (localMd5 != null && localMd5 == driveFile.md5Checksum) {
                val statusMsg = "기존 파일 연결 (MD5 Match): ${localFile.name}"
                logger.log(statusMsg, folder.accountEmail)
                currentStatusMessage = statusMsg
                trackSyncItem(folder, localFile, driveFile.id, driveFile.modifiedTime, driveFile.size, SyncStatus.SYNCED, driveFile.md5Checksum)
                return RecursiveSyncResult(0, 0, 1, 0, emptyList())
            }
        }

        // v1.0.9: Existing Item Swallowing - If both sides match DB size, just update metadata
        if (existingItem != null && localSize == existingItem.localSize && driveSize == existingItem.driveSize) {
            if (isLocalUpdated || isDriveUpdated) {
                val statusMsg = "크기 일치 (내용 변화 없음): ${localFile.name}"
                logger.log("$statusMsg - 메타데이터만 업데이트", folder.accountEmail)
                currentStatusMessage = statusMsg
                updateSyncItem(existingItem, localFile, driveFile.id, driveFile.modifiedTime, driveFile.size, SyncStatus.SYNCED, driveFile.md5Checksum)
            }
            return RecursiveSyncResult(0, 0, 1, 0, emptyList())
        }
        
        if (isLocalUpdated || isDriveUpdated) {
            val dbLocalMod = existingItem?.localModifiedAt
            val dbDriveMod = existingItem?.driveModifiedAt
            val dbLocalSize = existingItem?.localSize
            val dbDriveSize = existingItem?.driveSize
            
            logger.log("변경 감지: ${localFile.name}\n" +
                "  Status: ${if (existingItem == null) "New Item (Not in DB)" else "Existing Item"}\n" +
                "  Local: $localModified (Size: $localSize) vs DB: $dbLocalMod (Size: $dbLocalSize)\n" +
                "  Drive: $driveModified (Size: $driveSize) vs DB: $dbDriveMod (Size: $dbDriveSize)", 
                folder.accountEmail)
        }
        
        try {
            when {
                isLocalUpdated && isDriveUpdated -> {
                    val conflict = SyncConflict(
                        existingItem ?: createSyncItem(folder, localFile, driveFile.id),
                        localFile.name, localModified, localFile.length(),
                        driveFile.name, driveModified, driveFile.size
                    )
                    val defaultResolution = syncPreferences.defaultConflictResolution
                    if (defaultResolution != null) {
                        if (resolveConflict(conflict, defaultResolution)) {
                            if (defaultResolution == ConflictResolution.USE_LOCAL) uploaded++ else downloaded++
                        } else {
                            errors++
                            logger.log("[ERROR] 충돌 해결 실패: ${localFile.name}", folder.accountEmail)
                        }
                    } else conflicts.add(conflict)
                }
                isLocalUpdated -> {
                    if (syncPreferences.defaultSyncDirection != SyncDirection.DOWNLOAD_ONLY) {
                        val statusMsg = "업로드 대기 (수정됨): ${localFile.name}"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                        
                        val pendingUpload = PendingUpload(
                            folderId = folder.id,
                            localFile = localFile,
                            driveFolderId = driveFile.id, 
                            isNewFile = false,
                            driveFileId = existingItem?.driveFileId ?: driveFile.id,
                            accountEmail = folder.accountEmail
                        )
                        return RecursiveSyncResult(0, 0, 1, 0, emptyList(), listOf(pendingUpload))
                    } else skipped++
                }
                isDriveUpdated -> {
                    if (syncPreferences.defaultSyncDirection != SyncDirection.UPLOAD_ONLY) {
                        val statusMsg = "파일 다운로드: ${localFile.name}"
                        logger.log(statusMsg, folder.accountEmail)
                        currentStatusMessage = statusMsg
                        if (driveHelper.downloadFile(driveFile.id, localFile.absolutePath)) {
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
                        } else {
                            errors++
                            logger.log("[ERROR] 파일 업데이트(다운로드) 실패: ${localFile.name}", folder.accountEmail)
                        }
                    } else skipped++
                }
                else -> skipped++
            }
        } catch (e: Exception) {
            if (isFatalNetworkError(e)) throw e
            errors++
            logger.log("[ERROR] 파일 처리 실패: ${localFile.name} (${e.message})", folder.accountEmail)
        }
        return RecursiveSyncResult(uploaded, downloaded, skipped, errors, conflicts, emptyList())
    }

    private data class RecursiveSyncResult(
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
    private suspend fun countAllFiles(localPath: String, driveFolderId: String): Int {
        val localFiles = scanLocalFolder(localPath)
        val driveFiles = driveHelper.listAllFiles(driveFolderId)
        
        val localMap = localFiles.associateBy { it.name }
        val driveMap = driveFiles.associateBy { it.name }
        val allNames = (localMap.keys + driveMap.keys).toSet()
        
        var count = 0
        for (name in allNames) {
            val localFile = localMap[name]
            val driveFile = driveMap[name]
            
            count++ // Count current item
            
            // If it's a directory, count its contents too
            if ((localFile != null && localFile.isDirectory) || (driveFile != null && driveFile.isFolder)) {
                val subDriveId = driveFile?.id
                if (subDriveId != null) {
                    val subLocalPath = File(localPath, name).absolutePath
                    count += countAllFiles(subLocalPath, subDriveId)
                } else if (localFile != null) {
                    // Local directory only, but we don't have Drive ID yet
                    // To be accurate, we'd need to assume it'll be created
                    // For now, let's just count local sub-items
                    count += countLocalFilesRecursive(localFile)
                }
            }
        }
        return count
    }

    private fun countLocalFilesRecursive(file: File): Int {
        var count = 0
        val contents = file.listFiles() ?: return 0
        for (item in contents) {
            count++
            if (item.isDirectory) {
                count += countLocalFilesRecursive(item)
            }
        }
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
        return folder.listFiles()?.toList() ?: emptyList()
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
}
