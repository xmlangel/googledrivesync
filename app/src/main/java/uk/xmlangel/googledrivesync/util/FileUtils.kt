package uk.xmlangel.googledrivesync.util

/**
 * Utility class for file operations
 */
object FileUtils {
    /**
     * Sanitizes a file name by replacing restricted characters with underscore.
     * Restricted characters: \ / : * ? " < > | and control characters (0-31)
     */
    fun sanitizeFileName(name: String): String {
        if (name.isBlank()) return "_"
        
        // Replace restricted characters with '_'
        // Using a regex to match: \ / : * ? " < > | or anything from \u0000 to \u001F
        return name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
    }
}
