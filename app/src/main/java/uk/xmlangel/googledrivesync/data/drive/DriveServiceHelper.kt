package uk.xmlangel.googledrivesync.data.drive

import android.content.Context
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
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Helper class for Google Drive API operations
 */
class DriveServiceHelper(private val context: Context) {
    
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
        
        val result = request.execute()
        
        DriveListResult(
            files = result.files?.map { it.toDriveItem() } ?: emptyList(),
            nextPageToken = result.nextPageToken
        )
    }
    
    /**
     * List all files in a folder, handling pagination automatically
     */
    suspend fun listAllFiles(folderId: String? = null): List<DriveItem> {
        val allFiles = mutableListOf<DriveItem>()
        var pageToken: String? = null
        
        do {
            val result = listFiles(folderId, pageSize = 100, pageToken = pageToken)
            allFiles.addAll(result.files)
            pageToken = result.nextPageToken
        } while (pageToken != null)
        
        return allFiles
    }

    /**
     * Get file metadata
     */
    suspend fun getFileMetadata(fileId: String): DriveItem = withContext(Dispatchers.IO) {
        val file = getDrive().files().get(fileId)
            .setFields("id, name, mimeType, modifiedTime, size, parents, md5Checksum")
            .execute()
        file.toDriveItem()
    }
    
    /**
     * Download a file
     */
    suspend fun downloadFile(fileId: String, destinationPath: String): Boolean = 
        withContext(Dispatchers.IO) {
            try {
                // Get metadata first to check size and mimeType
                val fileMetadata = getDrive().files().get(fileId)
                    .setFields("id, name, mimeType, size")
                    .execute()
                
                val mimeType = fileMetadata.mimeType
                val size = fileMetadata.getSize() ?: 0L
                
                // 1. Handle 0-byte files (Fix for 416 error)
                if (size == 0L && !isGoogleDoc(mimeType)) {
                    File(destinationPath).createNewFile()
                    return@withContext true
                }
                
                val outputStream: OutputStream = FileOutputStream(destinationPath)
                
                // 2. Handle Google Docs (Fix for 403 error)
                if (isGoogleDoc(mimeType)) {
                    val exportMimeType = getExportMimeType(mimeType)
                    getDrive().files().export(fileId, exportMimeType)
                        .executeMediaAndDownloadTo(outputStream)
                } else {
                    // Normal binary file
                    getDrive().files().get(fileId)
                        .executeMediaAndDownloadTo(outputStream)
                }
                
                outputStream.close()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    private fun isGoogleDoc(mimeType: String?): Boolean {
        return mimeType?.startsWith("application/vnd.google-apps.") == true &&
                mimeType != MIME_TYPE_FOLDER
    }

    private fun getExportMimeType(googleMimeType: String?): String {
        return when (googleMimeType) {
            "application/vnd.google-apps.document" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "application/vnd.google-apps.spreadsheet" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "application/vnd.google-apps.presentation" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "application/vnd.google-apps.drawing" -> "image/png"
            "application/vnd.google-apps.script" -> "application/vnd.google-apps.script+json"
            else -> "application/pdf"
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
        try {
            val fileMetadata = DriveFile().apply {
                name = fileName
                if (parentFolderId != null) {
                    parents = listOf(parentFolderId)
                }
            }
            
            val localFile = File(localPath)
            val mediaContent = FileContent(mimeType, localFile)
            
            val file = getDrive().files().create(fileMetadata, mediaContent)
                .setFields("id, name, mimeType, modifiedTime, size, parents")
                .execute()
            
            file.toDriveItem()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Update an existing file
     */
    suspend fun updateFile(
        fileId: String,
        localPath: String,
        mimeType: String = "application/octet-stream"
    ): DriveItem? = withContext(Dispatchers.IO) {
        try {
            val localFile = File(localPath)
            val mediaContent = FileContent(mimeType, localFile)
            
            val file = getDrive().files().update(fileId, null, mediaContent)
                .setFields("id, name, mimeType, modifiedTime, size, parents")
                .execute()
            
            file.toDriveItem()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Create a folder
     */
    suspend fun createFolder(
        folderName: String,
        parentFolderId: String? = null
    ): DriveItem? = withContext(Dispatchers.IO) {
        try {
            val fileMetadata = DriveFile().apply {
                name = folderName
                mimeType = MIME_TYPE_FOLDER
                if (parentFolderId != null) {
                    parents = listOf(parentFolderId)
                }
            }
            
            val file = getDrive().files().create(fileMetadata)
                .setFields("id, name, mimeType, modifiedTime, parents")
                .execute()
            
            file.toDriveItem()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Delete a file or folder
     */
    suspend fun delete(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            getDrive().files().delete(fileId).execute()
            true
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
        
        val result = getDrive().files().list()
            .setQ(searchQuery)
            .setSpaces("drive")
            .setFields("files(id, name, mimeType, modifiedTime, size, parents)")
            .setPageSize(50)
            .execute()
        
        result.files?.map { it.toDriveItem() } ?: emptyList()
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
