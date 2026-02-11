package uk.xmlangel.googledrivesync.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.sync.ConflictResolution
import uk.xmlangel.googledrivesync.sync.SyncExclusions
import uk.xmlangel.googledrivesync.sync.SyncExclusionType

/**
 * Preferences for sync settings
 */
class SyncPreferences(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "sync_prefs",
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_SYNC_INTERVAL_MINUTES = "sync_interval_minutes"
        private const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"
        private const val KEY_SYNC_WHILE_CHARGING = "sync_while_charging"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_DEFAULT_CONFLICT_RESOLUTION = "default_conflict_resolution"
        private const val KEY_DEFAULT_SYNC_DIRECTION = "default_sync_direction"
        private const val KEY_AUTO_UPLOAD_ENABLED = "auto_upload_enabled"
        private const val KEY_REALTIME_SYNC_ENABLED = "realtime_sync_enabled"
        private const val KEY_USER_EXCLUDED_PATHS = "user_excluded_paths"
        
        const val DEFAULT_SYNC_INTERVAL = 15 // minutes (Default changed from 60 to 15)
    }
    
    /**
     * Get sync interval in minutes
     * Enforces a minimum of 15 minutes due to Android background task limitations.
     */
    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL).coerceAtLeast(15)
        set(value) = prefs.edit { putInt(KEY_SYNC_INTERVAL_MINUTES, value.coerceAtLeast(15)) }
    
    /**
     * Sync only on Wi-Fi
     */
    var syncWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_SYNC_WIFI_ONLY, true)
        set(value) = prefs.edit { putBoolean(KEY_SYNC_WIFI_ONLY, value) }
    
    /**
     * Sync only while charging
     */
    var syncWhileCharging: Boolean
        get() = prefs.getBoolean(KEY_SYNC_WHILE_CHARGING, false)
        set(value) = prefs.edit { putBoolean(KEY_SYNC_WHILE_CHARGING, value) }
    
    /**
     * Enable automatic background sync
     */
    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SYNC_ENABLED, value) }
    
    /**
     * Enable sync notifications
     */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS_ENABLED, value) }
    
    /**
     * Get default conflict resolution strategy
     * Returns null if "Always Ask" is selected
     */
    var defaultConflictResolution: ConflictResolution?
        get() {
            val name = prefs.getString(KEY_DEFAULT_CONFLICT_RESOLUTION, null)
            return try {
                name?.let { ConflictResolution.valueOf(it) }
            } catch (e: Exception) {
                null
            }
        }
        set(value) = prefs.edit { putString(KEY_DEFAULT_CONFLICT_RESOLUTION, value?.name) }
    
    /**
     * Get default sync direction (Bidirectional, Download only, Upload only)
     */
    var defaultSyncDirection: SyncDirection
        get() {
            val name = prefs.getString(KEY_DEFAULT_SYNC_DIRECTION, SyncDirection.DOWNLOAD_ONLY.name)
            return try {
                SyncDirection.valueOf(name ?: SyncDirection.DOWNLOAD_ONLY.name)
            } catch (e: Exception) {
                SyncDirection.DOWNLOAD_ONLY
            }
        }
        set(value) = prefs.edit { putString(KEY_DEFAULT_SYNC_DIRECTION, value.name) }
    
    /**
     * Enable automatic upload of non-conflicting local changes
     */
    var autoUploadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPLOAD_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_UPLOAD_ENABLED, value) }

    /**
     * Enable realtime local file observer based sync trigger.
     * This is separate from periodic WorkManager schedule.
     */
    var realtimeSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_REALTIME_SYNC_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_REALTIME_SYNC_ENABLED, value) }

    /**
     * User-defined exclusion rules stored as tokens:
     * - file:<relative/path>
     * - directory:<relative/path>
     * - pattern:<glob-pattern>
     *
     * Backward compatibility: legacy plain paths are migrated to file:<path>.
     */
    var userExcludedPaths: Set<String>
        get() = prefs.getStringSet(KEY_USER_EXCLUDED_PATHS, emptySet())
            ?.mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                if (trimmed.contains(":")) {
                    val sep = trimmed.indexOf(':')
                    val type = trimmed.substring(0, sep).lowercase()
                    val value = SyncExclusions.normalizeRelativePath(trimmed.substring(sep + 1))
                    if (value.isBlank()) return@mapNotNull null
                    if (type !in setOf("file", "directory", "pattern")) return@mapNotNull null
                    val normalized = "$type:$value"
                    if (type == "file" && SyncExclusions.isDefaultRelativePath(value)) return@mapNotNull null
                    normalized
                } else {
                    // Legacy format: plain relative path => file rule
                    val value = SyncExclusions.normalizeRelativePath(trimmed)
                    if (value.isBlank() || SyncExclusions.isDefaultRelativePath(value)) return@mapNotNull null
                    "file:$value"
                }
            }
            ?.toSet()
            ?: emptySet()
        set(value) {
            val normalized = value
                .mapNotNull { token ->
                    val sep = token.indexOf(':')
                    if (sep <= 0) return@mapNotNull null
                    val type = token.substring(0, sep).lowercase()
                    val rawValue = token.substring(sep + 1)
                    val normValue = SyncExclusions.normalizeRelativePath(rawValue)
                    if (normValue.isBlank()) return@mapNotNull null
                    if (type !in setOf("file", "directory", "pattern")) return@mapNotNull null
                    if (type == "file" && SyncExclusions.isDefaultRelativePath(normValue)) return@mapNotNull null
                    "$type:$normValue"
                }
                .toSet()
            prefs.edit { putStringSet(KEY_USER_EXCLUDED_PATHS, normalized) }
        }

    fun addUserExcludedPath(relativePath: String) {
        addUserExcludedRule(SyncExclusionType.FILE, relativePath)
    }

    fun addUserExcludedRule(type: SyncExclusionType, value: String) {
        val token = SyncExclusions.buildUserRuleToken(type, value) ?: return
        userExcludedPaths = userExcludedPaths + token
    }

    fun removeUserExcludedPath(relativePath: String) {
        val normalized = SyncExclusions.normalizeRelativePath(relativePath)
        userExcludedPaths = userExcludedPaths - "file:$normalized"
    }

    fun removeUserExcludedRule(token: String) {
        userExcludedPaths = userExcludedPaths - token.trim()
    }
        
    /**
     * Available sync interval options (in minutes)
     * All options must be >= 15 minutes.
     */
    val availableIntervals = listOf(
        15, 30, 60, 120, 360, 720, 1440
    )
    
    /**
     * Format interval for display
     */
    fun formatInterval(minutes: Int): String {
        val displayMinutes = minutes.coerceAtLeast(15)
        return when {
            displayMinutes < 60 -> "${displayMinutes}분"
            displayMinutes == 60 -> "1시간"
            displayMinutes < 1440 -> "${displayMinutes / 60}시간"
            else -> "24시간"
        }
    }
}
