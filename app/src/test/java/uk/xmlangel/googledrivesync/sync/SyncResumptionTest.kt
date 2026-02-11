package uk.xmlangel.googledrivesync.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import uk.xmlangel.googledrivesync.data.drive.*
import uk.xmlangel.googledrivesync.data.local.*
import uk.xmlangel.googledrivesync.util.SyncLogger
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncResumptionTest {

    @MockK lateinit var driveHelper: DriveServiceHelper
    @MockK lateinit var syncFolderDao: SyncFolderDao
    @MockK lateinit var syncItemDao: SyncItemDao
    @MockK lateinit var historyDao: SyncHistoryDao
    @MockK lateinit var dirtyLocalDao: DirtyLocalDao
    @MockK lateinit var preferences: SyncPreferences
    @MockK lateinit var logger: SyncLogger

    private lateinit var syncManager: SyncManager
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        
        syncManager = SyncManager(
            context = context,
            driveHelper = driveHelper,
            database = mockk(),
            syncFolderDao = syncFolderDao,
            syncItemDao = syncItemDao,
            historyDao = historyDao,
            dirtyLocalDao = dirtyLocalDao,
            syncPreferences = preferences,
            logger = logger
        )

        every { preferences.defaultSyncDirection } returns uk.xmlangel.googledrivesync.data.model.SyncDirection.BIDIRECTIONAL
        every { preferences.defaultConflictResolution } returns ConflictResolution.USE_LOCAL
        every { preferences.autoUploadEnabled } returns true
        every { preferences.userExcludedPaths } returns emptySet()
        coEvery { syncItemDao.getSyncItemsByFolder(any()) } returns flowOf(
            listOf(
                SyncItemEntity(
                    id = "tracked-item",
                    syncFolderId = "tracked-folder",
                    accountId = "acc_1",
                    accountEmail = "test@example.com",
                    localPath = File(context.cacheDir, "tracked.txt").absolutePath,
                    driveFileId = "tracked-drive-id",
                    fileName = "tracked.txt",
                    mimeType = "text/plain",
                    localModifiedAt = 1L,
                    driveModifiedAt = 1L,
                    localSize = 1L,
                    driveSize = 1L
                )
            )
        )
        coEvery { dirtyLocalDao.countDirtyItemsByFolderAfter(any(), any()) } returns 0
    }

    @Test
    fun `syncChangesInternal updates page token after each batch`() {
        runBlocking {
            val folder = SyncFolderEntity(
                id = "1",
                accountId = "acc_1",
                accountEmail = "test@example.com",
                localPath = File(context.cacheDir, "sync-resume-test-1").apply { mkdirs() }.absolutePath,
                driveFolderId = "drive_id",
                driveFolderName = "Test",
                lastStartPageToken = "token1"
            )
            
            val driveItem1 = DriveItem("file1", "File 1", "mime", 1000L, 100L, null, listOf("drive_id"), false)
            val driveItem2 = DriveItem("file2", "File 2", "mime", 2000L, 200L, null, listOf("drive_id"), false)

            val page1 = DriveChangeResult(
                changes = listOf(DriveChange("file1", false, driveItem1)),
                nextPageToken = "token2",
                newStartPageToken = null
            )
            val page2 = DriveChangeResult(
                changes = listOf(DriveChange("file2", false, driveItem2)),
                nextPageToken = null,
                newStartPageToken = "token_final"
            )
            
            coEvery { driveHelper.initializeDriveService(any()) } returns true
            coEvery { driveHelper.getChanges("token1") } returns page1
            coEvery { driveHelper.getChanges("token2") } returns page2
            coEvery { syncItemDao.getSyncItemByDriveId(any()) } returns null
            coEvery { driveHelper.downloadFile(any(), any()) } returns true
            
            coEvery { syncFolderDao.getSyncFolderById("1") } returns folder
            coEvery { dirtyLocalDao.getDirtyItemsByFolder("1") } returns emptyList()
            coEvery { driveHelper.getStartPageToken() } returns "token_new_start"
            coEvery { historyDao.insertHistory(any()) } returns 101L
            
            syncManager.syncFolder("1")
            
            // Verify updatePageToken was called correctly
            coVerify(exactly = 1) { syncFolderDao.updatePageToken("1", "token2") }
            coVerify(exactly = 1) { syncFolderDao.updatePageToken("1", "token_final") }
        }
    }

    @Test
    fun `syncFolder reports progress during drive file listing`() {
        runBlocking {
            val folder = SyncFolderEntity(
                id = "1",
                accountId = "acc_1",
                accountEmail = "test@example.com",
                localPath = File(context.cacheDir, "sync-resume-test-2").apply { mkdirs() }.absolutePath,
                driveFolderId = "drive_id",
                driveFolderName = "Test",
                lastSyncedAt = System.currentTimeMillis() - 7200000 // 2 hours ago
            )
            
            coEvery { driveHelper.initializeDriveService(any()) } returns true
            coEvery { syncFolderDao.getSyncFolderById("1") } returns folder
            coEvery { dirtyLocalDao.getDirtyItemsByFolder("1") } returns emptyList()
            coEvery { historyDao.insertHistory(any()) } returns 102L
            
            // Mock listAllFiles to invoke progress callback
            coEvery { driveHelper.listAllFiles(any(), any()) } answers {
                val callback = secondArg<((Int) -> Unit)?>()
                callback?.invoke(50)
                callback?.invoke(100)
                emptyList()
            }
            coEvery { driveHelper.getStartPageToken() } returns "token_start"
            
            syncManager.syncFolder("1")
        }
    }
}
