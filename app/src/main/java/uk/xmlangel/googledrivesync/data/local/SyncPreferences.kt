package uk.xmlangel.googledrivesync.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import uk.xmlangel.googledrivesync.sync.ConflictResolution

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
        
        const val DEFAULT_SYNC_INTERVAL = 60 // minutes
    }
    
    /**
     * Get sync interval in minutes
     */
    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL)
        set(value) = prefs.edit { putInt(KEY_SYNC_INTERVAL_MINUTES, value) }
    
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
     * Available sync interval options (in minutes)
     */
    val availableIntervals = listOf(
        15, 30, 60, 120, 360, 720, 1440
    )
    
    /**
     * Format interval for display
     */
    fun formatInterval(minutes: Int): String {
        return when {
            minutes < 60 -> "${minutes}분"
            minutes == 60 -> "1시간"
            minutes < 1440 -> "${minutes / 60}시간"
            else -> "24시간"
        }
    }
}
