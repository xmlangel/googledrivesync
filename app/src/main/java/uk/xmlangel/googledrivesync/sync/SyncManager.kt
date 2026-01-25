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
class SyncManager(
    private val context: Context,
    private val driveHelper: DriveServiceHelper = DriveServiceHelper(context),
    private val database: SyncDatabase = SyncDatabase.getInstance(context),
    private val syncFolderDao: SyncFolderDao = database.syncFolderDao(),
    private val syncItemDao: SyncItemDao = database.syncItemDao(),
    private val historyDao: SyncHistoryDao = database.syncHistoryDao(),
    private val syncPreferences: SyncPreferences = SyncPreferences(context),
    private val logger: uk.xmlangel.googledrivesync.util.SyncLogger = uk.xmlangel.googledrivesync.util.SyncLogger(context)
) {
    
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
    suspend fun syncFolder(folderId: String): SyncResult {
        if (_isSyncing.value) {
            return SyncResult.Error("동기화가 이미 진행 중입니다")
        }
        
        _isSyncing.value = true
        
        try {
            val folder = syncFolderDao.getSyncFolderById(folderId)
                ?: return SyncResult.Error("동기화 폴더를 찾을 수 없습니다")
            
            // Initialize Drive service for this specific account
            if (!driveHelper.initializeDriveService(folder.accountEmail)) {
                return SyncResult.Error("계정 동기화 서비스를 초기화할 수 없습니다")
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

        val localMap = localItems.associateBy { it.name }
        val driveMap = driveItems.associateBy { it.name }
        val allNames = (localMap.keys + driveMap.keys).toSet()

        for (name in allNames) {
            val localFile = localMap[name]
            val driveFile = driveMap[name]

            _syncProgress.value = SyncProgress(
                currentFile = name,
                currentIndex = 0, // Simplified for recursive calls
                totalFiles = allNames.size,
                bytesTransferred = 0,
                totalBytes = localFile?.length() ?: driveFile?.size ?: 0,
                isUploading = driveFile == null
            )

            when {
                // Folder syncing
                (localFile != null && localFile.isDirectory) || (driveFile != null && driveFile.isFolder) -> {
                    val subDriveFolderId = when {
                        localFile != null && driveFile != null -> driveFile.id
                        localFile != null && driveFile == null -> {
                            if (folder.syncDirection != SyncDirection.DOWNLOAD_ONLY) {
                                logger.log("폴더 업로드: ${localFile.name}", folder.accountEmail)
                                val created = driveHelper.createFolder(localFile.name, driveFolderId)
                                created?.id
                            } else null
                        }
                        localFile == null && driveFile != null -> {
                            if (folder.syncDirection != SyncDirection.UPLOAD_ONLY) {
                                logger.log("폴더 다운로드: ${driveFile.name}", folder.accountEmail)
                                val dir = File(localPath, driveFile.name)
                                if (!dir.exists()) dir.mkdirs()
                                driveFile.id
                            } else null
                        }
                        else -> null
                    }

                    if (subDriveFolderId != null) {
                        val subLocalPath = File(localPath, name).absolutePath
                        val subLocalItems = scanLocalFolder(subLocalPath)
                        val subDriveItems = driveHelper.listAllFiles(subDriveFolderId)
                        
                        val subResult = syncDirectoryRecursive(folder, subLocalPath, subDriveFolderId, subLocalItems, subDriveItems)
                        uploaded += subResult.uploaded
                        downloaded += subResult.downloaded
                        skipped += subResult.skipped
                        errors += subResult.errors
                        conflicts.addAll(subResult.conflicts)
                    } else {
                        skipped++
                    }
                }

                // File syncing (Existing logic moved here)
                localFile != null && driveFile == null -> {
                    if (folder.syncDirection != SyncDirection.DOWNLOAD_ONLY) {
                        logger.log("업로드 시작: $name", folder.accountEmail)
                        val result = driveHelper.uploadFile(localFile.absolutePath, name, driveFolderId)
                        if (result != null) {
                            uploaded++; logger.log("업로드 성공: $name", folder.accountEmail)
                            trackSyncItem(folder, localFile, result.id, SyncStatus.SYNCED)
                        } else {
                            errors++; logger.log("업로드 실패: $name", folder.accountEmail)
                        }
                    } else skipped++
                }

                localFile == null && driveFile != null -> {
                    if (folder.syncDirection != SyncDirection.UPLOAD_ONLY) {
                        logger.log("다운로드 시작: $name", folder.accountEmail)
                        val destPath = File(localPath, name).absolutePath
                        val success = driveHelper.downloadFile(driveFile.id, destPath)
                        if (success) {
                            downloaded++; logger.log("다운로드 성공: $name", folder.accountEmail)
                            trackSyncItem(folder, File(destPath), driveFile.id, SyncStatus.SYNCED)
                        } else {
                            errors++; logger.log("다운로드 실패: $name", folder.accountEmail)
                        }
                    } else skipped++
                }

                localFile != null && driveFile != null -> {
                    val localModified = localFile.lastModified()
                    val driveModified = driveFile.modifiedTime
                    val existingItem = syncItemDao.getSyncItemByLocalPath(localFile.absolutePath)
                    val lastSyncedAt = existingItem?.lastSyncedAt ?: 0L
                    
                    val isLocalUpdated = localModified > lastSyncedAt
                    val isDriveUpdated = driveModified > lastSyncedAt
                    
                    when {
                        isLocalUpdated && isDriveUpdated -> {
                            val conflict = SyncConflict(existingItem ?: createSyncItem(folder, localFile, driveFile.id), name, localModified, localFile.length(), driveFile.name, driveModified, driveFile.size)
                            val defaultResolution = syncPreferences.defaultConflictResolution
                            if (defaultResolution != null) {
                                if (resolveConflict(conflict, defaultResolution)) {
                                    if (defaultResolution == ConflictResolution.USE_LOCAL) uploaded++ else downloaded++
                                } else errors++
                            } else conflicts.add(conflict)
                        }
                        isLocalUpdated -> {
                            if (folder.syncDirection != SyncDirection.DOWNLOAD_ONLY) {
                                val result = driveHelper.updateFile(existingItem?.driveFileId ?: driveFile.id, localFile.absolutePath)
                                if (result != null) {
                                    uploaded++; updateSyncItem(existingItem!!, localFile, driveFile.id, SyncStatus.SYNCED)
                                } else errors++
                            } else skipped++
                        }
                        isDriveUpdated -> {
                            if (folder.syncDirection != SyncDirection.UPLOAD_ONLY) {
                                if (driveHelper.downloadFile(driveFile.id, localFile.absolutePath)) {
                                    downloaded++; updateSyncItem(existingItem ?: createSyncItem(folder, localFile, driveFile.id), localFile, driveFile.id, SyncStatus.SYNCED)
                                } else errors++
                            } else skipped++
                        }
                        else -> skipped++
                    }
                }
            }
        }
        return RecursiveSyncResult(uploaded, downloaded, skipped, errors, conflicts)
    }

    private data class RecursiveSyncResult(
        val uploaded: Int,
        val downloaded: Int,
        val skipped: Int,
        val errors: Int,
        val conflicts: List<SyncConflict>
    )

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
        status: SyncStatus
    ) {
        val updatedItem = existingItem.copy(
            driveFileId = driveFileId ?: existingItem.driveFileId,
            mimeType = getMimeType(localFile.name),
            localModifiedAt = localFile.lastModified(),
            driveModifiedAt = System.currentTimeMillis(),
            localSize = localFile.length(),
            driveSize = localFile.length(),
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
        status: SyncStatus
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
            accountEmail = folder.accountEmail,
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
        return uk.xmlangel.googledrivesync.util.MimeTypeUtil.getMimeType(fileName)
    }
}
