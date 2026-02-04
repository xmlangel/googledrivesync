package uk.xmlangel.googledrivesync.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility for logging synchronization events to a file with size management
 */
class SyncLogger(private val context: Context) {
    
    private val logFile = File(context.cacheDir, LOG_FILE_NAME)
    private val oldLogFile = File(context.cacheDir, "$LOG_FILE_NAME.old")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * Log a message with current timestamp and optional account info
     */
    fun log(message: String, account: String? = null) {
        try {
            checkRotation()
            val timestamp = dateFormat.format(Date())
            val accountTag = if (account != null) " [$account]" else ""
            val logEntry = "[$timestamp]$accountTag $message\n"
            
            FileOutputStream(logFile, true).use { output ->
                output.write(logEntry.toByteArray())
            }
            
            // Emit to SharedFlow for real-time updates
            _logEvents.tryEmit(logEntry.trim())

            // Also output to Logcat for easier debugging
            val fullMessage = accountTag + " " + message
            if (message.startsWith("[ERROR]")) {
                Log.e(TAG, fullMessage)
            } else {
                Log.d(TAG, fullMessage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get the log file for sharing/exporting
     */
    fun getLogFile(): File = logFile

    /**
     * Read all logs from current and old log files
     */
    fun readLogs(): List<String> {
        val logs = mutableListOf<String>()
        
        // Read current logs
        if (logFile.exists()) {
            logs.addAll(logFile.readLines())
        }
        
        // Optionally read old logs if needed, but for UI simplicity, usually current is enough
        // or we can prepend old logs
        /*
        if (oldLogFile.exists()) {
            val oldLogs = oldLogFile.readLines()
            logs.addAll(0, oldLogs)
        }
        */
        
        return logs.reversed() // Newest first
    }

    /**
     * Clear all log files
     */
    fun clearLogs() {
        if (logFile.exists()) logFile.delete()
        if (oldLogFile.exists()) oldLogFile.delete()
    }

    /**
     * Check if log file exceeds MAX_SIZE and rotate if necessary
     */
    private fun checkRotation() {
        if (logFile.exists() && logFile.length() > MAX_SIZE) {
            if (oldLogFile.exists()) {
                oldLogFile.delete()
            }
            logFile.renameTo(oldLogFile)
        }
    }

    companion object {
        private const val LOG_FILE_NAME = "sync.log"
        private const val MAX_SIZE = 10 * 1024 * 1024 // 10MB
        private const val TAG = "GoogleDriveSync"

        private val _logEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(
            extraBufferCapacity = 100,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )
        val logEvents = _logEvents.asSharedFlow()
    }
}
