package uk.xmlangel.googledrivesync.util

/**
 * Utility for mapping file extensions to MIME types
 */
object MimeTypeUtil {
    fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "canvas" -> "application/json"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/x-m4a"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"

        }
    }
}
