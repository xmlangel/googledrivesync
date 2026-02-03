package uk.xmlangel.googledrivesync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.data.model.SyncStatus

/**
 * Entity for tracking sync folder configurations
 */
@Entity(tableName = "sync_folders")
data class SyncFolderEntity(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val accountEmail: String,
    val localPath: String,
    val driveFolderId: String,
    val driveFolderName: String,
    val syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
    val isEnabled: Boolean = true,
    val lastSyncedAt: Long = 0,
    val lastStartPageToken: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Entity for tracking individual file sync state
 */
@Entity(tableName = "sync_items")
data class SyncItemEntity(
    @PrimaryKey
    val id: String,
    val syncFolderId: String,
    val accountId: String,
    val accountEmail: String,
    val localPath: String,
    val driveFileId: String?,
    val fileName: String,
    val mimeType: String,
    val localModifiedAt: Long,
    val driveModifiedAt: Long,
    val localSize: Long,
    val driveSize: Long,
    val md5Checksum: String? = null,
    val status: SyncStatus = SyncStatus.PENDING_UPLOAD,
    val lastSyncedAt: Long = 0,
    val errorMessage: String? = null
)

/**
 * Entity for sync operation history
 */
@Entity(tableName = "sync_history")
data class SyncHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val syncFolderId: String,
    val accountId: String,
    val accountEmail: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val filesUploaded: Int = 0,
    val filesDownloaded: Int = 0,
    val filesSkipped: Int = 0,
    val errors: Int = 0,
    val status: String = "IN_PROGRESS"
)

/**
 * Entity for tracking real-time local file changes (Dirty Tracking)
 */
@Entity(tableName = "dirty_local_items")
data class DirtyLocalItemEntity(
    @PrimaryKey
    val localPath: String,
    val syncFolderId: String,
    val eventType: Int, // From FileObserver events
    val detectedAt: Long = System.currentTimeMillis()
)
