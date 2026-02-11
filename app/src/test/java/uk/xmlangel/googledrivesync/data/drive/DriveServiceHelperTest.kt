package uk.xmlangel.googledrivesync.data.drive

import android.content.Context
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.google.api.services.drive.Drive
import android.util.Log
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.drive.model.FileList
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

    @MockK
    lateinit var mockList: Drive.Files.List

    private lateinit var driveServiceHelper: DriveServiceHelper

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        driveServiceHelper = DriveServiceHelper(mockContext)
        // We'll need a way to inject the mockDrive. 
        // For now, let's assume we'll add a setter or modify the constructor.
        driveServiceHelper.setDriveServiceForTest(mockDrive)
        every { mockDrive.files() } returns mockFiles
        
        mockkStatic(Log::class)
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.v(any<String>(), any<String>()) } returns 0
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

    @Test
    fun `getFile retries on SocketTimeoutException and eventually succeeds`() {
        runBlocking {
            val fileId = "retry-id"
            val mockDriveFile = DriveFile().apply {
                setId(fileId)
                setName("test.txt")
                setMimeType("text/plain")
            }

            every { mockFiles.get(fileId) } returns mockGet
            every { mockGet.setFields(any()) } returns mockGet
            
            // Fail twice with timeout, succeed on 3rd attempt
            val iterator = listOf<() -> DriveFile>(
                { throw java.net.SocketTimeoutException("Timeout 1") },
                { throw java.net.SocketTimeoutException("Timeout 2") },
                { mockDriveFile }
            ).iterator()
            every { mockGet.execute() } answers { iterator.next().invoke() }

            val result = driveServiceHelper.getFile(fileId)

            assertNotNull(result)
            assertEquals("test.txt", result?.name)
            
            // Should have called execute 3 times
            verify(exactly = 3) { mockGet.execute() }
        }
    }

    @Test
    fun `getFile fails after max retries`() {
        runBlocking {
            val fileId = "fail-id"

            every { mockFiles.get(fileId) } returns mockGet
            every { mockGet.setFields(any()) } returns mockGet
            
            // Fail 5 times with timeout
            every { mockGet.execute() } throws java.net.SocketTimeoutException("Timeout")

            val result = driveServiceHelper.getFile(fileId)

            // DriveServiceHelper.getFile returns null on Exception (not rethrowing)
            // Wait, looking at getFile implementation:
            // catch (e: Exception) { null }
            // But runWithRetry throws the last exception. 
            // So getFile will catch that thrown exception and return null.
            assertNull(result)
            
            // Should have called execute 5 times
            verify(exactly = 5) { mockGet.execute() }
        }
    }

    @Test
    fun `findFile escapes single quote and backslash in query`() = runBlocking {
        val fileName = "O'Reilly\\notes.txt"
        val parentId = "parent-id"

        every { mockFiles.list() } returns mockList
        every { mockList.setQ(any()) } returns mockList
        every { mockList.setSpaces(any()) } returns mockList
        every { mockList.setFields(any()) } returns mockList
        every { mockList.setPageSize(any()) } returns mockList
        every { mockList.execute() } returns FileList().apply {
            files = listOf(
                DriveFile().apply {
                    id = "file-id"
                    name = fileName
                    mimeType = "text/plain"
                    setSize(1L)
                    parents = listOf(parentId)
                }
            )
        }

        val querySlot = slot<String>()
        every { mockList.setQ(capture(querySlot)) } returns mockList

        val result = driveServiceHelper.findFile(fileName, parentId)

        assertNotNull(result)
        assertEquals("file-id", result?.id)
        assertTrue(querySlot.captured.contains("O\\'Reilly\\\\notes.txt"))
    }
}
