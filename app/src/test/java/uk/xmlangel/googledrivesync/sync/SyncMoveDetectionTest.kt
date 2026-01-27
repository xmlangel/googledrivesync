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
import uk.xmlangel.googledrivesync.data.drive.DriveItem
import uk.xmlangel.googledrivesync.data.drive.DriveServiceHelper
import uk.xmlangel.googledrivesync.data.local.*
import uk.xmlangel.googledrivesync.data.model.SyncStatus
import uk.xmlangel.googledrivesync.util.SyncLogger
import java.io.File
import java.util.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SyncMoveDetectionTest {

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
    fun `syncDirectoryRecursive detects cross-folder move from Drive`() = runBlocking {
        println("Testing cross-folder move detection...")
        
        val folderAId = "folder-a-id"
        val folderBId = "folder-b-id"
        val driveFileId = "drive-file-id"
        
        val folderA = SyncFolderEntity(folderAId, "acc", "test@test.com", File(context.cacheDir, "A").also { it.mkdir() }.absolutePath, "drive-a", "Drive A")
        
        // 1. File originally in Folder A
        val oldLocalPath = File(folderA.localPath, "test.txt").absolutePath
        File(oldLocalPath).writeText("content")
        
        val existingItem = SyncItemEntity(
            id = "item-id",
            syncFolderId = folderAId,
            accountId = "acc",
            accountEmail = "test@test.com",
            localPath = oldLocalPath,
            driveFileId = driveFileId,
            fileName = "test.txt",
            mimeType = "text/plain",
            localModifiedAt = 1000L,
            driveModifiedAt = 1000L,
            localSize = 7L,
            driveSize = 7L,
            status = SyncStatus.SYNCED
        )

        // 2. Syncing Folder B where the file is now located on Drive
        val folderBPath = File(context.cacheDir, "B").also { it.mkdir() }.absolutePath
        val driveItemInB = DriveItem(driveFileId, "test.txt", "text/plain", 1000L, 7L, "md5hash", listOf("drive-b"), false)
        
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(any()) } returns null
        coEvery { mockSyncItemDao.getSyncItemByDriveId(driveFileId) } returns existingItem
        coEvery { mockSyncItemDao.updateSyncItem(any()) } just Runs
        
        // Trigger Recursive Sync for Folder B
        // Note: We are mocking the internal call structure to verify logic
        val localItems = emptyList<File>()
        val driveItems = listOf(driveItemInB)
        
        // Call internal recursive sync method via reflection or just call syncFolder if we mock enough
        coEvery { mockSyncFolderDao.getSyncFolderById(folderBId) } returns SyncFolderEntity(folderBId, "acc", "test@test.com", folderBPath, "drive-b", "Drive B")
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockDriveHelper.listAllFiles("drive-b") } returns driveItems
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs

        syncManager.syncFolder(folderBId)

        // Verify local file was moved from A to B
        val newLocalPath = File(folderBPath, "test.txt")
        assertTrue("File should have been moved to Folder B", newLocalPath.exists())
        assertFalse("File should no longer be in Folder A", File(oldLocalPath).exists())
        
        // Verify DB update
        coVerify { 
            mockSyncItemDao.updateSyncItem(match { 
                it.driveFileId == driveFileId && it.localPath == newLocalPath.absolutePath 
            }) 
        }
    }

    @Test
    fun `syncDirectoryRecursive avoids deletion if file still exists on Drive at different location`() = runBlocking {
        println("Testing cautious deletion logic...")
        
        val folderAId = "folder-a-id"
        val driveFileId = "drive-file-id"
        val folderAPath = File(context.cacheDir, "A_delete").also { it.mkdir() }.absolutePath
        val localFile = File(folderAPath, "exists.txt").also { it.writeText("content") }
        
        val folderA = SyncFolderEntity(folderAId, "acc", "test@test.com", folderAPath, "drive-a", "Drive A")
        
        val existingItem = SyncItemEntity(
            id = "item-id",
            syncFolderId = folderAId,
            accountId = "acc",
            accountEmail = "test@test.com",
            localPath = localFile.absolutePath,
            driveFileId = driveFileId,
            fileName = "exists.txt",
            mimeType = "text/plain",
            localModifiedAt = 1000L,
            driveModifiedAt = 1000L,
            localSize = 7L,
            driveSize = 7L,
            status = SyncStatus.SYNCED
        )

        // Drive list for folder A is empty (file moved away)
        val driveItems = emptyList<DriveItem>()
        val localItems = listOf(localFile)
        
        // Mock getFileMetadata showing it's still alive but in Folder B
        val movedMeta = DriveItem(driveFileId, "exists.txt", "text/plain", 1000L, 7L, "hash", listOf("drive-b"), false)
        coEvery { mockDriveHelper.getFileMetadata(driveFileId) } returns movedMeta
        
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        
        // Run sync
        coEvery { mockSyncFolderDao.getSyncFolderById(folderAId) } returns folderA
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockDriveHelper.listAllFiles("drive-a") } returns driveItems
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs

        syncManager.syncFolder(folderAId)

        // Verify local file was NOT deleted because it was moved on Drive
        assertTrue("Local file should still exist as it might be moved", localFile.exists())
        coVerify(exactly = 0) { mockSyncItemDao.deleteSyncItem(any()) }
    }
}
