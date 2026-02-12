package uk.xmlangel.googledrivesync.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import uk.xmlangel.googledrivesync.data.model.SyncStatus

@Dao
interface SyncFolderDao {
    
    @Query("SELECT * FROM sync_folders WHERE accountId = :accountId")
    fun getSyncFoldersByAccount(accountId: String): Flow<List<SyncFolderEntity>>
    
    @Query("SELECT * FROM sync_folders WHERE isEnabled = 1")
    fun getEnabledSyncFolders(): Flow<List<SyncFolderEntity>>

    @Query("SELECT * FROM sync_folders WHERE isEnabled = 1")
    suspend fun getEnabledSyncFoldersOnce(): List<SyncFolderEntity>

    @Query("SELECT * FROM sync_folders")
    suspend fun getAllSyncFoldersOnce(): List<SyncFolderEntity>
    
    @Query("SELECT * FROM sync_folders WHERE id = :id")
    suspend fun getSyncFolderById(id: String): SyncFolderEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncFolder(folder: SyncFolderEntity)
    
    @Update
    suspend fun updateSyncFolder(folder: SyncFolderEntity)
    
    @Delete
    suspend fun deleteSyncFolder(folder: SyncFolderEntity)
    
    @Query("UPDATE sync_folders SET lastSyncedAt = :timestamp WHERE id = :folderId")
    suspend fun updateLastSyncTime(folderId: String, timestamp: Long)
    
    @Query("UPDATE sync_folders SET isEnabled = :enabled WHERE id = :folderId")
    suspend fun setFolderEnabled(folderId: String, enabled: Boolean)

    @Query("UPDATE sync_folders SET lastStartPageToken = :pageToken WHERE id = :folderId")
    suspend fun updatePageToken(folderId: String, pageToken: String)

    @Query("UPDATE sync_folders SET lastStartPageToken = NULL WHERE id = :folderId")
    suspend fun clearPageToken(folderId: String)

    @Query("SELECT MAX(lastSyncedAt) FROM sync_folders WHERE accountId = :accountId")
    suspend fun getMaxLastSyncTimeByAccount(accountId: String): Long?

    @Query("SELECT * FROM sync_folders WHERE driveFolderId = :driveFolderId LIMIT 1")
    suspend fun getSyncFolderByDriveId(driveFolderId: String): SyncFolderEntity?

    @Query("DELETE FROM sync_folders WHERE accountId = :accountId")
    suspend fun deleteFoldersByAccount(accountId: String)
}

@Dao
interface SyncItemDao {
    
    @Query("SELECT * FROM sync_items WHERE syncFolderId = :folderId")
    fun getSyncItemsByFolder(folderId: String): Flow<List<SyncItemEntity>>

    @Query("SELECT * FROM sync_items")
    suspend fun getAllSyncItems(): List<SyncItemEntity>
    
    @Query("SELECT * FROM sync_items WHERE status = :status")
    suspend fun getSyncItemsByStatus(status: SyncStatus): List<SyncItemEntity>
    
    @Query("SELECT * FROM sync_items WHERE syncFolderId = :folderId AND status IN (:statuses)")
    suspend fun getPendingSyncItems(folderId: String, statuses: List<SyncStatus>): List<SyncItemEntity>
    
    @Query("SELECT * FROM sync_items WHERE localPath = :localPath")
    suspend fun getSyncItemByLocalPath(localPath: String): SyncItemEntity?
    
    @Query("SELECT * FROM sync_items WHERE driveFileId = :driveFileId")
    suspend fun getSyncItemByDriveId(driveFileId: String): SyncItemEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncItem(item: SyncItemEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncItems(items: List<SyncItemEntity>)
    
    @Update
    suspend fun updateSyncItem(item: SyncItemEntity)
    
    @Delete
    suspend fun deleteSyncItem(item: SyncItemEntity)
    
    @Query("DELETE FROM sync_items WHERE syncFolderId = :folderId")
    suspend fun deleteItemsByFolder(folderId: String)
    
    @Query("UPDATE sync_items SET status = :status WHERE id = :itemId")
    suspend fun updateItemStatus(itemId: String, status: SyncStatus)
    
    @Query("UPDATE sync_items SET status = :status, errorMessage = :error WHERE id = :itemId")
    suspend fun updateItemError(itemId: String, status: SyncStatus, error: String)
    
    @Query("SELECT COUNT(*) FROM sync_items WHERE syncFolderId = :folderId AND status = :status")
    suspend fun countItemsByStatus(folderId: String, status: SyncStatus): Int

    @Query("DELETE FROM sync_items WHERE accountId = :accountId")
    suspend fun deleteItemsByAccount(accountId: String)
}

@Dao
interface SyncHistoryDao {
    
    @Query("SELECT * FROM sync_history WHERE syncFolderId = :folderId ORDER BY startedAt DESC LIMIT :limit")
    fun getSyncHistory(folderId: String, limit: Int = 20): Flow<List<SyncHistoryEntity>>
    
    @Insert
    suspend fun insertHistory(history: SyncHistoryEntity): Long
    
    @Update
    suspend fun updateHistory(history: SyncHistoryEntity)
    
    @Query("UPDATE sync_history SET completedAt = :completedAt, status = :status, filesUploaded = :uploaded, filesDownloaded = :downloaded, filesSkipped = :skipped, errors = :errors WHERE id = :id")
    suspend fun completeHistory(
        id: Long,
        completedAt: Long,
        status: String,
        uploaded: Int,
        downloaded: Int,
        skipped: Int,
        errors: Int
    )

    @Query("DELETE FROM sync_history WHERE accountId = :accountId")
    suspend fun deleteHistoryByAccount(accountId: String)
}

@Dao
interface DirtyLocalDao {
    @Query("SELECT * FROM dirty_local_items WHERE syncFolderId = :folderId")
    suspend fun getDirtyItemsByFolder(folderId: String): List<DirtyLocalItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDirtyItem(item: DirtyLocalItemEntity)

    @Query("DELETE FROM dirty_local_items WHERE localPath = :localPath")
    suspend fun deleteDirtyItemByPath(localPath: String)

    @Query("DELETE FROM dirty_local_items WHERE syncFolderId = :folderId")
    suspend fun deleteDirtyItemsByFolder(folderId: String)

    @Query("DELETE FROM dirty_local_items WHERE syncFolderId = :folderId AND detectedAt <= :detectedAt")
    suspend fun deleteDirtyItemsByFolderBefore(folderId: String, detectedAt: Long)

    @Query("SELECT COUNT(*) FROM dirty_local_items WHERE syncFolderId = :folderId AND detectedAt > :detectedAt")
    suspend fun countDirtyItemsByFolderAfter(folderId: String, detectedAt: Long): Int
}
