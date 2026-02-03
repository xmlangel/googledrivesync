package uk.xmlangel.googledrivesync.data.model

/**
 * Represents a Google account that can sync with Drive
 */
data class GoogleAccount(
    val id: String,
    val email: String,
    val displayName: String?,
    val photoUrl: String?,
    val lastSyncedAt: Long? = null,
    val isActive: Boolean = false
)

/**
 * Sync status for individual items
 */
enum class SyncStatus {
    SYNCED,
    PENDING_UPLOAD,
    PENDING_DOWNLOAD,
    SYNCING,
    CONFLICT,
    ERROR
}

/**
 * Sync direction configuration
 */
enum class SyncDirection {
    BIDIRECTIONAL,
    UPLOAD_ONLY,
    DOWNLOAD_ONLY
}

/**
 * Represents the application version information
 */
data class AppVersion(
    val versionName: String,
    val versionCode: Long
)
