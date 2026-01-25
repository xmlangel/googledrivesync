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

    @MockK
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
}
