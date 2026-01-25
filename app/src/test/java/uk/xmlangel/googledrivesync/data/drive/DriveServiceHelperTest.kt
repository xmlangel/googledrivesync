package uk.xmlangel.googledrivesync.data.drive

import android.content.Context
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import java.io.OutputStream

class DriveServiceHelperTest {

    @MockK
    lateinit var mockContext: Context

    @MockK
    lateinit var mockDrive: Drive

    @MockK
    lateinit var mockFiles: Drive.Files

    @MockK
    lateinit var mockGet: Drive.Files.Get

    @MockK
    lateinit var mockExport: Drive.Files.Export

    private lateinit var driveServiceHelper: DriveServiceHelper

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        driveServiceHelper = DriveServiceHelper(mockContext)
        // We'll need a way to inject the mockDrive. 
        // For now, let's assume we'll add a setter or modify the constructor.
        driveServiceHelper.setDriveServiceForTest(mockDrive)
        
        every { mockDrive.files() } returns mockFiles
    }

    @Test
    fun `downloadFile creates empty file for 0-byte files without calling API`() {
        runBlocking {
            val fileId = "zero-byte-id"
        val destPath = File.createTempFile("test", ".txt").absolutePath
        val driveItem = DriveItem(
            id = fileId,
            name = "test.txt",
            mimeType = "text/plain",
            modifiedTime = 1000L,
            size = 0L,
            parentIds = emptyList(),
            isFolder = false
        )

        // Mock metadata fetch (Fix: downloadFile now fetches metadata first)
        val mockDriveFile = DriveFile().apply {
            setId(fileId)
            setMimeType("text/plain")
            setSize(0L)
        }
        every { mockFiles.get(fileId) } returns mockGet
        every { mockGet.setFields(any()) } returns mockGet
        every { mockGet.execute() } returns mockDriveFile
        
        val success = driveServiceHelper.downloadFile(fileId, destPath)
        
        assertTrue(success)
        val destFile = File(destPath)
        assertTrue(destFile.exists())
        assertEquals(0L, destFile.length())
        
        // Verify no download call was made (only metadata get was called)
        verify(exactly = 1) { mockFiles.get(fileId) }
        verify(exactly = 0) { mockGet.executeMediaAndDownloadTo(any()) }
        
        destFile.delete()
        }
    }

    @Test
    fun `downloadFile uses export for Google Docs`() {
        runBlocking {
            val fileId = "google-doc-id"
        val destPath = File.createTempFile("test", ".pdf").absolutePath
        
        // Mock getFileMetadata to return a Google Doc mime type
        val googleDocMimeType = "application/vnd.google-apps.document"
        val mockDriveFile = DriveFile().apply {
            setId(fileId)
            setMimeType(googleDocMimeType)
        }
        
        every { mockFiles.get(fileId) } returns mockGet
        every { mockGet.setFields(any()) } returns mockGet
        every { mockGet.execute() } returns mockDriveFile
        
        val exportMimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        every { mockFiles.export(fileId, exportMimeType) } returns mockExport
        every { mockExport.executeMediaAndDownloadTo(any<OutputStream>()) } just Runs

        val success = driveServiceHelper.downloadFile(fileId, destPath)
        
        assertTrue(success)
        verify { mockFiles.export(fileId, exportMimeType) }
        verify(exactly = 0) { mockGet.executeMediaAndDownloadTo(any()) }
        
        File(destPath).delete()
        }
    }

    @Test
    fun `downloadFile uses get alt=media for normal files`() {
        runBlocking {
            val fileId = "normal-file-id"
        val destPath = File.createTempFile("test", ".txt").absolutePath
        
        val mockDriveFile = DriveFile().apply {
            setId(fileId)
            setMimeType("text/plain")
            setSize(100L)
        }
        
        every { mockFiles.get(fileId) } returns mockGet
        every { mockGet.setFields(any()) } returns mockGet
        every { mockGet.execute() } returns mockDriveFile
        every { mockGet.executeMediaAndDownloadTo(any<OutputStream>()) } just Runs

        val success = driveServiceHelper.downloadFile(fileId, destPath)
        
        assertTrue(success)
        verify { mockGet.executeMediaAndDownloadTo(any()) }
        verify(exactly = 0) { mockFiles.export(any(), any()) }
        
        File(destPath).delete()
        }
    }
}
