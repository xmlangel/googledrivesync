@file:Suppress("DEPRECATION")

package uk.xmlangel.googledrivesync.data.drive

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Helper class for Google Drive API operations
 */
class DriveServiceHelper(private val context: Context) {
    data class DownloadResult(
        val success: Boolean,
        val skipped: Boolean = false,
        val reason: String? = null,
        val mimeType: String? = null,
        val size: Long? = null
    )
    
    private var driveService: Drive? = null
    
    /**
     * Set Drive service for testing
     */
    @androidx.annotation.VisibleForTesting
    internal fun setDriveServiceForTest(service: Drive) {
        this.driveService = service
    }
    
    /**
     * Initialize Drive service with a specific account or current signed-in account
     */
    fun initializeDriveService(accountEmail: String? = null): Boolean {
        val account = if (accountEmail != null) {
            android.accounts.Account(accountEmail, "com.google")
        } else {
            GoogleSignIn.getLastSignedInAccount(context)?.account
        } ?: return false
        
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE)
        )
        credential.selectedAccount = account
        
        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Google Drive Sync")
            .build()
        
        return true
    }
    
    /**
     * Get Drive service instance
     */
    private fun getDrive(): Drive {
        return driveService ?: throw IllegalStateException("Drive service not initialized")
    }
    
    /**
     * List files in a folder
     */
    suspend fun listFiles(
        folderId: String? = null,
        pageSize: Int = 100,
        pageToken: String? = null
    ): DriveListResult = withContext(Dispatchers.IO) {
        val query = if (folderId != null) {
            "'$folderId' in parents and trashed = false"
        } else {
            "'root' in parents and trashed = false"
        }
        
        val request = getDrive().files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("nextPageToken, files(id, name, mimeType, modifiedTime, size, parents)")
            .setPageSize(pageSize)
        
        if (pageToken != null) {
            request.pageToken = pageToken
        }
        
        val result = runWithRetry { request.execute() }
        
        DriveListResult(
            files = result.files?.map { it.toDriveItem() } ?: emptyList(),
            nextPageToken = result.nextPageToken
        )
    }
        /**
     * List all files in a folder, handling pagination automatically.
     * @param onProgress Callback invoked with the current count of retrieved files.
     */
    suspend fun listAllFiles(
        folderId: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): List<DriveItem> {
        val allFiles = mutableListOf<DriveItem>()
        var pageToken: String? = null
                do {
            val result = listFiles(folderId, pageSize = 100, pageToken = pageToken)
            allFiles.addAll(result.files)
            pageToken = result.nextPageToken
            onProgress?.invoke(allFiles.size)
        } while (pageToken != null)
        
        return allFiles
    }

    /**
     * Get the starting page token for Changes API
     */
    suspend fun getStartPageToken(): String? = withContext(Dispatchers.IO) {
        try {
            runWithRetry { getDrive().changes().getStartPageToken().execute() }.startPageToken
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get changes since a specific page token
     */
    suspend fun getChanges(pageToken: String): DriveChangeResult = withContext(Dispatchers.IO) {
        val result = runWithRetry {
            getDrive().changes().list(pageToken)
                .setFields("nextPageToken, newStartPageToken, changes(fileId, removed, file(id, name, mimeType, modifiedTime, size, parents, md5Checksum))")
                .execute()
        }
        
        DriveChangeResult(
            changes = result.changes?.map { it.toDriveChange() } ?: emptyList(),
            nextPageToken = result.nextPageToken,
            newStartPageToken = result.newStartPageToken
        )
    }

    /**
     * Get file metadata
     */
    suspend fun getFileMetadata(fileId: String): DriveItem = withContext(Dispatchers.IO) {
        val file = runWithRetry {
            getDrive().files().get(fileId)
                .setFields("id, name, mimeType, modifiedTime, size, parents, md5Checksum")
                .execute()
        }
        file.toDriveItem()
    }

    /**
     * Get a single file by ID
     */
    suspend fun getFile(fileId: String): DriveItem? = withContext(Dispatchers.IO) {
        try {
            getFileMetadata(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Find a file by name in a specific parent folder
     */
    suspend fun findFile(name: String, parentId: String): DriveItem? = withContext(Dispatchers.IO) {
        try {
            val escapedName = escapeDriveQueryValue(name)
            val query = "name = '$escapedName' and '$parentId' in parents and trashed = false"
            val result = runWithRetry {
                getDrive().files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name, mimeType, modifiedTime, size, parents, md5Checksum)")
                    .setPageSize(1)
                    .execute()
            }
            
            result.files?.firstOrNull()?.toDriveItem()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Find a folder by name in a specific parent folder.
     */
    suspend fun findFolder(name: String, parentId: String): DriveItem? = withContext(Dispatchers.IO) {
        try {
            val escapedName = escapeDriveQueryValue(name)
            val query = "name = '$escapedName' and '$parentId' in parents and trashed = false and mimeType = '$MIME_TYPE_FOLDER'"
            val result = runWithRetry {
                getDrive().files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name, mimeType, modifiedTime, size, parents, md5Checksum)")
                    .setPageSize(1)
                    .execute()
            }

            result.files?.firstOrNull()?.toDriveItem()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Download a file
     */
    suspend fun downloadFile(fileId: String, destinationPath: String): Boolean =
        downloadFileDetailed(fileId, destinationPath).success

    suspend fun downloadFileDetailed(fileId: String, destinationPath: String): DownloadResult =
        withContext(Dispatchers.IO) {
            try {
                // Get metadata first to check size and mimeType
                val fileMetadata = runWithRetry {
                    getDrive().files().get(fileId)
                        .setFields("id, name, mimeType, size")
                        .execute()
                }

                val mimeType = fileMetadata.mimeType
                val size = fileMetadata.getSize() ?: 0L
                val destinationFile = File(destinationPath)
                destinationFile.parentFile?.mkdirs()

                // Handle non-downloadable Google types (shortcuts/forms/sites/maps/folders)
                if (isNonDownloadable(mimeType)) {
                    return@withContext DownloadResult(
                        success = false,
                        skipped = true,
                        reason = "non_downloadable_google_type",
                        mimeType = mimeType,
                        size = size
                    )
                }

                // Handle 0-byte regular files
                if (size == 0L && !isGoogleDoc(mimeType)) {
                    return@withContext try {
                        destinationFile.createNewFile()
                        DownloadResult(success = true, mimeType = mimeType, size = size)
                    } catch (e: Exception) {
                        DownloadResult(
                            success = false,
                            reason = "create_file_failed:${e.javaClass.simpleName}:${e.message ?: "no_message"}",
                            mimeType = mimeType,
                            size = size
                        )
                    }
                }

                // Google Docs export or binary download
                return@withContext try {
                    FileOutputStream(destinationPath).use { outputStream ->
                        if (isGoogleDoc(mimeType)) {
                            val exportMimeType = getExportMimeType(mimeType)
                            runWithRetry {
                                getDrive().files().export(fileId, exportMimeType)
                                    .executeMediaAndDownloadTo(outputStream)
                            }
                        } else {
                            runWithRetry {
                                getDrive().files().get(fileId)
                                    .executeMediaAndDownloadTo(outputStream)
                            }
                        }
                    }
                    DownloadResult(success = true, mimeType = mimeType, size = size)
                } catch (e: Exception) {
                    DownloadResult(
                        success = false,
                        reason = "download_failed:${e.javaClass.simpleName}:${e.message ?: "no_message"}",
                        mimeType = mimeType,
                        size = size
                    )
                }
            } catch (e: Exception) {
                DownloadResult(
                    success = false,
                    reason = "metadata_failed:${e.javaClass.simpleName}:${e.message ?: "no_message"}"
                )
            }
        }

    private fun isGoogleDoc(mimeType: String?): Boolean {
        return mimeType == "application/vnd.google-apps.document" ||
                mimeType == "application/vnd.google-apps.spreadsheet" ||
                mimeType == "application/vnd.google-apps.presentation" ||
                mimeType == "application/vnd.google-apps.drawing" ||
                mimeType == "application/vnd.google-apps.script"
    }

    private fun isNonDownloadable(mimeType: String?): Boolean {
        return mimeType == "application/vnd.google-apps.folder" ||
                mimeType == "application/vnd.google-apps.shortcut" ||
                mimeType == "application/vnd.google-apps.map" ||
                mimeType == "application/vnd.google-apps.form" ||
                mimeType == "application/vnd.google-apps.site"
    }

    private fun getExportMimeType(googleMimeType: String?): String {
        return when (googleMimeType) {
            "application/vnd.google-apps.document" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "application/vnd.google-apps.spreadsheet" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "application/vnd.google-apps.presentation" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "application/vnd.google-apps.drawing" -> "image/png"
            "application/vnd.google-apps.script" -> "application/vnd.google-apps.script+json"
            else -> "application/pdf" // Should not be reached with refined isGoogleDoc
        }
    }
    
    /**
     * Upload a file
     */
    suspend fun uploadFile(
        localPath: String,
        fileName: String,
        parentFolderId: String?,
        mimeType: String = "application/octet-stream"
    ): DriveItem? = withContext(Dispatchers.IO) {
        val fileMetadata = DriveFile().apply {
            name = fileName
            if (parentFolderId != null) {
                parents = listOf(parentFolderId)
            }
        }
        
        val localFile = File(localPath)
        val mediaContent = FileContent(mimeType, localFile)
        
        val file = runWithRetry {
            getDrive().files().create(fileMetadata, mediaContent)
                .setFields("id, name, mimeType, modifiedTime, size, parents")
                .execute()
        }
        
        file.toDriveItem()
    }
    
    /**
     * Update an existing file
     */
    suspend fun updateFile(
        fileId: String,
        localPath: String,
        mimeType: String = "application/octet-stream"
    ): DriveItem? = withContext(Dispatchers.IO) {
        val localFile = File(localPath)
        val mediaContent = FileContent(mimeType, localFile)
        
        val file = runWithRetry {
            getDrive().files().update(fileId, null, mediaContent)
                .setFields("id, name, mimeType, modifiedTime, size, parents")
                .execute()
        }
        
        file.toDriveItem()
    }
    
    /**
     * Update file metadata (name, parents) without changing content
     */
    suspend fun updateMetadata(
        fileId: String,
        newName: String? = null,
        addParents: String? = null,
        removeParents: String? = null
    ): Boolean {
        return try {
            val fileMetadata = com.google.api.services.drive.model.File()
            if (newName != null) {
                fileMetadata.name = newName
            }

            runWithRetry {
                val request = getDrive().files().update(fileId, fileMetadata)
                if (addParents != null) {
                    request.addParents = addParents
                }
                if (removeParents != null) {
                    request.removeParents = removeParents
                }
                
                request.setFields("id, name, parents").execute()
            }
            true
        } catch (e: Exception) {
            Log.e("DriveServiceHelper", "Error updating metadata: ${e.message}")
            false
        }
    }
    
    /**
     * Create a folder
     */
    suspend fun createFolder(
        folderName: String,
        parentFolderId: String? = null
    ): DriveItem? = withContext(Dispatchers.IO) {
        val fileMetadata = DriveFile().apply {
            name = folderName
            mimeType = MIME_TYPE_FOLDER
            if (parentFolderId != null) {
                parents = listOf(parentFolderId)
            }
        }
        
        val file = runWithRetry {
            getDrive().files().create(fileMetadata)
                .setFields("id, name, mimeType, modifiedTime, parents")
                .execute()
        }
        
        file.toDriveItem()
    }
    
    /**
     * Delete a file or folder
     */
    suspend fun delete(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            runWithRetry { getDrive().files().delete(fileId).execute() }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Search files by name
     */
    suspend fun searchFiles(query: String): List<DriveItem> = withContext(Dispatchers.IO) {
        val searchQuery = "name contains '$query' and trashed = false"
        
        val result = runWithRetry {
            getDrive().files().list()
                .setQ(searchQuery)
                .setSpaces("drive")
                .setFields("files(id, name, mimeType, modifiedTime, size, parents)")
                .setPageSize(50)
                .execute()
        }
        
        result.files?.map { it.toDriveItem() } ?: emptyList()
    }

    /**
     * Run a block of code with retry logic for SocketTimeoutException
     */
    private suspend fun <T> runWithRetry(
        maxRetries: Int = 5,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                return block()
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
                if (attempt == maxRetries) {
                    Log.e("DriveServiceHelper", "최대 재시도 횟수($maxRetries) 초과: ${e.message}")
                    throw e
                }
                
                // Exponential backoff: 1s, 2s, 4s, 8s, 16s
                val delayMillis = Math.pow(2.0, (attempt - 1).toDouble()).toLong() * 1000L
                Log.w("DriveServiceHelper", "네트워크 타임아웃 발생 ($attempt/$maxRetries). ${delayMillis}ms 후 재시도합니다...")
                delay(delayMillis)
            }
        }
        throw lastException ?: java.io.IOException("재시도 실패")
    }

    private fun escapeDriveQueryValue(value: String): String {
        return value.replace("\\", "\\\\").replace("'", "\\'")
    }
    
    companion object {
        const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
    }
}

/**
 * Extension to convert Drive API File to DriveItem
 */
private fun DriveFile.toDriveItem(): DriveItem {
    return DriveItem(
        id = id,
        name = name,
        mimeType = mimeType,
        modifiedTime = modifiedTime?.value ?: 0L,
        size = getSize()?.toLong() ?: 0L,
        md5Checksum = md5Checksum,
        parentIds = parents ?: emptyList(),
        isFolder = mimeType == DriveServiceHelper.MIME_TYPE_FOLDER
    )
}

/**
 * Drive file/folder item
 */
data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTime: Long,
    val size: Long,
    val md5Checksum: String? = null,
    val parentIds: List<String>,
    val isFolder: Boolean
)

/**
 * Result of listing files
 */
data class DriveListResult(
    val files: List<DriveItem>,
    val nextPageToken: String?
)

/**
 * Result of listing changes
 */
data class DriveChangeResult(
    val changes: List<DriveChange>,
    val nextPageToken: String?,
    val newStartPageToken: String?
)

/**
 * Individual change item
 */
data class DriveChange(
    val fileId: String,
    val removed: Boolean,
    val file: DriveItem? = null
)

/**
 * Extension to convert Drive API Change to DriveChange
 */
private fun com.google.api.services.drive.model.Change.toDriveChange(): DriveChange {
    return DriveChange(
        fileId = fileId,
        removed = removed ?: false,
        file = file?.toDriveItem()
    )
}
