package uk.xmlangel.googledrivesync.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xmlangel.googledrivesync.data.drive.DriveServiceHelper
import uk.xmlangel.googledrivesync.data.local.*
import uk.xmlangel.googledrivesync.data.model.SyncStatus
import uk.xmlangel.googledrivesync.util.SyncLogger
import java.io.File
import java.util.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SyncManagerTest {

    private lateinit var context: Context
    
    @MockK
    lateinit var mockDriveHelper: DriveServiceHelper

    @MockK
    lateinit var mockDatabase: SyncDatabase

    @MockK
    lateinit var mockSyncFolderDao: SyncFolderDao

    @MockK
    lateinit var mockSyncItemDao: SyncItemDao

    @MockK
    lateinit var mockHistoryDao: SyncHistoryDao

    @MockK
    lateinit var mockSyncPreferences: SyncPreferences

    @MockK(relaxUnitFun = true)
    lateinit var mockLogger: SyncLogger

    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        context = ApplicationProvider.getApplicationContext()
        
        // Setup SyncManager with mocked dependencies
        syncManager = SyncManager(
            context = context,
            driveHelper = mockDriveHelper,
            database = mockDatabase,
            syncFolderDao = mockSyncFolderDao,
            syncItemDao = mockSyncItemDao,
            historyDao = mockHistoryDao,
            syncPreferences = mockSyncPreferences,
            logger = mockLogger
        )
    }

    @Test
    fun `initialize returns true when drive helper initializes successfully`() {
        println("Testing SyncManager initialization...")
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        val result = syncManager.initialize()
        assertTrue(result)
        println("  SyncManager initialized successfully as expected.")
    }

    @Test
    fun `resolveConflict USE_LOCAL updates status and calls driveHelper`() = runBlocking {
        println("Testing conflict resolution with USE_LOCAL policy...")
        val syncItem = SyncItemEntity(
            id = "test-id",
            syncFolderId = "folder-id",
            accountId = "account-id",
            accountEmail = "test@example.com",
            localPath = "/tmp/test.txt",
            driveFileId = "drive-id",
            fileName = "test.txt",
            mimeType = "text/plain",
            localModifiedAt = 1000L,
            driveModifiedAt = 1000L,
            localSize = 100L,
            driveSize = 100L,
            status = SyncStatus.CONFLICT
        )
        val conflict = SyncConflict(syncItem, "test.txt", 1000L, 100L, "test.txt", 1000L, 100L)
        println("  Created conflict situation for test.txt (CONFLICT status)")
        
        val mockDriveItem = mockk<uk.xmlangel.googledrivesync.data.drive.DriveItem>()
        coEvery { mockDriveHelper.updateFile(any(), any(), any()) } returns mockDriveItem
        coEvery { mockSyncItemDao.updateItemStatus(any(), any()) } just Runs
        
        val result = syncManager.resolveConflict(conflict, ConflictResolution.USE_LOCAL)
        
        assertTrue(result)
        println("  resolveConflict(USE_LOCAL) returned true")
        coVerify { mockDriveHelper.updateFile("drive-id", "/tmp/test.txt", any()) }
        println("  Verified updatedFile was called on Drive (uploading local).")
        coVerify { mockSyncItemDao.updateItemStatus("test-id", SyncStatus.SYNCED) }
        println("  Verified local DB status was updated to SYNCED.")
    }

    @Test
    fun `resolveConflict USE_DRIVE updates status and calls driveHelper`() = runBlocking {
        println("Testing conflict resolution with USE_DRIVE policy...")
        val syncItem = SyncItemEntity(
            id = "test-id",
            syncFolderId = "folder-id",
            accountId = "account-id",
            accountEmail = "test@example.com",
            localPath = "/tmp/test.txt",
            driveFileId = "drive-id",
            fileName = "test.txt",
            mimeType = "text/plain",
            localModifiedAt = 1000L,
            driveModifiedAt = 1000L,
            localSize = 100L,
            driveSize = 100L,
            status = SyncStatus.CONFLICT
        )
        val conflict = SyncConflict(syncItem, "test.txt", 1000L, 100L, "test.txt", 1000L, 100L)
        println("  Created conflict situation for test.txt (CONFLICT status)")
        
        coEvery { mockDriveHelper.downloadFile(any(), any()) } returns true
        coEvery { mockSyncItemDao.updateItemStatus(any(), any()) } just Runs
        
        val result = syncManager.resolveConflict(conflict, ConflictResolution.USE_DRIVE)
        
        assertTrue(result)
        println("  resolveConflict(USE_DRIVE) returned true")
        coVerify { mockDriveHelper.downloadFile("drive-id", "/tmp/test.txt") }
        println("  Verified downloadFile was called from Drive (overwriting local).")
        coVerify { mockSyncItemDao.updateItemStatus("test-id", SyncStatus.SYNCED) }
        println("  Verified local DB status was updated to SYNCED.")
    }

    @Test
    fun `syncFolder updates lastSyncResult on success`() = runBlocking {
        println("Testing syncFolder updates lastSyncResult on success...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(
            id = folderId,
            accountId = "account-id",
            accountEmail = "test@example.com",
            localPath = "/tmp/local",
            driveFolderId = "drive-id",
            driveFolderName = "Drive Folder"
        )
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        coEvery { mockDriveHelper.listAllFiles(any()) } returns emptyList()
        
        // Use spy or just observe the flow
        syncManager.syncFolder(folderId)
        
        val result = syncManager.lastSyncResult.value
        if (result is SyncResult.Error) {
            println("  Sync failed with error: ${result.message}")
            result.exception?.printStackTrace()
        }
        assertTrue("Expected Success but got ${result?.javaClass?.simpleName}", result is SyncResult.Success)
        println("  Verified lastSyncResult is Success after successful sync.")
    }

    @Test
    fun `dismissLastResult clears lastSyncResult`() = runBlocking {
        println("Testing dismissLastResult clears the state...")
        // Set an initial result (we need access to internal state or trigger a sync)
        // Since we can't easily set the private field, we trigger a sync that fails
        coEvery { mockSyncFolderDao.getSyncFolderById(any()) } returns null
        
        syncManager.syncFolder("non-existent")
        assertNotNull("lastSyncResult should not be null after sync failure", syncManager.lastSyncResult.value)
        
        syncManager.dismissLastResult()
        assertNull(syncManager.lastSyncResult.value)
        println("  Verified lastSyncResult is null after dismissLastResult().")
    }

    @Test
    fun `syncFolder logs with ERROR prefix on failure`() = runBlocking {
        println("Testing error logging with [ERROR] prefix in syncFolder...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(
            id = folderId,
            accountId = "account-id",
            accountEmail = "test@example.com",
            localPath = "/tmp/local",
            driveFolderId = "drive-id",
            driveFolderName = "Drive Folder"
        )
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        
        // Mock a failure during file listing to trigger the catch block in syncFolder
        coEvery { mockDriveHelper.listAllFiles(any()) } throws Exception("List files failed")
        
        syncManager.syncFolder(folderId)
        
        // Verify that logger was called with some error message containing [ERROR]
        verify { mockLogger.log(match { it.contains("[ERROR]") }, any()) }
        println("  Verified [ERROR] prefix was logged on failure.")
    }
}
