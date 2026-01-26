package uk.xmlangel.googledrivesync.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    @Test
    fun `syncFolder updates syncProgress with correct currentIndex and totalFiles`() = runBlocking {
        println("Testing syncFolder updates syncProgress correctly...")
        val folderId = "test-folder-id"
        // Use a real-looking local path
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Mock 1 file in Drive
        val driveItems = listOf(
            uk.xmlangel.googledrivesync.data.drive.DriveItem("id1", "file1.txt", "text/plain", 1000L, 100L, emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        coEvery { mockDriveHelper.downloadFile(any(), any()) } returns true
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(any()) } returns null
        coEvery { mockSyncItemDao.insertSyncItem(any()) } just Runs

        val progressUpdates = mutableListOf<SyncProgress>()
        
        // Launch collection in background
        val collectJob = launch(Dispatchers.Unconfined) {
            syncManager.syncProgress.collect { progress ->
                if (progress != null) {
                    progressUpdates.add(progress)
                }
            }
        }

        syncManager.syncFolder(folderId)
        
        // No need for delay with Dispatchers.Unconfined and completion of syncFolder
        collectJob.cancel()
        
        // Verify we got the progress update
        assertFalse("Should have received progress updates", progressUpdates.isEmpty())
        val lastProgress = progressUpdates.last()
        assertEquals("Total files should be 1", 1, lastProgress.totalFiles)
        assertEquals("Current index should be 1", 1, lastProgress.currentIndex)
        println("  Verified syncProgress updates: totalFiles=${lastProgress.totalFiles}, currentIndex=${lastProgress.currentIndex}")
    }

    @Test
    fun `syncFolder skips when no changes`() = runBlocking {
        println("Testing syncFolder skips unchanged files...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Mock a file that exists locally and on Drive, and is already tracked in DB
        val localFile = File(context.cacheDir, "skip_test.txt")
        localFile.writeText("content")
        val fixedTime = 1000000L
        localFile.setLastModified(fixedTime)
        
        val driveItems = listOf(
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-1", localFile.name, "text/plain", fixedTime + 5000, 100L, emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        
        val existingItem = SyncItemEntity(
            id = "item-id",
            syncFolderId = folderId,
            accountId = "acc-id",
            accountEmail = "test@example.com",
            localPath = localFile.absolutePath,
            driveFileId = "drive-id-1",
            fileName = localFile.name,
            mimeType = "text/plain",
            localModifiedAt = localFile.lastModified(),
            driveModifiedAt = driveItems[0].modifiedTime,
            localSize = localFile.length(),
            driveSize = 100L,
            status = SyncStatus.SYNCED,
            lastSyncedAt = fixedTime
        )
        
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        
        val result = syncManager.syncFolder(folderId)
        
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals("Should have 1 skipped file", 1, success.skipped)
        assertEquals("Should have 0 uploaded files", 0, success.uploaded)
        assertEquals("Should have 0 downloaded files", 0, success.downloaded)
        
        // Verify no Drive API updates/downloads were called
        coVerify(exactly = 0) { mockDriveHelper.updateFile(any(), any(), any()) }
        coVerify(exactly = 0) { mockDriveHelper.downloadFile(any(), any()) }
        
        println("  Verified that syncFolder skipped the unchanged file correctly.")
    }

    @Test
    fun `syncFolder links existing files without sync`() = runBlocking {
        println("Testing syncFolder linking identical existing files (v1.0.8 logic)...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Mock a file that exists locally and on Drive, but NOT tracked in DB
        val localFile = File(context.cacheDir, "link_test.txt")
        localFile.writeText("content")
        val fixedTime = 2000000L
        localFile.setLastModified(fixedTime)
        
        val driveItems = listOf(
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-link", localFile.name, "text/plain", fixedTime + 100, localFile.length(), emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        
        // NOT in DB
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns null
        coEvery { mockSyncItemDao.insertSyncItem(any()) } just Runs
        
        val result = syncManager.syncFolder(folderId)
        
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals("Should have 1 skipped (linked) file", 1, success.skipped)
        assertEquals("Should have 0 uploaded files", 0, success.uploaded)
        
        // Verify linking occurred (trackSyncItem was called internally via the sync logic)
        coVerify { mockSyncItemDao.insertSyncItem(match { 
            it.localPath == localFile.absolutePath && it.driveFileId == "drive-id-link" 
        }) }
        
        // Verify no Drive API updates/downloads
        coVerify(exactly = 0) { mockDriveHelper.updateFile(any(), any(), any()) }
        coVerify(exactly = 0) { mockDriveHelper.downloadFile(any(), any()) }
        
        println("  Verified that syncFolder linked identical untracked files without syncing.")
    }

    @Test
    fun `syncFolder links by size even if timestamps differ greatly`() = runBlocking {
        println("Testing v1.0.9 size-based linking for new items...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Mock a file that exists locally and on Drive, but NOT tracked in DB
        // Sizes match but timestamps are very different (minutes apart)
        val localFile = File(context.cacheDir, "size_link_test.txt")
        localFile.writeText("content")
        localFile.setLastModified(1000000000L)
        
        val driveItems = listOf(
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-size", localFile.name, "text/plain", 2000000000L, localFile.length(), emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns null
        coEvery { mockSyncItemDao.insertSyncItem(any()) } just Runs
        
        val result = syncManager.syncFolder(folderId)
        
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals("Should have 1 skipped (linked) file due to size match", 1, success.skipped)
        
        // Verify linking occurred based on size
        coVerify { mockSyncItemDao.insertSyncItem(match { it.driveSize == localFile.length() }) }
        println("  Verified that syncFolder linked files with matching size despite large timestamp difference.")
    }

    @Test
    fun `syncFolder swallows metadata update if sizes match`() = runBlocking {
        println("Testing v1.0.9 metadata swallowing for existing items...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Existing item in DB
        val localFile = File(context.cacheDir, "swallow_test.txt")
        localFile.writeText("content")
        localFile.setLastModified(3000000000L) // Newer than DB
        
        val driveItems = listOf(
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-swallow", localFile.name, "text/plain", 4000000000L, localFile.length(), emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        
        val existingItem = SyncItemEntity(
            id = "item-id",
            syncFolderId = folderId,
            accountId = "acc-id",
            accountEmail = "test@example.com",
            localPath = localFile.absolutePath,
            driveFileId = "drive-id-swallow",
            fileName = localFile.name,
            mimeType = "text/plain",
            localModifiedAt = 2000000000L, // Older than current local
            driveModifiedAt = 2000000000L, // Older than current drive
            localSize = localFile.length(),
            driveSize = localFile.length(),
            status = SyncStatus.SYNCED,
            lastSyncedAt = 2000000000L
        )
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        coEvery { mockSyncItemDao.updateSyncItem(any()) } just Runs
        
        val result = syncManager.syncFolder(folderId)
        
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals("Should have 1 skipped (swallowed) file", 1, success.skipped)
        
        // Verify metadata update happened in DB but NO Drive API calls
        coVerify { mockSyncItemDao.updateSyncItem(any()) }
        coVerify(exactly = 0) { mockDriveHelper.updateFile(any(), any(), any()) }
        coVerify(exactly = 0) { mockDriveHelper.downloadFile(any(), any()) }
        
        println("  Verified that syncFolder updated metadata only when sizes matched (swallowing).")
    }
}
