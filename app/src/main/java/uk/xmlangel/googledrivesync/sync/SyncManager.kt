package uk.xmlangel.googledrivesync.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.xmlangel.googledrivesync.data.drive.DriveServiceHelper
import uk.xmlangel.googledrivesync.data.local.*
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.data.model.SyncStatus
import java.io.File
import java.util.UUID

/**
 * Manager for synchronization operations with bidirectional sync support
 */
class SyncManager(private val context: Context) {
    
    private val driveHelper = DriveServiceHelper(context)
    private val database = SyncDatabase.getInstance(context)
    private val syncFolderDao = database.syncFolderDao()
    private val syncItemDao = database.syncItemDao()
    private val historyDao = database.syncHistoryDao()
    private val syncPreferences = SyncPreferences(context)
    private val logger = uk.xmlangel.googledrivesync.util.SyncLogger(context)
    
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()
    
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    
    private val _pendingConflicts = MutableStateFlow<List<SyncConflict>>(emptyList())
    val pendingConflicts: StateFlow<List<SyncConflict>> = _pendingConflicts.asStateFlow()
    
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
        localPath: String,
        driveFolderId: String,
        driveFolderName: String,
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL
    ): SyncFolderEntity {
        val folder = SyncFolderEntity(
            id = UUID.randomUUID().toString(),
            accountId = accountId,
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
    suspend fun syncFolder(folderId: String): SyncResult {
        if (_isSyncing.value) {
            return SyncResult.Error("동기화가 이미 진행 중입니다")
        }
        
        _isSyncing.value = true
        
        try {
            val folder = syncFolderDao.getSyncFolderById(folderId)
                ?: return SyncResult.Error("동기화 폴더를 찾을 수 없습니다")
            
            // Start history entry
            val historyId = historyDao.insertHistory(
                SyncHistoryEntity(
                    syncFolderId = folderId,
                    accountId = folder.accountId,
                    startedAt = System.currentTimeMillis()
                )
            )
            
            logger.log("동기화 시작: folderId=$folderId")
            
            var uploaded = 0
            var downloaded = 0
            var skipped = 0
            var errors = 0
            val conflicts = mutableListOf<SyncConflict>()
            
            // Get local files
            val localFiles = scanLocalFolder(folder.localPath)
            
            // Get drive files
            val driveResult = driveHelper.listFiles(folder.driveFolderId)
            val driveFiles = driveResult.files
            
            logger.log("스캔 완료: 로컬=${localFiles.size}개, 드라이브=${driveFiles.size}개")
            
            // Build maps for comparison
            val localFileMap = localFiles.associateBy { it.name }
            val driveFileMap = driveFiles.associateBy { it.name }
            
            val allFileNames = (localFileMap.keys + driveFileMap.keys).toSet()
            var currentIndex = 0
            
            for (fileName in allFileNames) {
                currentIndex++
                val localFile = localFileMap[fileName]
                val driveFile = driveFileMap[fileName]
                
                // Update progress
                _syncProgress.value = SyncProgress(
                    currentFile = fileName,
                    currentIndex = currentIndex,
                    totalFiles = allFileNames.size,
                    bytesTransferred = 0,
                    totalBytes = localFile?.length() ?: driveFile?.size ?: 0,
                    isUploading = driveFile == null
                )
                
                when {
                    // File exists only locally → Upload
                    localFile != null && driveFile == null -> {
                        if (folder.syncDirection != SyncDirection.DOWNLOAD_ONLY) {
                            logger.log("업로드 시작: $fileName")
                            val result = driveHelper.uploadFile(
                                localPath = localFile.absolutePath,
                                fileName = fileName,
                                parentFolderId = folder.driveFolderId
                            )
                            if (result != null) {
                                uploaded++
                                logger.log("업로드 성공: $fileName")
                                trackSyncItem(folder, localFile, result.id, SyncStatus.SYNCED)
                            } else {
                                errors++
                                logger.log("업로드 실패: $fileName")
                            }
                        } else {
                            skipped++
                        }
                    }
                    
                    // File exists only on Drive → Download
                    localFile == null && driveFile != null -> {
                        if (folder.syncDirection != SyncDirection.UPLOAD_ONLY) {
                            if (!driveFile.isFolder) {
                                logger.log("다운로드 시작: $fileName")
                                val destPath = "${folder.localPath}/${driveFile.name}"
                                val success = driveHelper.downloadFile(driveFile.id, destPath)
                                if (success) {
                                    downloaded++
                                    logger.log("다운로드 성공: $fileName")
                                    trackSyncItem(folder, File(destPath), driveFile.id, SyncStatus.SYNCED)
                                } else {
                                    errors++
                                    logger.log("다운로드 실패: $fileName")
                                }
                            }
                        } else {
                            skipped++
                        }
                    }
                    
                    // File exists in both → Check for conflicts
                    localFile != null && driveFile != null -> {
                        val localModified = localFile.lastModified()
                        val driveModified = driveFile.modifiedTime
                        
                        // Check if files are in sync
                        val existingItem = syncItemDao.getSyncItemByLocalPath(localFile.absolutePath)
                        val isConflict = existingItem?.let {
                            localModified > it.lastSyncedAt && driveModified > it.lastSyncedAt
                        } ?: (localModified != driveModified)
                        
                        if (isConflict) {
                            val conflict = SyncConflict(
                                syncItem = existingItem ?: createSyncItem(folder, localFile, driveFile.id),
                                localFileName = fileName,
                                localModifiedAt = localModified,
                                localSize = localFile.length(),
                                driveFileName = driveFile.name,
                                driveModifiedAt = driveModified,
                                driveSize = driveFile.size
                            )
                            
                            val defaultResolution = syncPreferences.defaultConflictResolution
                            if (defaultResolution != null) {
                                // Automatically resolve with default strategy
                                val success = resolveConflict(conflict, defaultResolution)
                                if (success) {
                                    if (defaultResolution == ConflictResolution.USE_LOCAL) uploaded++
                                    else if (defaultResolution == ConflictResolution.USE_DRIVE) downloaded++
                                    else if (defaultResolution == ConflictResolution.KEEP_BOTH) downloaded++ // renamed local, then downloaded drive
                                } else {
                                    errors++
                                }
                            } else {
                                // Add to conflicts for user resolution
                                conflicts.add(conflict)
                            }
                        } else {
                            skipped++
                        }
                    }
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
            
            logger.log("동기화 완료: 업로드=$uploaded, 다운로드=$downloaded, 스킵=$skipped, 에러=$errors, 충돌=${conflicts.size}")
            
            return if (conflicts.isNotEmpty()) {
                _pendingConflicts.value = conflicts
                SyncResult.Conflict(conflicts)
            } else {
                SyncResult.Success(uploaded, downloaded, skipped)
            }
            
        } catch (e: Exception) {
            return SyncResult.Error(e.message ?: "Unknown error", e)
        } finally {
            _isSyncing.value = false
            _syncProgress.value = null
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
            false
        }
    }
    
    /**
     * Scan local folder for files
     */
    private fun scanLocalFolder(path: String): List<File> {
        val folder = File(path)
        if (!folder.exists() || !folder.isDirectory) {
            return emptyList()
        }
        return folder.listFiles()?.filter { it.isFile } ?: emptyList()
    }
    
    /**
     * Track sync item in database
     */
    private suspend fun trackSyncItem(
        folder: SyncFolderEntity,
        localFile: File,
        driveFileId: String?,
        status: SyncStatus
    ) {
        val item = SyncItemEntity(
            id = UUID.randomUUID().toString(),
            syncFolderId = folder.id,
            accountId = folder.accountId,
            localPath = localFile.absolutePath,
            driveFileId = driveFileId,
            fileName = localFile.name,
            mimeType = getMimeType(localFile.name),
            localModifiedAt = localFile.lastModified(),
            driveModifiedAt = System.currentTimeMillis(),
            localSize = localFile.length(),
            driveSize = localFile.length(),
            status = status,
            lastSyncedAt = System.currentTimeMillis()
        )
        syncItemDao.insertSyncItem(item)
    }
    
    private fun createSyncItem(
        folder: SyncFolderEntity,
        localFile: File,
        driveFileId: String?
    ): SyncItemEntity {
        return SyncItemEntity(
            id = UUID.randomUUID().toString(),
            syncFolderId = folder.id,
            accountId = folder.accountId,
            localPath = localFile.absolutePath,
            driveFileId = driveFileId,
            fileName = localFile.name,
            mimeType = getMimeType(localFile.name),
            localModifiedAt = localFile.lastModified(),
            driveModifiedAt = System.currentTimeMillis(),
            localSize = localFile.length(),
            driveSize = 0,
            status = SyncStatus.CONFLICT,
            lastSyncedAt = 0
        )
    }
    
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "txt" -> "text/plain"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
