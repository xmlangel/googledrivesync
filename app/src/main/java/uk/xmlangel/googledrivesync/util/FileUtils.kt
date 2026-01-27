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
        return name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
    }

    /**
     * Calculates the MD5 checksum of a file.
     */
    fun calculateMd5(file: java.io.File): String? {
        if (!file.exists() || file.isDirectory) return null
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            file.inputStream().use { input ->
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    md.update(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}
