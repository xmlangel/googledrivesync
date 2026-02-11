package uk.xmlangel.googledrivesync.sync

import uk.xmlangel.googledrivesync.data.local.SyncItemEntity

/**
 * Conflict information for user resolution
 */
data class SyncConflict(
    val syncItem: SyncItemEntity,
    val localFileName: String,
    val localModifiedAt: Long,
    val localSize: Long,
    val driveFileName: String,
    val driveModifiedAt: Long,
    val driveSize: Long
)

/**
 * Information for a file that is pending upload confirmation
 */
data class PendingUpload(
    val folderId: String,
    val localFile: java.io.File,
    val driveFolderId: String,
    val isNewFile: Boolean,
    val driveFileId: String? = null,
    val accountEmail: String
)

/**
 * User's choice for pending upload
 */
enum class PendingUploadResolution {
    UPLOAD,
    SKIP
}

/**
 * User's choice for conflict resolution
 */
enum class ConflictResolution {
    USE_LOCAL,      // Upload local version to Drive
    USE_DRIVE,      // Download Drive version to local
    KEEP_BOTH,      // Rename and keep both versions
    SKIP            // Skip this file
}

/**
 * Sync operation result
 */
sealed class SyncResult {
    data class Success(
        val uploaded: Int,
        val downloaded: Int,
        val skipped: Int,
        val warningMessage: String? = null
    ) : SyncResult()
    
    data class Conflict(
        val conflicts: List<SyncConflict>
    ) : SyncResult()
    
    data class Error(
        val message: String,
        val exception: Exception? = null
    ) : SyncResult()
    
    object Cancelled : SyncResult()
}

/**
 * Progress update during sync
 */
data class SyncProgress(
    val currentFile: String,
    val currentIndex: Int,
    val totalFiles: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val isUploading: Boolean,
    val statusMessage: String? = null
)
