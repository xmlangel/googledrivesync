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
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.util.SyncLogger
import uk.xmlangel.googledrivesync.util.TestMetadata
import uk.xmlangel.googledrivesync.util.TestMetadataRule
import org.junit.Rule
import java.io.File
import java.util.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SyncManagerTest {

    private lateinit var context: Context
    
    @MockK
    lateinit var mockDriveHelper: DriveServiceHelper

    @MockK
    lateinit var mockSyncFolderDao: SyncFolderDao

    @MockK
    lateinit var mockSyncItemDao: SyncItemDao

    @MockK
    lateinit var mockHistoryDao: SyncHistoryDao

    @MockK
    lateinit var mockDatabase: SyncDatabase

    @MockK
    lateinit var mockDirtyLocalDao: DirtyLocalDao

    private lateinit var mockSyncPreferences: SyncPreferences
    
    private lateinit var mockLogger: SyncLogger

    private lateinit var syncManager: SyncManager

    @get:Rule
    val metadataRule = TestMetadataRule()

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        context = ApplicationProvider.getApplicationContext()
        
        mockSyncFolderDao = mockk(relaxed = true)
        coEvery { mockSyncFolderDao.getSyncFolderByDriveId(any()) } returns null
        mockSyncItemDao = mockk(relaxed = true)
        mockHistoryDao = mockk(relaxed = true)
        mockDriveHelper = mockk(relaxed = true)
        mockDatabase = mockk(relaxed = true)
        mockDirtyLocalDao = mockk(relaxed = true)
        every { mockDatabase.dirtyLocalDao() } returns mockDirtyLocalDao
        mockSyncPreferences = mockk(relaxed = true)
        
        // Default behaviors
        every { mockSyncPreferences.defaultSyncDirection } returns SyncDirection.BIDIRECTIONAL
        every { mockSyncPreferences.defaultConflictResolution } returns ConflictResolution.USE_LOCAL
        
        mockLogger = mockk(relaxed = true)
        // Ensure logger logs to stdout in tests for visibility
        every { mockLogger.log(any(), any()) } answers {
            println("Logger: ${it.invocation.args[0]}")
        }
        
        syncManager = SyncManager(
            context = context,
            syncFolderDao = mockSyncFolderDao,
            syncItemDao = mockSyncItemDao,
            historyDao = mockHistoryDao,
            driveHelper = mockDriveHelper,
            database = mockDatabase,
            dirtyLocalDao = mockDirtyLocalDao,
            syncPreferences = mockSyncPreferences,
            logger = mockLogger
        )
        
        mockkObject(uk.xmlangel.googledrivesync.util.FileUtils)
        every { uk.xmlangel.googledrivesync.util.FileUtils.calculateMd5(any()) } returns "test-md5"
    }

    @Test
    @TestMetadata(
        description = "서비스 초기화 검증",
        step = "1. DriveServiceHelper 목킹 | 2. initialize() 호출 | 3. 반환값 확인",
        expected = "DriveServiceHelper가 성공하면 true를 반환한다"
    )
    fun `initialize returns true when drive helper initializes successfully`() {
        println("설명: 서비스 초기화 검증 | 예상결과: true | 실제결과: 준비 중...")
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        val result = syncManager.initialize()
        println("설명: 서비스 초기화 검증 | 예상결과: true | 실제결과: $result")
        assertTrue("서비스 초기화가 실패했습니다. (예상: true, 실제: $result)", result)
    }

    @Test
    @TestMetadata(
        description = "로컬 우선 해결 정책 검증",
        step = "1. 충돌 항목 생성 | 2. resolveConflict(USE_LOCAL) 호출 | 3. 드라이브 업데이트 및 DB 상태 확인",
        expected = "드라이브 파일이 업데이트되고 DB 상태가 SYNCED로 변경되어야 함"
    )
    fun `resolveConflict USE_LOCAL updates status and calls driveHelper`() = runBlocking {
        println("설명: 로컬 우선 해결 정책 검증 | 예상결과: true | 실제결과: 시작")
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
        
        val mockDriveItem = mockk<uk.xmlangel.googledrivesync.data.drive.DriveItem>()
        coEvery { mockDriveHelper.updateFile(any(), any(), any()) } returns mockDriveItem
        coEvery { mockSyncItemDao.updateItemStatus(any(), any()) } just Runs
        
        val result = syncManager.resolveConflict(conflict, ConflictResolution.USE_LOCAL)
        
        println("설명: 로컬 우선 해결 정책 검증 | 예상결과: true | 실제결과: $result")
        assertTrue("충돌 해결 결과가 false입니다.", result)
        coVerify { mockDriveHelper.updateFile("drive-id", "/tmp/test.txt", any()) }
        coVerify { mockSyncItemDao.updateItemStatus("test-id", SyncStatus.SYNCED) }
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
    fun `resolveConflict KEEP_BOTH renames local and downloads drive version`() = runBlocking {
        println("Testing conflict resolution with KEEP_BOTH policy...")
        val localDir = context.cacheDir
        val localFile = File(localDir, "conflict.txt")
        localFile.writeText("local content")
        
        val syncItem = SyncItemEntity(
            id = "test-id",
            syncFolderId = "folder-id",
            accountId = "account-id",
            accountEmail = "test@example.com",
            localPath = localFile.absolutePath,
            driveFileId = "drive-id",
            fileName = "conflict.txt",
            mimeType = "text/plain",
            localModifiedAt = 1000L,
            driveModifiedAt = 1000L,
            localSize = 13L,
            driveSize = 13L,
            status = SyncStatus.CONFLICT
        )
        val conflict = SyncConflict(syncItem, "conflict.txt", 1000L, 13L, "conflict.txt", 1000L, 13L)
        
        coEvery { mockDriveHelper.downloadFile(any(), any()) } answers {
            File(it.invocation.args[1] as String).writeText("drive content")
            true
        }
        coEvery { mockSyncItemDao.updateItemStatus(any(), any()) } just Runs
        
        val result = syncManager.resolveConflict(conflict, ConflictResolution.KEEP_BOTH)
        
        assertTrue(result)
        
        val renamedFile = File(localDir, "conflict_local.txt")
        assertTrue("Local file should have been renamed to _local.txt", renamedFile.exists())
        assertEquals("Renamed file should have local content", "local content", renamedFile.readText())
        
        assertTrue("Original path should now have drive content", localFile.exists())
        assertEquals("Original path should have downloaded content", "drive content", localFile.readText())
        
        coVerify { mockSyncItemDao.updateItemStatus("test-id", SyncStatus.SYNCED) }
        println("  Verified KEEP_BOTH: local renamed, drive downloaded, DB updated.")
        
        // Cleanup
        localFile.delete()
        renamedFile.delete()
        Unit
    }

    @Test
    fun `syncFolder adds to conflicts list when no default policy is set`() = runBlocking {
        println("Testing 'Ask Every Time' behavior (null policy)...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Null policy means "Ask Every Time"
        every { mockSyncPreferences.defaultConflictResolution } returns null
        
        // Setup a conflict: Local and Drive both updated since last sync
        val localFile = File(context.cacheDir, "conflict_ask.txt")
        localFile.writeText("local change")
        localFile.setLastModified(5000L)
        
        val driveItems = listOf(
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-ask", "conflict_ask.txt", "text/plain", 6000L, 100L, "hash", listOf("drive-id"), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        
        val existingItem = SyncItemEntity(
            id = "item-id", syncFolderId = folderId, accountId = "acc-id", accountEmail = "test@example.com",
            localPath = localFile.absolutePath, driveFileId = "drive-id-ask", fileName = "conflict_ask.txt",
            mimeType = "text/plain", localModifiedAt = 1000L, driveModifiedAt = 1000L, // Last sync was at 1000L
            localSize = 5L, driveSize = 5L, status = SyncStatus.SYNCED
        )
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        
        val result = syncManager.syncFolder(folderId)
        
        assertTrue(result is SyncResult.Conflict)
        val syncConflictResult = result as SyncResult.Conflict
        assertEquals("Should have 1 conflict in the result", 1, syncConflictResult.conflicts.size)
        assertEquals("Conflict file name should match", "conflict_ask.txt", syncConflictResult.conflicts[0].localFileName)
        
        // Verify state flows were updated
        assertEquals("Pending conflicts list should have 1 item", 1, syncManager.pendingConflicts.value.size)
        
        println("  Verified that null policy results in a Conflict result for user interaction.")
        localFile.delete()
        Unit
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
            uk.xmlangel.googledrivesync.data.drive.DriveItem("id1", "file1.txt", "text/plain", 1000L, 100L, null, emptyList(), false)
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
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-1", localFile.name, "text/plain", fixedTime + 5000, 100L, null, emptyList(), false)
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
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-link", localFile.name, "text/plain", fixedTime + 100, localFile.length(), "test-md5", emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        
        // NOT in DB
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns null
        coEvery { mockSyncItemDao.getSyncItemByDriveId("drive-id-link") } returns null
        coEvery { mockSyncItemDao.insertSyncItem(any()) } just Runs
        
        val result = syncManager.syncFolder(folderId)
        assertTrue("Expected Success but got $result", result is SyncResult.Success)
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
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-size", localFile.name, "text/plain", 2000000000L, localFile.length(), "test-md5", emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns null
        coEvery { mockSyncItemDao.getSyncItemByDriveId("drive-id-size") } returns null
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
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-swallow", localFile.name, "text/plain", 4000000000L, localFile.length(), "test-md5", emptyList(), false)
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

    @Test
    fun `syncFolder handles renames on Drive by renaming local file`() = runBlocking {
        println("Testing rename detection on Drive...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // 1. Existing file locally with old name
        val oldLocalFile = File(context.cacheDir, "old_name.txt")
        oldLocalFile.writeText("content")
        val fixedTime = 5000000L
        oldLocalFile.setLastModified(fixedTime)
        
        // 2. Drive has same ID but NEW name
        val driveItems = listOf(
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-rename", "new_name.txt", "text/plain", fixedTime, oldLocalFile.length(), null, emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        
        // 3. DB knows about the old name linked to the ID
        val existingItem = SyncItemEntity(
            id = "item-id",
            syncFolderId = folderId,
            accountId = "acc-id",
            accountEmail = "test@example.com",
            localPath = oldLocalFile.absolutePath,
            driveFileId = "drive-id-rename",
            fileName = "old_name.txt",
            mimeType = "text/plain",
            localModifiedAt = oldLocalFile.lastModified(),
            driveModifiedAt = fixedTime,
            localSize = oldLocalFile.length(),
            driveSize = oldLocalFile.length(),
            status = SyncStatus.SYNCED,
            lastSyncedAt = fixedTime
        )
        
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(oldLocalFile.absolutePath) } returns existingItem
        coEvery { mockSyncItemDao.getSyncItemByDriveId("drive-id-rename") } returns existingItem
        coEvery { mockSyncItemDao.updateSyncItem(any()) } just Runs
        
        val result = syncManager.syncFolder(folderId)
        
        assertTrue(result is SyncResult.Success)
        
        // Verify local file was renamed
        val newLocalFile = File(context.cacheDir, "new_name.txt")
        assertTrue("Local file should have been renamed to new_name.txt", newLocalFile.exists())
        
        // Cleanup
        newLocalFile.delete()
        
        // Verify DB was updated
        coVerify { mockSyncItemDao.updateSyncItem(match { it.fileName == "new_name.txt" && it.localPath == newLocalFile.absolutePath }) }
        println("  Verified that local file was renamed and DB updated when Drive name changed.")
    }

    @Test
    fun `syncFolder logs with ERROR prefix when folder not found`() {
        runBlocking {
            println("Testing error logging when folder is not found...")
            val folderId = "non-existent-id"
            
            coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns null
            
            syncManager.syncFolder(folderId)
            
            // Verify that logger was called with the specific error message containing [ERROR]
            verify { mockLogger.log(match { it.contains("[ERROR]") && it.contains("찾을 수 없음") }, any()) }
            println("  Verified [ERROR] prefix was logged when folder not found.")
        }
    }

    @Test
    fun testSyncFolderStashesWhenAutoUploadDisabled() {
        runBlocking {
            println("Testing stashing of new local files when autoUploadEnabled is false...")
            val folderId = "test-folder-id"
            val folder = SyncFolderEntity(
                folderId, "acc-id", "test@example.com", 
                context.cacheDir.absolutePath, "drive-id", "Drive"
            )
            
            coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
            every { mockDriveHelper.initializeDriveService(any()) } returns true
            coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
            coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
            coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
            
            // Set preference to false
            every { mockSyncPreferences.autoUploadEnabled } returns false
            
            val localFile = File(context.cacheDir, "stash_test.txt")
            localFile.writeText("content")
            
            coEvery { mockDriveHelper.listAllFiles(any()) } returns emptyList()
            coEvery { mockSyncItemDao.getSyncItemByLocalPath(any()) } returns null
            
            syncManager.syncFolder(folderId)
            
            val pendingUploads = syncManager.pendingUploads.value
            assertEquals("Should have 1 pending upload", 1, pendingUploads.size)
            coVerify(exactly = 0) { mockDriveHelper.uploadFile(any(), any(), any()) }
            
            println("  Verified stashing when autoUploadEnabled is false.")
            localFile.delete()
        }
    }

    @Test
    fun testSyncFolderUploadsWhenAutoUploadEnabled() {
        runBlocking {
            println("Testing immediate upload of new local files when autoUploadEnabled is true...")
            val folderId = "test-folder-id"
            val folder = SyncFolderEntity(
                folderId, "acc-id", "test@example.com", 
                context.cacheDir.absolutePath, "drive-id", "Drive"
            )
            
            coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
            every { mockDriveHelper.initializeDriveService(any()) } returns true
            coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
            coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
            coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
            
            // Set preference to true
            every { mockSyncPreferences.autoUploadEnabled } returns true
            
            val localFile = File(context.cacheDir, "auto_upload_test.txt")
            localFile.writeText("content")
            
            coEvery { mockDriveHelper.listAllFiles(any()) } returns emptyList()
            coEvery { mockSyncItemDao.getSyncItemByLocalPath(any()) } returns null
            
            val mockDriveItem = mockk<uk.xmlangel.googledrivesync.data.drive.DriveItem>()
            every { mockDriveItem.id } returns "new-drive-id"
            every { mockDriveItem.modifiedTime } returns 12345L
            every { mockDriveItem.size } returns 100L
            every { mockDriveItem.md5Checksum } returns "md5"
            coEvery { mockDriveHelper.uploadFile(any(), any(), any()) } returns mockDriveItem
            
            syncManager.syncFolder(folderId)
            
            // This test is expected to FAIL currently because we haven't implemented the pass logic yet
            // Wait, I should verify if it fails first.
            coVerify { mockDriveHelper.uploadFile(localFile.absolutePath, localFile.name, "drive-id") }
            val pendingUploads = syncManager.pendingUploads.value
            assertEquals("Should have 0 pending uploads as it should be immediate", 0, pendingUploads.size)
            
            println("  Verified immediate upload when autoUploadEnabled is true.")
            localFile.delete()
        }
    }

    @Test
    fun testSyncFolderHandlesServerSideMove() {
        runBlocking {
        println("Testing handling of server-side moves...")
        val folderId = "test-folder-id"
        val localDir = context.cacheDir
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", localDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Setup: File existed at old path locally
        val oldLocalFile = File(localDir, "old_location.txt")
        oldLocalFile.writeText("content")
        val fixedTime = 5000L
        oldLocalFile.setLastModified(fixedTime)
        
        // Drive item with same ID but NEW name/location in listing
        val driveItems = listOf(
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-move", "new_location.txt", "text/plain", fixedTime, oldLocalFile.length(), null, emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        
        // DB knows about the old link
        val existingItem = SyncItemEntity(
            id = "item-id", syncFolderId = folderId, accountId = "acc-id", accountEmail = "test@example.com",
            localPath = oldLocalFile.absolutePath, driveFileId = "drive-id-move", fileName = "old_location.txt",
            mimeType = "text/plain", localModifiedAt = fixedTime, driveModifiedAt = fixedTime,
            localSize = oldLocalFile.length(), driveSize = oldLocalFile.length(), status = SyncStatus.SYNCED
        )
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(oldLocalFile.absolutePath) } returns existingItem
        coEvery { mockSyncItemDao.getSyncItemByDriveId("drive-id-move") } returns existingItem
        coEvery { mockSyncItemDao.updateSyncItem(any()) } just Runs
        
        syncManager.syncFolder(folderId)
        
        val newLocalFile = File(localDir, "new_location.txt")
        assertTrue("Local file should have been renamed to new_location.txt", newLocalFile.exists())
        assertFalse("Old file should no longer exist", oldLocalFile.exists())
        
        coVerify { mockSyncItemDao.updateSyncItem(match { it.fileName == "new_location.txt" }) }
        println("  Verified server-side move was handled correctly.")
        newLocalFile.delete()
        }
    }

    @Test
    fun testSyncFolderHandlesServerSideDelete() {
        runBlocking {
        println("Testing handling of server-side deletions...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Setup: File exists locally
        val localFile = File(context.cacheDir, "delete_me.txt")
        localFile.writeText("content")
        
        // Drive listing is empty
        coEvery { mockDriveHelper.listAllFiles(any()) } returns emptyList()
        
        // DB knows about the link
        val existingItem = SyncItemEntity(
            id = "item-id", syncFolderId = folderId, accountId = "acc-id", accountEmail = "test@example.com",
            localPath = localFile.absolutePath, driveFileId = "drive-id-del", fileName = "delete_me.txt",
            mimeType = "text/plain", localModifiedAt = 1000L, driveModifiedAt = 1000L,
            localSize = localFile.length(), driveSize = localFile.length(), status = SyncStatus.SYNCED
        )
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        
        // Mock Drive metadata call to confirm it's actually trashed or gone
        val mockDriveItem = uk.xmlangel.googledrivesync.data.drive.DriveItem(
            id = "drive-id-del", name = "delete_me.txt", mimeType = "text/plain",
            modifiedTime = 1000L, size = 100L, md5Checksum = "md5",
            parentIds = listOf("some-other-id"), isFolder = false
        )
        coEvery { mockDriveHelper.getFileMetadata("drive-id-del") } returns mockDriveItem
        
        syncManager.syncFolder(folderId)
        
        assertFalse("Local file should have been deleted", localFile.exists())
        coVerify { mockSyncItemDao.deleteSyncItem(any()) }
        println("  Verified server-side deletion was handled correctly.")
        }
    }

    @Test
    fun testSyncFolderRecoversLocallyDeletedFiles() {
        runBlocking {
        println("Testing recovery of locally deleted files (Download from server)...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Drive possesses the file
        val driveItems = listOf(
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-recover", "recover.txt", "text/plain", 1000L, 100L, null, emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any()) } returns driveItems
        
        // Local file does NOT exist
        val localFile = File(context.cacheDir, "recover.txt")
        if (localFile.exists()) localFile.delete()
        
        // Even if DB knows about it or not, it should be downloaded
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns null
        coEvery { mockDriveHelper.downloadFile(any(), any()) } answers {
            localFile.writeText("recovered content")
            true
        }
        
        syncManager.syncFolder(folderId)
        
        assertTrue("Local file should have been recovered/downloaded", localFile.exists())
        coVerify { mockDriveHelper.downloadFile("drive-id-recover", localFile.absolutePath) }
        println("  Verified local deletion resulted in recovery from server.")
        localFile.delete()
        }
    }

    @Test
    fun testSyncFolderStopsOnFatalNetworkError() {
        runBlocking {
        println("Testing syncFolder stops immediately on fatal network error...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        
        // Mock a fatal network error when listing files
        coEvery { mockDriveHelper.listAllFiles(any()) } throws java.net.UnknownHostException("Unable to resolve host")
        
        val result = syncManager.syncFolder(folderId)
        
        assertTrue(result is SyncResult.Error)
        val error = result as SyncResult.Error
        assertTrue("Error message should mention network", error.message.contains("네트워크 연결"))
        
        // Verify that history was NOT completed successfully (it would have been logged in the finally block or catch block)
        // In the current implementation, SyncManager catches the exception and returns SyncResult.Error.
        // Let's verify logger was called with the error.
        verify { mockLogger.log(match { it.contains("치명적 오류 발생") && it.contains("DNS 오류") }, any()) }
        println("  Verified syncFolder caught fatal network error and returned appropriate SyncResult.Error.")
        }
    }
    @Test
    fun testSyncFolderUsesChangesApi() {
        runBlocking {
        println("Testing syncFolder uses Changes API when token is available...")
        val folderId = "test-folder-id"
        // Folder has a token!
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", lastStartPageToken = "old-token")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        
        // Mock a change: file-id-1 updated
        val driveFile = uk.xmlangel.googledrivesync.data.drive.DriveItem("file-id-1", "test.txt", "text/plain", System.currentTimeMillis(), 100L, "new-md5", listOf("drive-id"), false)
        val changes = listOf(uk.xmlangel.googledrivesync.data.drive.DriveChange("file-id-1", false, driveFile))
        val changeResult = uk.xmlangel.googledrivesync.data.drive.DriveChangeResult(changes, null, "new-token")
        
        coEvery { mockDriveHelper.getChanges("old-token") } returns changeResult
        
        // Mock DB knows this item
        val localFile = File(context.cacheDir, "test.txt")
        localFile.writeText("old content")
        val existingItem = SyncItemEntity(UUID.randomUUID().toString(), folderId, "acc-id", "test@example.com", localFile.absolutePath, "file-id-1", "test.txt", "text/plain", localFile.lastModified(), 0L, localFile.length(), 0L, "old-md5")
        
        coEvery { mockSyncItemDao.getSyncItemByDriveId("file-id-1") } returns existingItem
        coEvery { mockDriveHelper.downloadFile(any(), any()) } returns true
        coEvery { mockSyncItemDao.updateSyncItem(any()) } just Runs
        coEvery { mockSyncFolderDao.updatePageToken(any(), any()) } just Runs
        
        val result = syncManager.syncFolder(folderId)
        
        assertTrue(result is SyncResult.Success)
        coVerify { mockDriveHelper.getChanges("old-token") }
        coVerify { mockDriveHelper.listAllFiles(any()) } 
        coVerify { mockSyncFolderDao.updatePageToken(folderId, "new-token") }
        println("  Verified syncFolder used Changes API and updated the token.")
        localFile.delete()
        }
    }

    @Test
    fun testSyncFolderHandlesServerDeleteThroughChangesApi() {
        runBlocking {
        println("Testing syncFolder handles server deletion via Changes API...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", lastStartPageToken = "old-token")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        
        // Mock a change: file-id-del removed
        val changes = listOf(uk.xmlangel.googledrivesync.data.drive.DriveChange("file-id-del", true, null))
        val changeResult = uk.xmlangel.googledrivesync.data.drive.DriveChangeResult(changes, null, "new-token")
        
        coEvery { mockDriveHelper.getChanges("old-token") } returns changeResult
        
        // Mock DB knows this item
        val localFile = File(context.cacheDir, "delete_me.txt")
        localFile.writeText("to be deleted")
        val existingItem = SyncItemEntity(UUID.randomUUID().toString(), folderId, "acc-id", "test@example.com", localFile.absolutePath, "file-id-del", "delete_me.txt", "text/plain", localFile.lastModified(), 0L, localFile.length(), 0L, "md5")
        
        coEvery { mockSyncItemDao.getSyncItemByDriveId("file-id-del") } returns existingItem
        coEvery { mockSyncItemDao.deleteSyncItem(any()) } just Runs
        coEvery { mockSyncFolderDao.updatePageToken(any(), any()) } just Runs
        
        syncManager.syncFolder(folderId)
        
        assertFalse("Local file should have been deleted", localFile.exists())
        coVerify { mockSyncItemDao.deleteSyncItem(existingItem) }
        println("  Verified server deletion was reflected locally via Changes API.")
        }
    }

    @Test
    fun testSyncFolderSkipsOnMd5Match() {
        runBlocking {
        println("Testing processFilePair skips sync when MD5 matches even if timestamps differ...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        val localFile = File(context.cacheDir, "md5_skip.txt")
        localFile.writeText("same content")
        val fixedMd5 = "constant-md5"
        
        // Mock FileUtils to return a fixed MD5
        coEvery { uk.xmlangel.googledrivesync.util.FileUtils.calculateMd5(any()) } returns fixedMd5
        
        val driveFile = uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-1", localFile.name, "text/plain", System.currentTimeMillis() + 10000, localFile.length(), fixedMd5, listOf("drive-id"), false)
        val existingItem = SyncItemEntity(UUID.randomUUID().toString(), folderId, "acc-id", "test@example.com", localFile.absolutePath, "drive-id-1", localFile.name, "text/plain", localFile.lastModified() - 10000, driveFile.modifiedTime - 10000, localFile.length(), localFile.length(), fixedMd5)
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockDriveHelper.listAllFiles(any()) } returns listOf(driveFile)
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(any()) } returns existingItem
        coEvery { mockSyncItemDao.getSyncItemByDriveId(any()) } returns existingItem
        coEvery { mockSyncItemDao.updateSyncItem(any()) } just Runs
        
        val result = syncManager.syncFolder(folderId)
        
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals("Should have 0 uploaded", 0, success.uploaded)
        assertEquals("Should have 0 downloaded", 0, success.downloaded)
        assertEquals("Should have 1 skipped", 1, success.skipped)
        
        // Verification: metadata was updated precisely once because modified times were different but MD5 was same
        coVerify { mockSyncItemDao.updateSyncItem(match { it.md5Checksum == fixedMd5 }) }
        println("  Verified that sync was skipped due to MD5 match despite timestamp drift.")
        localFile.delete()
        }
    }

    @Test
    fun testSyncFolderUpdatesTokenAfterFullSync() {
        runBlocking {
        println("Testing syncFolder fetches and saves a Page Token after a full sync...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", lastStartPageToken = null)
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockDriveHelper.listAllFiles(any()) } returns emptyList()
        coEvery { mockDriveHelper.getStartPageToken() } returns "brand-new-token"
        coEvery { mockSyncFolderDao.updatePageToken(any(), any()) } just Runs
        
        syncManager.syncFolder(folderId)
        
        coVerify { mockDriveHelper.getStartPageToken() }
        coVerify { mockSyncFolderDao.updatePageToken(folderId, "brand-new-token") }
        println("  Verified that Page Token was initialized after full sync.")
        }
    }

    @org.junit.After
    fun tearDown() {
        unmockkObject(uk.xmlangel.googledrivesync.util.FileUtils)
    }
}

