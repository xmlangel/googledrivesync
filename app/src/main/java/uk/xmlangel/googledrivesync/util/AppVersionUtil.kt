package uk.xmlangel.googledrivesync.util

import android.content.Context
import android.os.Build

/**
 * Utility to get consistent version information for the application.
 */
object AppVersionUtil {

    /**
     * Returns the version string in the format "v$versionName ($versionCode)".
     * Example: "v1.2.0 (18)"
     */
    fun getVersionString(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            
            val name = packageInfo.versionName ?: "Unknown"
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            
            "v$name ($code)"
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
