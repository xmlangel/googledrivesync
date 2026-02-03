package uk.xmlangel.googledrivesync.sync

import android.os.FileObserver
import android.util.Log
import java.io.File
import java.util.*

/**
 * A recursive FileObserver that monitors a directory and all its subdirectories.
 * It handles the creation and deletion of sub-observers as directories are added/removed.
 */
class RecursiveFileObserver(
    private val rootPath: String,
    private val mask: Int = FileObserver.MODIFY or FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO,
    private val onEventCallback: (event: Int, path: String?) -> Unit
) {
    private val observers = mutableMapOf<String, SingleFolderObserver>()
    private val TAG = "RecursiveFileObserver"

    init {
        startWatching()
    }

    @Synchronized
    fun startWatching() {
        Log.d(TAG, "Starting recursive watch for: $rootPath")
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) return

        // Recursively add observers for all existing directories
        addObservers(root)
    }

    @Synchronized
    fun stopWatching() {
        Log.d(TAG, "Stopping recursive watch for: $rootPath")
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun addObservers(file: File) {
        if (file.isDirectory) {
            val path = file.absolutePath
            if (!observers.containsKey(path)) {
                val observer = SingleFolderObserver(path)
                observer.startWatching()
                observers[path] = observer
            }

            file.listFiles()?.forEach { 
                if (it.isDirectory) addObservers(it)
            }
        }
    }

    private inner class SingleFolderObserver(val folderPath: String) : FileObserver(File(folderPath), mask) {
        override fun onEvent(event: Int, path: String?) {
            val absolutePath = if (path != null) "$folderPath/$path" else folderPath
            
            // Handle directory creation/deletion to maintain recursive chain
            when (event and ALL_EVENTS) {
                CREATE, MOVED_TO -> {
                    val file = File(absolutePath)
                    if (file.isDirectory) {
                        Log.d(TAG, "New directory detected: $absolutePath. Adding observer.")
                        addObservers(file)
                    }
                }
                DELETE, MOVED_FROM -> {
                    // If a directory was removed, we should remove its observer
                    // But we don't know for sure if it's a directory from the event alone
                    // We can check our map
                    if (observers.containsKey(absolutePath)) {
                        Log.d(TAG, "Directory removed: $absolutePath. Removing observer.")
                        observers[absolutePath]?.stopWatching()
                        observers.remove(absolutePath)
                    }
                }
            }

            // Propagate event to the main callback
            onEventCallback(event, absolutePath)
        }
    }
}
