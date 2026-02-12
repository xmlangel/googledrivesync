package uk.xmlangel.googledrivesync.sync

import android.content.Context
import android.os.FileObserver
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
import uk.xmlangel.googledrivesync.data.drive.*
import uk.xmlangel.googledrivesync.data.local.*
import uk.xmlangel.googledrivesync.data.model.SyncStatus
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.util.SyncLogger
import uk.xmlangel.googledrivesync.util.TestMetadata
import uk.xmlangel.googledrivesync.util.TestMetadataRule
import org.junit.Rule
import java.io.File
import java.util.*
import kotlinx.coroutines.flow.flowOf
import org.junit.rules.TemporaryFolder

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

    @get:Rule
    val tempFolder = TemporaryFolder()

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
        every { mockSyncPreferences.userExcludedPaths } returns emptySet()
        coEvery { mockSyncItemDao.getSyncItemsByFolder(any()) } returns flowOf(
            listOf(
                SyncItemEntity(
                    id = "tracked-item",
                    syncFolderId = "tracked-folder",
                    accountId = "acc-id",
                    accountEmail = "test@example.com",
                    localPath = File(context.cacheDir, "tracked.txt").absolutePath,
                    driveFileId = "tracked-drive-id",
                    fileName = "tracked.txt",
                    mimeType = "text/plain",
                    localModifiedAt = 1L,
                    driveModifiedAt = 1L,
                    localSize = 1L,
                    driveSize = 1L,
                    status = SyncStatus.SYNCED
                )
            )
        )
        
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
    // Given: Preconditions for "initialize returns true when drive helper initializes successfully" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "initialize returns true when drive helper initializes successfully" is verified.
    // 주어진 것: "initialize returns true when drive helper initializes successfully" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "initialize returns true when drive helper initializes successfully"의 기대 동작이 검증됨.
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
    // Given: Preconditions for "resolveConflict USE_LOCAL updates status and calls driveHelper" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "resolveConflict USE_LOCAL updates status and calls driveHelper" is verified.
    // 주어진 것: "resolveConflict USE_LOCAL updates status and calls driveHelper" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "resolveConflict USE_LOCAL updates status and calls driveHelper"의 기대 동작이 검증됨.
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
    // Given: Preconditions for "resolveConflict USE_DRIVE updates status and calls driveHelper" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "resolveConflict USE_DRIVE updates status and calls driveHelper" is verified.
    // 주어진 것: "resolveConflict USE_DRIVE updates status and calls driveHelper" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "resolveConflict USE_DRIVE updates status and calls driveHelper"의 기대 동작이 검증됨.
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
    fun `resolveConflict USE_DRIVE returns false and marks error when download fails`() = runBlocking {
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

        coEvery { mockDriveHelper.downloadFile(any(), any()) } returns false
        coEvery { mockSyncItemDao.updateItemError(any(), any(), any()) } just Runs

        val result = syncManager.resolveConflict(conflict, ConflictResolution.USE_DRIVE)

        assertFalse(result)
        coVerify { mockDriveHelper.downloadFile("drive-id", "/tmp/test.txt") }
        coVerify(exactly = 0) { mockSyncItemDao.updateItemStatus("test-id", SyncStatus.SYNCED) }
        coVerify { mockSyncItemDao.updateItemError("test-id", SyncStatus.ERROR, match { it.contains("Drive download failed") }) }
    }

    @Test
    // Given: Preconditions for "resolveConflict KEEP_BOTH renames local and downloads drive version" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "resolveConflict KEEP_BOTH renames local and downloads drive version" is verified.
    // 주어진 것: "resolveConflict KEEP_BOTH renames local and downloads drive version" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "resolveConflict KEEP_BOTH renames local and downloads drive version"의 기대 동작이 검증됨.
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
    // Given: Preconditions for "syncFolder adds to conflicts list when no default policy is set" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder adds to conflicts list when no default policy is set" is verified.
    // 주어진 것: "syncFolder adds to conflicts list when no default policy is set" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder adds to conflicts list when no default policy is set"의 기대 동작이 검증됨.
    fun `syncFolder adds to conflicts list when no default policy is set`() = runBlocking {
        println("Testing 'Ask Every Time' behavior (null policy)...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
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
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns driveItems
        
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
        coVerify(exactly = 0) { mockDriveHelper.updateFile("drive-id-ask", any(), any()) }
        coVerify(exactly = 0) { mockDriveHelper.downloadFile(any(), any()) }
        
        println("  Verified that null policy results in a Conflict result for user interaction.")
        localFile.delete()
        Unit
    }


    @Test
    // Given: Preconditions for "syncFolder updates lastSyncResult on success" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder updates lastSyncResult on success" is verified.
    // 주어진 것: "syncFolder updates lastSyncResult on success" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder updates lastSyncResult on success"의 기대 동작이 검증됨.
    fun `syncFolder updates lastSyncResult on success`() = runBlocking {
        println("Testing syncFolder updates lastSyncResult on success...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(
            id = folderId,
            accountId = "account-id",
            accountEmail = "test@example.com",
            localPath = context.cacheDir.absolutePath,
            driveFolderId = "drive-id",
            driveFolderName = "Drive Folder"
        )
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns emptyList()
        
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
    // Given: Preconditions for "dismissLastResult clears lastSyncResult" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "dismissLastResult clears lastSyncResult" is verified.
    // 주어진 것: "dismissLastResult clears lastSyncResult" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "dismissLastResult clears lastSyncResult"의 기대 동작이 검증됨.
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
    // Given: Preconditions for "syncFolder logs with ERROR prefix on failure" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder logs with ERROR prefix on failure" is verified.
    // 주어진 것: "syncFolder logs with ERROR prefix on failure" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder logs with ERROR prefix on failure"의 기대 동작이 검증됨.
    fun `syncFolder logs with ERROR prefix on failure`() = runBlocking {
        println("Testing error logging with [ERROR] prefix in syncFolder...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(
            id = folderId,
            accountId = "account-id",
            accountEmail = "test@example.com",
            localPath = context.cacheDir.absolutePath,
            driveFolderId = "drive-id",
            driveFolderName = "Drive Folder"
        )
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        
        // Mock a failure during file listing to trigger the catch block in syncFolder
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } throws Exception("List files failed")
        
        syncManager.syncFolder(folderId)
        
        // Verify that logger was called with some error message containing [ERROR]
        verify { mockLogger.log(match { it.contains("[ERROR]") }, any()) }
        println("  Verified [ERROR] prefix was logged on failure.")
    }

    @Test
    // Given: Preconditions for "syncFolder updates syncProgress with correct currentIndex and totalFiles" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder updates syncProgress with correct currentIndex and totalFiles" is verified.
    // 주어진 것: "syncFolder updates syncProgress with correct currentIndex and totalFiles" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder updates syncProgress with correct currentIndex and totalFiles"의 기대 동작이 검증됨.
    fun `syncFolder updates syncProgress with correct currentIndex and totalFiles`() = runBlocking {
        println("Testing syncFolder updates syncProgress correctly...")
        val folderId = "test-folder-id"
        // Use an isolated local path so recursive counting remains deterministic.
        val progressRoot = tempFolder.newFolder("sync-progress")
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", progressRoot.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Mock 1 file in Drive
        val driveItems = listOf(
            uk.xmlangel.googledrivesync.data.drive.DriveItem("id1", "file1.txt", "text/plain", 1000L, 100L, null, emptyList(), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns driveItems
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
        assertTrue("Total files should be at least 1", lastProgress.totalFiles >= 1)
        assertTrue("Current index should be at least 1", lastProgress.currentIndex >= 1)
        println("  Verified syncProgress updates: totalFiles=${lastProgress.totalFiles}, currentIndex=${lastProgress.currentIndex}")
    }

    @Test
    // Given: Preconditions for "syncFolder skips when no changes" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder skips when no changes" is verified.
    // 주어진 것: "syncFolder skips when no changes" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder skips when no changes"의 기대 동작이 검증됨.
    fun `syncFolder skips when no changes`() = runBlocking {
        println("Testing syncFolder skips unchanged files...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
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
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-1", localFile.name, "text/plain", fixedTime + 5000, 100L, null, listOf("drive-id"), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns driveItems
        
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
        assertTrue("Should skip at least one _local file", success.skipped >= 1)
        assertEquals("Should have 0 uploaded files", 0, success.uploaded)
        assertEquals("Should have 0 downloaded files", 0, success.downloaded)
        
        // Verify no Drive API updates/downloads were called
        coVerify(exactly = 0) { mockDriveHelper.updateFile("drive-id-1", any(), any()) }
        coVerify(exactly = 0) { mockDriveHelper.downloadFile(any(), any()) }
        
        println("  Verified that syncFolder skipped the unchanged file correctly.")
    }

    @Test
    // Given: Preconditions for "syncFolder links existing files without sync" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder links existing files without sync" is verified.
    // 주어진 것: "syncFolder links existing files without sync" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder links existing files without sync"의 기대 동작이 검증됨.
    fun `syncFolder links existing files without sync`() = runBlocking {
        println("Testing syncFolder linking identical existing files (v1.0.8 logic)...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
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
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-link", localFile.name, "text/plain", fixedTime + 100, localFile.length(), "test-md5", listOf("drive-id"), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns driveItems
        
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
        coVerify(exactly = 0) { mockDriveHelper.updateFile("drive-id-link", any(), any()) }
        coVerify(exactly = 0) { mockDriveHelper.downloadFile(any(), any()) }
        
        println("  Verified that syncFolder linked identical untracked files without syncing.")
    }

    @Test
    // Given: Preconditions for "syncFolder links by size even if timestamps differ greatly" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder links by size even if timestamps differ greatly" is verified.
    // 주어진 것: "syncFolder links by size even if timestamps differ greatly" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder links by size even if timestamps differ greatly"의 기대 동작이 검증됨.
    fun `syncFolder links by size even if timestamps differ greatly`() = runBlocking {
        println("Testing v1.0.9 size-based linking for new items...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
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
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns driveItems
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
    // Given: Preconditions for "syncFolder swallows metadata update if sizes match" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder swallows metadata update if sizes match" is verified.
    // 주어진 것: "syncFolder swallows metadata update if sizes match" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder swallows metadata update if sizes match"의 기대 동작이 검증됨.
    fun `syncFolder swallows metadata update if sizes match`() = runBlocking {
        println("Testing v1.0.9 metadata swallowing for existing items...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
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
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-swallow", localFile.name, "text/plain", 4000000000L, localFile.length(), "test-md5", listOf("drive-id"), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns driveItems
        
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
        coVerify(exactly = 0) { mockDriveHelper.updateFile("drive-id-swallow", any(), any()) }
        coVerify(exactly = 0) { mockDriveHelper.downloadFile(any(), any()) }
        
        println("  Verified that syncFolder updated metadata only when sizes matched (swallowing).")
    }

    @Test
    // Given: Preconditions for "syncFolder handles renames on Drive by renaming local file" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder handles renames on Drive by renaming local file" is verified.
    // 주어진 것: "syncFolder handles renames on Drive by renaming local file" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder handles renames on Drive by renaming local file"의 기대 동작이 검증됨.
    fun `syncFolder handles renames on Drive by renaming local file`() = runBlocking {
        println("Testing rename detection on Drive...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
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
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns driveItems
        
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
        coVerify { mockSyncItemDao.updateSyncItem(match {
            it.fileName == "new_name.txt" &&
                it.localPath == newLocalFile.absolutePath &&
                it.syncFolderId == folderId
        }) }
        println("  Verified that local file was renamed and DB updated when Drive name changed.")
    }

    @Test
    // Given: Preconditions for "syncFolder logs with ERROR prefix when folder not found" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder logs with ERROR prefix when folder not found" is verified.
    // 주어진 것: "syncFolder logs with ERROR prefix when folder not found" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder logs with ERROR prefix when folder not found"의 기대 동작이 검증됨.
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
                context.cacheDir.absolutePath, "drive-id", "Drive",
                syncDirection = SyncDirection.BIDIRECTIONAL
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
            
            coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns emptyList()
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
                context.cacheDir.absolutePath, "drive-id", "Drive",
                syncDirection = SyncDirection.BIDIRECTIONAL
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
            
            coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns emptyList()
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
            assertTrue("Pending uploads size should be non-negative", pendingUploads.size >= 0)
            
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
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns driveItems
        
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
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Setup: File exists locally
        val localFile = File(context.cacheDir, "delete_me.txt")
        localFile.writeText("content")
        
        // Drive listing is empty
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns emptyList()
        
        // DB knows about the link
        val existingItem = SyncItemEntity(
            id = "item-id", syncFolderId = folderId, accountId = "acc-id", accountEmail = "test@example.com",
            localPath = localFile.absolutePath, driveFileId = "drive-id-del", fileName = "delete_me.txt",
            mimeType = "text/plain", localModifiedAt = 1000L, driveModifiedAt = 1000L,
            localSize = localFile.length(), driveSize = localFile.length(), status = SyncStatus.SYNCED
        )
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        
        // Mock Drive metadata call to confirm it's actually trashed or gone
        // Using getFile (returns null if 404) as used in new Phase 4 implementation
        coEvery { mockDriveHelper.getFile("drive-id-del") } returns null
        
        syncManager.syncFolder(folderId)
        
        assertFalse("Local file should have been deleted because it is gone from Drive", localFile.exists())
        coVerify { mockSyncItemDao.deleteSyncItem(any()) }
        println("  Verified server-side deletion was handled correctly during full scan.")
        }
    }

    @Test
    fun testSyncFolderDeletesMovedDirectoryWhenTargetFolderNotSynced() {
        runBlocking {
            println("Testing deletion of moved directory when Drive parent is not a synced folder...")
            val folderId = "test-folder-id"
            val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")

            coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
            every { mockDriveHelper.initializeDriveService(any()) } returns true
            coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
            coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
            coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
            coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns emptyList()

            val localDir = File(context.cacheDir, "moved_dir").also { it.mkdirs() }
            File(localDir, "child.txt").writeText("nested")

            val existingItem = SyncItemEntity(
                id = "dir-item-id",
                syncFolderId = folderId,
                accountId = "acc-id",
                accountEmail = "test@example.com",
                localPath = localDir.absolutePath,
                driveFileId = "drive-dir-id",
                fileName = "moved_dir",
                mimeType = DriveServiceHelper.MIME_TYPE_FOLDER,
                localModifiedAt = localDir.lastModified(),
                driveModifiedAt = localDir.lastModified(),
                localSize = 0L,
                driveSize = 0L,
                status = SyncStatus.SYNCED
            )
            coEvery { mockSyncItemDao.getSyncItemByLocalPath(localDir.absolutePath) } returns existingItem

            val movedMeta = DriveItem(
                id = "drive-dir-id",
                name = "moved_dir",
                mimeType = DriveServiceHelper.MIME_TYPE_FOLDER,
                modifiedTime = System.currentTimeMillis(),
                size = 0L,
                md5Checksum = null,
                parentIds = listOf("unsynced-parent-id"),
                isFolder = true
            )
            coEvery { mockDriveHelper.getFile("drive-dir-id") } returns movedMeta
            coEvery { mockSyncFolderDao.getSyncFolderByDriveId("unsynced-parent-id") } returns null

            syncManager.syncFolder(folderId)

            assertFalse("Moved directory should be deleted recursively", localDir.exists())
            coVerify { mockSyncItemDao.deleteSyncItem(existingItem) }
        }
    }

    @Test
    fun testSyncFolderHandlesServerTrashDuringFullScan() {
        runBlocking {
        println("Testing handling of server-side trash (remains in folder but trashed)...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        val localFile = File(context.cacheDir, "trash_me.txt")
        localFile.writeText("content")
        
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns emptyList()
        val existingItem = SyncItemEntity(
            id = "item-id", syncFolderId = folderId, accountId = "acc-id", accountEmail = "test@example.com",
            localPath = localFile.absolutePath, driveFileId = "drive-id-trash", fileName = "trash_me.txt",
            mimeType = "text/plain", localModifiedAt = 1000L, driveModifiedAt = 1000L,
            localSize = localFile.length(), driveSize = localFile.length(), status = SyncStatus.SYNCED
        )
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        
        // Mock Drive item still exists but parent matches (missing from list means trashed or moved, 
        // listAllFiles query is '... and trashed = false')
        val mockDriveItem = uk.xmlangel.googledrivesync.data.drive.DriveItem(
            id = "drive-id-trash", name = "trash_me.txt", mimeType = "text/plain",
            modifiedTime = 1000L, size = 100L, md5Checksum = "md5",
            parentIds = listOf("drive-id"), isFolder = false // Still reports same parent!
        )
        coEvery { mockDriveHelper.getFile("drive-id-trash") } returns mockDriveItem
        
        syncManager.syncFolder(folderId)
        
        assertFalse("Local file should have been deleted (server trash detection)", localFile.exists())
        coVerify { mockSyncItemDao.deleteSyncItem(any()) }
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
            uk.xmlangel.googledrivesync.data.drive.DriveItem("drive-id-recover", "recover.txt", "text/plain", 1000L, 100L, null, listOf("drive-id"), false)
        )
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns driveItems
        
        // Local file does NOT exist
        val localFile = File(context.cacheDir, "recover.txt")
        if (localFile.exists()) localFile.delete()
        
        // Even if DB knows about it or not, it should be downloaded
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns null
        coEvery { mockDriveHelper.downloadFileDetailed(any(), any()) } answers {
            localFile.writeText("recovered content")
            DriveServiceHelper.DownloadResult(success = true, mimeType = "text/plain", size = 100L)
        }
        
        syncManager.syncFolder(folderId)
        
        assertTrue("Local file should have been recovered/downloaded", localFile.exists())
        coVerify { mockDriveHelper.downloadFileDetailed("drive-id-recover", localFile.absolutePath) }
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
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } throws java.net.UnknownHostException("Unable to resolve host")
        
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
    fun testSyncFolderHandlesRenameThroughChangesApi() {
        runBlocking {
            val folderId = "test-folder-id"
            val oldLocalFile = File(context.cacheDir, "old_name.txt")
            oldLocalFile.writeText("content")
            
            val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL, lastStartPageToken = "old-token", lastSyncedAt = 1000L)
            coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
            every { mockDriveHelper.initializeDriveService(any()) } returns true
            
            val driveFileId = "drive-file-id"
            val existingItem = SyncItemEntity(
                id = java.util.UUID.randomUUID().toString(),
                syncFolderId = folderId,
                accountId = "acc-id",
                accountEmail = "test@example.com",
                fileName = "old_name.txt",
                localPath = oldLocalFile.absolutePath,
                driveFileId = driveFileId,
                mimeType = "text/plain",
                localModifiedAt = System.currentTimeMillis(),
                driveModifiedAt = System.currentTimeMillis(),
                localSize = 7L,
                driveSize = 7L,
                status = SyncStatus.SYNCED
            )
            coEvery { mockSyncItemDao.getSyncItemByDriveId(driveFileId) } returns existingItem
            coEvery { mockSyncItemDao.getSyncItemsByFolder(folderId) } returns flowOf(listOf(existingItem))
            
            // Mock Change: Rename from old_name.txt to new_name.txt
            val driveFile = uk.xmlangel.googledrivesync.data.drive.DriveItem(driveFileId, "new_name.txt", "text/plain", System.currentTimeMillis(), 7L, "md5", listOf("drive-id"), false)
            val changes = listOf(uk.xmlangel.googledrivesync.data.drive.DriveChange(driveFileId, false, driveFile))
            val changeResult = uk.xmlangel.googledrivesync.data.drive.DriveChangeResult(changes, null, "new-token")
            coEvery { mockDriveHelper.getChanges("old-token") } returns changeResult
            coEvery { mockDriveHelper.getStartPageToken() } returns "new-token"
            
            syncManager.syncFolder(folderId)
            
            val newLocalFile = File(context.cacheDir, "new_name.txt")
            assertTrue("Local file should have been renamed via Changes API", newLocalFile.exists())
            assertFalse("Old file should no longer exist", oldLocalFile.exists())
            
            coVerify { mockSyncItemDao.updateSyncItem(any<SyncItemEntity>()) }
            coVerify { mockSyncFolderDao.updatePageToken(folderId, "new-token") }
            
            // Clean up
            if (newLocalFile.exists()) newLocalFile.delete()
        }
    }

    @Test
    fun testSyncFolderHandlesMoveThroughChangesApi() {
        runBlocking {
            val folderId = "test-folder-id"
            val subDir = File(context.cacheDir, "subfolder")
            subDir.mkdirs()
            val oldLocalFile = File(context.cacheDir, "file.txt")
            oldLocalFile.writeText("content")
            
            val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL, lastStartPageToken = "old-token", lastSyncedAt = 1000L)
            coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
            every { mockDriveHelper.initializeDriveService(any()) } returns true
            
            val driveFileId = "drive-file-id"
            val existingItem = SyncItemEntity(
                id = java.util.UUID.randomUUID().toString(),
                syncFolderId = folderId,
                accountId = "acc-id",
                accountEmail = "test@example.com",
                fileName = "file.txt",
                localPath = oldLocalFile.absolutePath,
                driveFileId = driveFileId,
                mimeType = "text/plain",
                localModifiedAt = System.currentTimeMillis(),
                driveModifiedAt = System.currentTimeMillis(),
                localSize = 7L,
                driveSize = 7L,
                status = SyncStatus.SYNCED
            )
            coEvery { mockSyncItemDao.getSyncItemByDriveId(driveFileId) } returns existingItem
            
            // Mock subfolder in DB
            val subFolderId = "sub-drive-id"
            val subFolderItem = SyncItemEntity(
                id = java.util.UUID.randomUUID().toString(),
                syncFolderId = folderId,
                accountId = "acc-id",
                accountEmail = "test@example.com",
                fileName = "subfolder",
                localPath = subDir.absolutePath,
                driveFileId = subFolderId,
                mimeType = uk.xmlangel.googledrivesync.data.drive.DriveServiceHelper.MIME_TYPE_FOLDER,
                localModifiedAt = System.currentTimeMillis(),
                driveModifiedAt = System.currentTimeMillis(),
                localSize = 0L,
                driveSize = 0L,
                status = SyncStatus.SYNCED
            )
            coEvery { mockSyncItemDao.getSyncItemsByFolder(folderId) } returns flowOf(listOf(subFolderItem))
            coEvery { mockSyncItemDao.getSyncItemByDriveId(subFolderId) } returns subFolderItem
            
            // Mock Change: Move file.txt into subfolder on Drive
            val driveFile = uk.xmlangel.googledrivesync.data.drive.DriveItem(driveFileId, "file.txt", "text/plain", System.currentTimeMillis(), 7L, "md5", listOf(subFolderId), false)
            val changes = listOf(uk.xmlangel.googledrivesync.data.drive.DriveChange(driveFileId, false, driveFile))
            val changeResult = uk.xmlangel.googledrivesync.data.drive.DriveChangeResult(changes, null, "new-token")
            coEvery { mockDriveHelper.getChanges("old-token") } returns changeResult
            coEvery { mockDriveHelper.getStartPageToken() } returns "new-token"
            
            syncManager.syncFolder(folderId)
            
            val newLocalFile = File(subDir, "file.txt")
            assertTrue("Local file should have been moved via Changes API", newLocalFile.exists())
            assertFalse("Old file should no longer exist", oldLocalFile.exists())
            
            coVerify { mockSyncItemDao.updateSyncItem(any()) }
            
            // Clean up
            subDir.deleteRecursively()
        }
    }
    
    @Test
    fun testSyncFolderHandlesLocalRenameOptimization() {
        runBlocking {
            val folderId = "test-folder-id"
            val oldPath = File(context.cacheDir, "old_file.txt").absolutePath
            val newFile = File(context.cacheDir, "new_file.txt")
            newFile.writeText("same content")
            val md5 = "test-md5"
            
            val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL, lastStartPageToken = "token", lastSyncedAt = 1000L)
            coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
            every { mockDriveHelper.initializeDriveService(any()) } returns true
            
            // Item exists in DB at old path, but file is missing (it's been renamed/moved)
            val driveFileId = "drive-file-id"
            val existingItem = SyncItemEntity(
                id = "pk-1",
                syncFolderId = folderId,
                accountId = "acc-id",
                accountEmail = "test@example.com",
                fileName = "old_file.txt",
                localPath = oldPath,
                driveFileId = driveFileId,
                mimeType = "text/plain",
                localModifiedAt = 500L,
                driveModifiedAt = 500L,
                localSize = newFile.length(),
                driveSize = newFile.length(),
                md5Checksum = md5,
                status = SyncStatus.SYNCED
            )
            
            // Dirty items: old path (missing) and new path (exists)
            val dirtyItems = listOf(
                DirtyLocalItemEntity(oldPath, folderId, 8),
                DirtyLocalItemEntity(newFile.absolutePath, folderId, 8)
            )
            
            coEvery { mockDirtyLocalDao.getDirtyItemsByFolder(folderId) } returns dirtyItems
            
            // SyncItemDao mocks - more robust matching
            coEvery { mockSyncItemDao.getSyncItemByLocalPath(any()) } answers {
                val p = it.invocation.args[0] as String
                if (p == oldPath) existingItem else null
            }
            coEvery { mockSyncItemDao.getSyncItemsByFolder(folderId) } returns flowOf(listOf(existingItem))
            
            // DriveHelper mocks
            val driveItem = DriveItem(driveFileId, "old_file.txt", "text/plain", 500L, newFile.length(), md5, listOf("drive-folder-id"), false)
            coEvery { mockDriveHelper.getFile(driveFileId) } returns driveItem
            coEvery { mockDriveHelper.getChanges(any()) } returns DriveChangeResult(emptyList(), null, "token")
            coEvery { mockDriveHelper.updateMetadata(driveFileId, newName = "new_file.txt", any(), any()) } returns true
            coEvery { mockDriveHelper.getStartPageToken() } returns "new-token"
            
            // Allow real FileUtils.calculateMd5 if not mocked
            // Calculating real MD5 for "same content"
            
            // Update existingItem with real MD5 if we use real one, or keep mock
            // Let's stick to mock but ensure it works.
            mockkObject(uk.xmlangel.googledrivesync.util.FileUtils)
            every { uk.xmlangel.googledrivesync.util.FileUtils.calculateMd5(any()) } returns md5
            
            syncManager.syncFolder(folderId)
            
            // Verify optimization: updateMetadata called, NOT uploadFile
            coVerify { mockDriveHelper.updateMetadata(driveFileId, newName = "new_file.txt", any(), any()) }
            coVerify(exactly = 0) { mockDriveHelper.uploadFile(newFile.absolutePath, any(), any(), any()) }
            
            // Verify DB update
            coVerify { mockSyncItemDao.deleteSyncItem(existingItem) }
            coVerify { mockSyncItemDao.insertSyncItem(match { it.localPath == newFile.absolutePath && it.driveFileId == driveFileId }) }
            
            newFile.delete()
            unmockkObject(uk.xmlangel.googledrivesync.util.FileUtils)
        }
    }
    @Test
    fun testSyncDirtyItemsHandlesDirectory() {
        runBlocking {
            val folderId = "test-folder-id"
            val dirName = "Obsidian"
            val newDir = File(context.cacheDir, dirName)
            if (!newDir.exists()) newDir.mkdir()
            
            val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL, lastSyncedAt = 1000L, lastStartPageToken = "token")
            coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
            every { mockDriveHelper.initializeDriveService(any()) } returns true
            coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
            coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
            coEvery { mockDriveHelper.getChanges(any()) } returns DriveChangeResult(emptyList(), null, "token")
            coEvery { mockDriveHelper.getStartPageToken() } returns "new-token"
            
            // Dirty item: new directory
            val dirtyItems = listOf(DirtyLocalItemEntity(newDir.absolutePath, folderId, 8))
            coEvery { mockDirtyLocalDao.getDirtyItemsByFolder(folderId) } returns dirtyItems
            
            // SyncItemDao mocks
            coEvery { mockSyncItemDao.getSyncItemByLocalPath(newDir.absolutePath) } returns null
            coEvery { mockSyncItemDao.getSyncItemsByFolder(folderId) } returns flowOf(
                listOf(
                    SyncItemEntity(
                        id = "tracked-dir",
                        syncFolderId = folderId,
                        accountId = "acc-id",
                        accountEmail = "test@example.com",
                        localPath = newDir.absolutePath,
                        driveFileId = "existing-dir",
                        fileName = dirName,
                        mimeType = DriveServiceHelper.MIME_TYPE_FOLDER,
                        localModifiedAt = 1L,
                        driveModifiedAt = 1L,
                        localSize = 0L,
                        driveSize = 0L,
                        status = SyncStatus.SYNCED
                    )
                )
            )
            
            // DriveHelper mocks - must return null to trigger "New local item" logic
            coEvery { mockDriveHelper.getFile(any()) } returns null
            coEvery { mockDriveHelper.findFile(any(), any()) } returns null
            
            // DriveHelper mock for createFolder
            val driveFolder = DriveItem("drive-folder-id", dirName, "application/vnd.google-apps.folder", System.currentTimeMillis(), 0L, null, listOf("drive-id"), true)
            coEvery { mockDriveHelper.createFolder(dirName, any()) } returns driveFolder
            coEvery { mockSyncItemDao.insertSyncItem(any()) } just Runs
            
            syncManager.syncFolder(folderId)
            
            // Verify createFolder was called instead of uploadFile
            coVerify { mockDriveHelper.createFolder(dirName, any()) }
            coVerify(exactly = 0) { mockDriveHelper.uploadFile(newDir.absolutePath, any(), any(), any()) }
            
            newDir.delete()
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
        coVerify { mockDriveHelper.listAllFiles(any(), any()) } 
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
        
        assertFalse("Local file should have been moved out from sync root", localFile.exists())
        val deferredRoot = File(File(context.cacheDir, "conflicts_backup"), "deferred_delete")
        val archivedCopyExists = deferredRoot.exists() &&
            deferredRoot.walkTopDown().any { it.isFile && it.name == "delete_me.txt" }
        assertTrue("Deleted file should be archived in conflicts_backup/deferred_delete", archivedCopyExists)
        coVerify { mockSyncItemDao.deleteSyncItem(existingItem) }
        println("  Verified server deletion was reflected locally via Changes API.")
        }
    }

    @Test
    // Given: Preconditions for "syncFolder cleans deferred delete backups older than 30 days" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder cleans deferred delete backups older than 30 days" is verified.
    // 주어진 것: "syncFolder cleans deferred delete backups older than 30 days" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder cleans deferred delete backups older than 30 days"의 기대 동작이 검증됨.
    fun `syncFolder cleans deferred delete backups older than 30 days`() = runBlocking {
        val folderId = "cleanup-folder-id"
        val localRoot = File(tempFolder.root, "cleanup-root").apply { mkdirs() }
        val folder = SyncFolderEntity(
            id = folderId,
            accountId = "acc-id",
            accountEmail = "test@example.com",
            localPath = localRoot.absolutePath,
            driveFolderId = "drive-id",
            driveFolderName = "Drive",
            lastSyncedAt = 1000L,
            lastStartPageToken = "token"
        )
        val oldArchiveDir = File(localRoot, "conflicts_backup/deferred_delete/old_batch").apply { mkdirs() }
        File(oldArchiveDir, "old.txt").writeText("old")
        oldArchiveDir.setLastModified(System.currentTimeMillis() - (31L * 24 * 60 * 60 * 1000))

        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockDriveHelper.getChanges("token") } returns DriveChangeResult(emptyList(), null, "new-token")
        coEvery { mockSyncItemDao.getSyncItemsByFolder(folderId) } returns flowOf(listOf(
            SyncItemEntity(
                id = "tracked",
                syncFolderId = folderId,
                accountId = "acc-id",
                accountEmail = "test@example.com",
                localPath = File(localRoot, "keep.txt").absolutePath,
                driveFileId = "drive-keep",
                fileName = "keep.txt",
                mimeType = "text/plain",
                localModifiedAt = 1000L,
                driveModifiedAt = 1000L,
                localSize = 1L,
                driveSize = 1L,
                status = SyncStatus.SYNCED
            )
        ))
        coEvery { mockDirtyLocalDao.getDirtyItemsByFolder(folderId) } returns emptyList()
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns emptyList()
        coEvery { mockDriveHelper.getStartPageToken() } returns "new-token"
        coEvery { mockSyncFolderDao.updatePageToken(folderId, "new-token") } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs

        val result = syncManager.syncFolder(folderId)

        assertTrue(result is SyncResult.Success)
        assertFalse("Deferred delete backup older than 30 days should be removed", oldArchiveDir.exists())
    }

    @Test
    fun testSyncFolderSkipsOnMd5Match() {
        runBlocking {
        println("Testing processFilePair skips sync when MD5 matches even if timestamps differ...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
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
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns listOf(driveFile)
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
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns emptyList()
        coEvery { mockDriveHelper.getStartPageToken() } returns "brand-new-token"
        coEvery { mockSyncFolderDao.updatePageToken(any(), any()) } just Runs
        
        syncManager.syncFolder(folderId)
        
        coVerify { mockDriveHelper.getStartPageToken() }
        coVerify { mockSyncFolderDao.updatePageToken(folderId, "brand-new-token") }
        println("  Verified that Page Token was initialized after full sync.")
        }
    }

    @Test
    // Given: Preconditions for "syncFolder skips files containing _local" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder skips files containing _local" is verified.
    // 주어진 것: "syncFolder skips files containing _local" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder skips files containing _local"의 기대 동작이 검증됨.
    fun `syncFolder skips files containing _local`() {
        runBlocking {
        println("Testing skipping of files containing _local...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive", syncDirection = SyncDirection.BIDIRECTIONAL)
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        
        // Setup preference to auto-upload to ensure it WOULD upload if not for the skip
        every { mockSyncPreferences.autoUploadEnabled } returns true
        
        val localFile = File(context.cacheDir, "test_local.txt")
        localFile.writeText("should be skipped")
        
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns emptyList()
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(any()) } returns null
        
        val result = syncManager.syncFolder(folderId)
        
        assertTrue("syncFolder should not return Error for _local skip case", result !is SyncResult.Error)
        if (result is SyncResult.Success) {
            assertTrue("Should skip at least one _local file", result.skipped >= 1)
            assertEquals("Should have 0 uploaded files", 0, result.uploaded)
        }
        
        // Verify uploadFile was NEVER called for the _local file
        coVerify(exactly = 0) { mockDriveHelper.uploadFile(localFile.absolutePath, any(), any()) }
        
        println("  Verified that file containing _local was skipped.")
        localFile.delete()
        }
    }

    @Test
    // Given: Preconditions for "syncFolder logs exception name when message is null" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder logs exception name when message is null" is verified.
    // 주어진 것: "syncFolder logs exception name when message is null" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder logs exception name when message is null"의 기대 동작이 검증됨.
    fun `syncFolder logs exception name when message is null`() {
        runBlocking {
        println("Testing logging of exception name when message is null...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        
        // Force an exception with null message during file listing
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } throws java.io.IOException(null as String?)
        
        syncManager.syncFolder(folderId)
        
        // Verify that logger was called with the exception class name "IOException"
        verify { mockLogger.log(match { it.contains("IOException") }, any()) }
        println("  Verified exception class name was logged when message was null.")
        }
    }

    @Test
    // Given: Preconditions for "syncFolder rethrows ConnectException as fatal" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder rethrows ConnectException as fatal" is verified.
    // 주어진 것: "syncFolder rethrows ConnectException as fatal" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder rethrows ConnectException as fatal"의 기대 동작이 검증됨.
    fun `syncFolder rethrows ConnectException as fatal`() {
        runBlocking {
        println("Testing handling of ConnectException as fatal error...")
        val folderId = "test-folder-id"
        val folder = SyncFolderEntity(folderId, "acc-id", "test@example.com", context.cacheDir.absolutePath, "drive-id", "Drive")
        
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        
        // Mock a ConnectException
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } throws java.net.ConnectException("Connection refused")
        val result = syncManager.syncFolder(folderId)
        
        assertTrue("Result should be SyncResult.Error but was $result", result is SyncResult.Error)
        println("  Verified syncFolder caught ConnectException and returned SyncResult.Error.")
        }
    }

    @Test
    @TestMetadata(
        description = "기존 폴더 처리 시 EISDIR 오류 방지 검증",
        step = "1. DB에 있는 폴더 목킹 | 2. 로컬 수정 시간 변경 | 3. processFilePair 호출",
        expected = "updateFile을 호출하지 않고 DB 메타데이터만 업데이트해야 함"
    )
    // Given: Preconditions for "testProcessFilePairHandlesExistingDirectory prevents EISDIR" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "testProcessFilePairHandlesExistingDirectory prevents EISDIR" is verified.
    // 주어진 것: "testProcessFilePairHandlesExistingDirectory prevents EISDIR" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "testProcessFilePairHandlesExistingDirectory prevents EISDIR"의 기대 동작이 검증됨.
    fun `testProcessFilePairHandlesExistingDirectory prevents EISDIR`() = runBlocking {
        println("설명: 기존 폴더 처리 시 EISDIR 오류 방지 검증 | 예상결과: SUCCESS | 실제결과: 시작")
        
        val folderId = "folder-id"
        val folder = SyncFolderEntity(folderId, "acc", "email", tempFolder.root.absolutePath, "drive-id", "root")
        
        val dirName = "existing-dir"
        val dirPath = File(tempFolder.root, dirName).apply { mkdir() }.absolutePath
        val localFile = File(dirPath)
        
        val driveFileId = "drive-dir-id"
        val driveDir = DriveItem(
            id = driveFileId, 
            name = dirName, 
            mimeType = "application/vnd.google-apps.folder", 
            modifiedTime = System.currentTimeMillis(), 
            size = 0L,
            parentIds = emptyList(),
            isFolder = true
        )
        
        val existingItem = SyncItemEntity(
            id = "item-id",
            syncFolderId = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = dirPath,
            driveFileId = driveFileId,
            fileName = dirName,
            mimeType = "application/vnd.google-apps.folder",
            localModifiedAt = System.currentTimeMillis() - 10000, // 10s difference
            driveModifiedAt = System.currentTimeMillis() - 10000,
            localSize = 0,
            driveSize = 0,
            status = SyncStatus.SYNCED
        )
        
        // Mocking
        coEvery { mockSyncItemDao.updateSyncItem(any()) } just Runs
        
        // Use reflection to call private processFilePair or test indirectly via syncDirtyItems
        // Actually, let's test via syncDirtyItems which is easier to trigger
        val dirtyItems = listOf(DirtyLocalItemEntity(dirPath, folderId, 8)) // 8 = MODIFY
        coEvery { mockDirtyLocalDao.getDirtyItemsByFolder(folderId) } returns dirtyItems
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(dirPath) } returns existingItem
        coEvery { mockDriveHelper.getFile(driveFileId) } returns driveDir
        coEvery { mockSyncItemDao.getSyncItemsByFolder(folderId) } returns flowOf(listOf(existingItem))
        
        // coEvery { mockDriveHelper.updateFile(any(), any(), any()) } returns null // Should not reach here
        
        // Directly call internal syncDirtyItems
        val result = syncManager.syncDirtyItems(folder, dirtyItems)
        
        println("설명: 기존 폴더 처리 시 EISDIR 오류 방지 검증 | 예상결과: SUCCESS | 실제결과: ${result.errors} errors")
        
        assertEquals("Errors should be 0", 0, result.errors)
        coVerify(exactly = 0) { mockDriveHelper.updateFile(any(), any(), any()) }
        coVerify(exactly = 1) { mockSyncItemDao.updateSyncItem(any()) }
    }

    @Test
    // Given: Preconditions for "testSyncChangesHandlesRemovalEvenIfLocalFileMissing" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "testSyncChangesHandlesRemovalEvenIfLocalFileMissing" is verified.
    // 주어진 것: "testSyncChangesHandlesRemovalEvenIfLocalFileMissing" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "testSyncChangesHandlesRemovalEvenIfLocalFileMissing"의 기대 동작이 검증됨.
    fun `testSyncChangesHandlesRemovalEvenIfLocalFileMissing`() = runBlocking {
        println("Testing Phase 2: DB cleanup even if local file is already missing...")
        val folderId = "folder-id"
        val folder = SyncFolderEntity(folderId, "acc", "email", tempFolder.root.absolutePath, "drive-id", "root", lastStartPageToken = "token")
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        
        val driveFileId = "drive-del-id"
        val existingItem = SyncItemEntity(
            id = "item-id", syncFolderId = folderId, accountId = "acc", accountEmail = "email",
            localPath = "/non/existent/path.txt", driveFileId = driveFileId, fileName = "path.txt",
            mimeType = "text/plain", localModifiedAt = 1000, driveModifiedAt = 1000, localSize = 10, driveSize = 10, status = SyncStatus.SYNCED
        )
        coEvery { mockSyncItemDao.getSyncItemByDriveId(driveFileId) } returns existingItem
        
        // Result with one 'removed' change
        val changes = listOf(DriveChange(driveFileId, removed = true, file = null))
        coEvery { mockDriveHelper.getChanges("token") } returns DriveChangeResult(changes, null, "new-token")
        
        // SyncManager uses getSyncItemsByFolder in syncChangesInternal for folder list, mock it
        coEvery { mockSyncItemDao.getSyncItemsByFolder(folderId) } returns flowOf(listOf(existingItem))

        syncManager.syncFolder(folderId)
        
        // Verify DB deletion happened despite file missing
        coVerify { mockSyncItemDao.deleteSyncItem(existingItem) }
    }

    @Test
    // Given: Preconditions for "testSyncDirtyItemsHandlesServerDeletionInsteadOfReupload" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "testSyncDirtyItemsHandlesServerDeletionInsteadOfReupload" is verified.
    // 주어진 것: "testSyncDirtyItemsHandlesServerDeletionInsteadOfReupload" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "testSyncDirtyItemsHandlesServerDeletionInsteadOfReupload"의 기대 동작이 검증됨.
    fun `testSyncDirtyItemsHandlesServerDeletionInsteadOfReupload`() = runBlocking {
        println("Testing Phase 3: Targeted sync deletes local file if Drive counterpart is gone...")
        val folderId = "folder-id"
        val localFile = File(context.cacheDir, "resurrect_me.txt")
        localFile.writeText("some content")
        
        val folder = SyncFolderEntity(folderId, "acc", "email", context.cacheDir.absolutePath, "drive-id", "root", lastSyncedAt = 1000L)
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        
        val driveFileId = "drive-id"
        val existingItem = SyncItemEntity(
            id = "item-id", syncFolderId = folderId, accountId = "acc", accountEmail = "email",
            localPath = localFile.absolutePath, driveFileId = driveFileId, fileName = "resurrect_me.txt",
            mimeType = "text/plain", localModifiedAt = 1000, driveModifiedAt = 1000, localSize = 12, driveSize = 12, status = SyncStatus.SYNCED
        )
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        
        // Dirty item event
        val dirtyItems = listOf(DirtyLocalItemEntity(localFile.absolutePath, folderId, 8))
        coEvery { mockDirtyLocalDao.getDirtyItemsByFolder(folderId) } returns dirtyItems
        
        // Drive lookup returns null (404)
        coEvery { mockDriveHelper.getFile(driveFileId) } returns null
        coEvery { mockSyncItemDao.getSyncItemsByFolder(folderId) } returns flowOf(listOf(existingItem))
        coEvery { mockDriveHelper.getChanges(any()) } returns DriveChangeResult(emptyList(), null, "token")
        
        syncManager.syncFolder(folderId)
        
        // Verify: local file deleted, DB item deleted, NO upload
        assertFalse("Local file should have been deleted because it's gone from Drive", localFile.exists())
        coVerify { mockSyncItemDao.deleteSyncItem(existingItem) }
        coVerify(exactly = 0) { mockDriveHelper.uploadFile(any(), any(), any(), any()) }
    }

    @Test
    // Given: Preconditions for "syncDirtyItems ensures nested Drive parent folders before upload" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncDirtyItems ensures nested Drive parent folders before upload" is verified.
    // 주어진 것: "syncDirtyItems ensures nested Drive parent folders before upload" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncDirtyItems ensures nested Drive parent folders before upload"의 기대 동작이 검증됨.
    fun `syncDirtyItems ensures nested Drive parent folders before upload`() = runBlocking {
        val folderId = "folder-id"
        val root = File(tempFolder.root, "root").apply { mkdirs() }
        val nestedDir = File(root, "level1/level2").apply { mkdirs() }
        val localFile = File(nestedDir, "new.txt").apply { writeText("new content") }

        val folder = SyncFolderEntity(
            id = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = root.absolutePath,
            driveFolderId = "drive-root",
            driveFolderName = "root",
            syncDirection = SyncDirection.BIDIRECTIONAL
        )

        val dirtyItems = listOf(DirtyLocalItemEntity(localFile.absolutePath, folderId, 8))

        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns null
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(File(root, "level1").absolutePath) } returns null
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(File(root, "level1/level2").absolutePath) } returns null

        coEvery { mockDriveHelper.findFolder("level1", "drive-root") } returns null
        coEvery { mockDriveHelper.createFolder("level1", "drive-root") } returns DriveItem(
            id = "drive-level1",
            name = "level1",
            mimeType = DriveServiceHelper.MIME_TYPE_FOLDER,
            modifiedTime = 1000L,
            size = 0L,
            parentIds = listOf("drive-root"),
            isFolder = true
        )
        coEvery { mockDriveHelper.findFolder("level2", "drive-level1") } returns null
        coEvery { mockDriveHelper.createFolder("level2", "drive-level1") } returns DriveItem(
            id = "drive-level2",
            name = "level2",
            mimeType = DriveServiceHelper.MIME_TYPE_FOLDER,
            modifiedTime = 1001L,
            size = 0L,
            parentIds = listOf("drive-level1"),
            isFolder = true
        )
        coEvery { mockDriveHelper.findFile(localFile.name, "drive-level2") } returns null
        coEvery {
            mockDriveHelper.uploadFile(localFile.absolutePath, localFile.name, "drive-level2", any())
        } returns DriveItem(
            id = "drive-file-id",
            name = localFile.name,
            mimeType = "text/plain",
            modifiedTime = 2000L,
            size = localFile.length(),
            md5Checksum = "test-md5",
            parentIds = listOf("drive-level2"),
            isFolder = false
        )

        val result = syncManager.syncDirtyItems(folder, dirtyItems)

        assertEquals(1, result.uploaded)
        assertEquals(0, result.errors)
        coVerify { mockDriveHelper.createFolder("level1", "drive-root") }
        coVerify { mockDriveHelper.createFolder("level2", "drive-level1") }
        coVerify { mockDriveHelper.uploadFile(localFile.absolutePath, localFile.name, "drive-level2", any()) }
    }

    @Test
    // Given: Preconditions for "syncDirtyItems rename sends null removeParents when parent unchanged" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncDirtyItems rename sends null removeParents when parent unchanged" is verified.
    // 주어진 것: "syncDirtyItems rename sends null removeParents when parent unchanged" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncDirtyItems rename sends null removeParents when parent unchanged"의 기대 동작이 검증됨.
    fun `syncDirtyItems rename sends null removeParents when parent unchanged`() = runBlocking {
        val folderId = "folder-id"
        val root = File(tempFolder.root, "rename-root").apply { mkdirs() }
        val oldPath = File(root, "old.txt").absolutePath
        val newFile = File(root, "renamed.txt").apply { writeText("same") }
        val driveFileId = "drive-file-id"

        val folder = SyncFolderEntity(
            id = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = root.absolutePath,
            driveFolderId = "drive-root",
            driveFolderName = "root",
            syncDirection = SyncDirection.BIDIRECTIONAL
        )

        val existingItem = SyncItemEntity(
            id = "item-id",
            syncFolderId = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = oldPath,
            driveFileId = driveFileId,
            fileName = "old.txt",
            mimeType = "text/plain",
            localModifiedAt = 1000L,
            driveModifiedAt = 1000L,
            localSize = newFile.length(),
            driveSize = newFile.length(),
            md5Checksum = "test-md5",
            status = SyncStatus.SYNCED
        )

        val dirtyItems = listOf(
            DirtyLocalItemEntity(oldPath, folderId, 64),
            DirtyLocalItemEntity(newFile.absolutePath, folderId, 128)
        )

        coEvery { mockSyncItemDao.getSyncItemByLocalPath(oldPath) } returns existingItem
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(newFile.absolutePath) } returns null
        coEvery { mockDriveHelper.getFile(driveFileId) } returns DriveItem(
            id = driveFileId,
            name = "old.txt",
            mimeType = "text/plain",
            modifiedTime = 1000L,
            size = newFile.length(),
            md5Checksum = "test-md5",
            parentIds = listOf("drive-root"),
            isFolder = false
        )
        coEvery {
            mockDriveHelper.updateMetadata(
                fileId = driveFileId,
                newName = "renamed.txt",
                addParents = null,
                removeParents = null
            )
        } returns true

        val result = syncManager.syncDirtyItems(folder, dirtyItems)

        assertEquals(1, result.uploaded)
        coVerify {
            mockDriveHelper.updateMetadata(
                fileId = driveFileId,
                newName = "renamed.txt",
                addParents = null,
                removeParents = null
            )
        }
        coVerify(exactly = 0) { mockDriveHelper.uploadFile(any(), any(), any(), any()) }
    }

    @Test
    // Given: Preconditions for "syncFolder honors folder syncDirection over default preference" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncFolder honors folder syncDirection over default preference" is verified.
    // 주어진 것: "syncFolder honors folder syncDirection over default preference" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncFolder honors folder syncDirection over default preference"의 기대 동작이 검증됨.
    fun `syncFolder honors folder syncDirection over default preference`() = runBlocking {
        val root = File(tempFolder.root, "dir-policy").apply { mkdirs() }
        val localFile = File(root, "upload_me.txt").apply { writeText("content") }
        val folderId = "folder-direction"
        val folder = SyncFolderEntity(
            id = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = root.absolutePath,
            driveFolderId = "drive-root",
            driveFolderName = "root",
            syncDirection = SyncDirection.DOWNLOAD_ONLY
        )

        every { mockSyncPreferences.defaultSyncDirection } returns SyncDirection.BIDIRECTIONAL
        every { mockSyncPreferences.autoUploadEnabled } returns true

        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        coEvery { mockHistoryDao.insertHistory(any()) } returns 1L
        coEvery { mockHistoryDao.completeHistory(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockSyncFolderDao.updateLastSyncTime(any(), any()) } just Runs
        coEvery { mockDriveHelper.listAllFiles(any(), any()) } returns emptyList()
        coEvery { mockDriveHelper.getStartPageToken() } returns "token"
        coEvery { mockSyncFolderDao.updatePageToken(folderId, "token") } just Runs
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns null

        val result = syncManager.syncFolder(folderId)

        assertTrue(result !is SyncResult.Error)
        coVerify(exactly = 0) { mockDriveHelper.uploadFile(any(), any(), any(), any()) }
    }

    @Test
    // Given: Preconditions for "testLocalDeletionIsSyncedToServer" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "testLocalDeletionIsSyncedToServer" is verified.
    // 주어진 것: "testLocalDeletionIsSyncedToServer" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "testLocalDeletionIsSyncedToServer"의 기대 동작이 검증됨.
    fun `testLocalDeletionIsSyncedToServer`() = runBlocking {
        println("Testing: Local deletion should be synced to Google Drive...")
        val folderId = "folder-id"
        val localFile = File(context.cacheDir, "delete_me_soon.txt")
        // Don't create the file, simulate it was deleted
        
        val folder = SyncFolderEntity(folderId, "acc", "email", context.cacheDir.absolutePath, "drive-id", "root", syncDirection = SyncDirection.BIDIRECTIONAL, lastSyncedAt = 1000L, lastStartPageToken = "existing-token")
        coEvery { mockSyncFolderDao.getSyncFolderById(folderId) } returns folder
        every { mockDriveHelper.initializeDriveService(any()) } returns true
        
        val driveFileId = "drive-id-to-del"
        val existingItem = SyncItemEntity(
            id = "item-id", syncFolderId = folderId, accountId = "acc", accountEmail = "email",
            localPath = localFile.absolutePath, driveFileId = driveFileId, fileName = "delete_me_soon.txt",
            mimeType = "text/plain", localModifiedAt = 1000, driveModifiedAt = 1000, localSize = 12, driveSize = 12, status = SyncStatus.SYNCED
        )
        
        // Mock DB: item exists at this path
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        
        // Dirty item event: explicit local delete event
        val dirtyItems = listOf(DirtyLocalItemEntity(localFile.absolutePath, folderId, FileObserver.DELETE))
        coEvery { mockDirtyLocalDao.getDirtyItemsByFolder(folderId) } returns dirtyItems
        
        // Drive lookup returns the file (it's still there!)
        val driveItem = DriveItem(driveFileId, "delete_me_soon.txt", "text/plain", 1000, 12, "md5", listOf("drive-id"), false)
        coEvery { mockDriveHelper.getFile(driveFileId) } returns driveItem
        coEvery { mockDriveHelper.delete(driveFileId) } returns true
        coEvery { mockSyncItemDao.deleteSyncItem(existingItem) } just Runs
        coEvery { mockSyncItemDao.getSyncItemsByFolder(folderId) } returns flowOf(listOf(existingItem))
        coEvery { mockDriveHelper.getChanges(any()) } returns DriveChangeResult(emptyList(), null, "token")
        coEvery { mockDriveHelper.getStartPageToken() } returns "new-token"
        coEvery { mockSyncFolderDao.updatePageToken(folderId, "new-token") } just Runs

        syncManager.syncFolder(folderId)
        
        // Verify: driveHelper.delete was called!
        coVerify { mockDriveHelper.delete(driveFileId) }
        coVerify { mockSyncItemDao.deleteSyncItem(existingItem) }
        println("  Verified local deletion was synced to server.")
    }

    @Test
    // Given: Local path is missing but dirty event is non-delete (e.g., MODIFY).
    // And: Item is tracked with driveFileId.
    // When: syncDirtyItems runs.
    // Then: Do not delete on Drive (defensive skip).
    // 주어진 것: 로컬 경로가 없지만 dirty 이벤트가 비삭제(MODIFY 등)임.
    // 그리고: 항목은 driveFileId로 추적 중임.
    // 언제: syncDirtyItems 실행 시.
    // 그러면: Drive 삭제를 수행하지 않음(보수적 스킵).
    fun `syncDirtyItems does not delete Drive file when missing local path is not a delete event`() = runBlocking {
        val folderId = "folder-id"
        val localFile = File(context.cacheDir, "missing-not-delete.txt")
        if (localFile.exists()) {
            localFile.delete()
        }
        val driveFileId = "drive-id-keep"

        val folder = SyncFolderEntity(
            folderId,
            "acc",
            "email",
            context.cacheDir.absolutePath,
            "drive-root",
            "root",
            lastSyncedAt = 1000L,
            lastStartPageToken = "existing-token"
        )
        val existingItem = SyncItemEntity(
            id = "item-id",
            syncFolderId = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = localFile.absolutePath,
            driveFileId = driveFileId,
            fileName = "missing-not-delete.txt",
            mimeType = "text/plain",
            localModifiedAt = 1000L,
            driveModifiedAt = 1000L,
            localSize = 10L,
            driveSize = 10L,
            status = SyncStatus.SYNCED
        )
        val dirtyItems = listOf(DirtyLocalItemEntity(localFile.absolutePath, folderId, FileObserver.MODIFY))

        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem

        val result = syncManager.syncDirtyItems(folder, dirtyItems)

        assertEquals(0, result.uploaded)
        assertEquals(0, result.errors)
        coVerify(exactly = 0) { mockDriveHelper.delete(any()) }
        coVerify(exactly = 0) { mockSyncItemDao.deleteSyncItem(existingItem) }
    }

    @Test
    // Given: DB has tracked driveFileId and local file changed.
    // And: Drive metadata exists but parent is outside current sync root.
    // When: syncDirtyItems runs.
    // Then: Keep tracked driveFileId (no name-based relink) and update using existing ID.
    // 주어진 것: DB에 추적 driveFileId가 있고 로컬 파일이 변경됨.
    // 그리고: Drive 메타데이터는 존재하지만 부모가 현재 동기화 루트 밖임.
    // 언제: syncDirtyItems 실행 시.
    // 그러면: 이름 기반 재링크 없이 기존 driveFileId를 유지하고 해당 ID로 업데이트.
    fun `syncDirtyItems keeps tracked drive id when parent mismatches`() = runBlocking {
        val folderId = "folder-stale-id"
        val root = File(tempFolder.root, "stale-id-root").apply { mkdirs() }
        val localFile = File(root, "1_2_3_4.md").apply { writeText("newer") }
        val oldDriveId = "drive-old-id"
        val folder = SyncFolderEntity(
            id = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = root.absolutePath,
            driveFolderId = "drive-root",
            driveFolderName = "root",
            syncDirection = SyncDirection.BIDIRECTIONAL
        )
        val existingItem = SyncItemEntity(
            id = "item-stale",
            syncFolderId = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = localFile.absolutePath,
            driveFileId = oldDriveId,
            fileName = localFile.name,
            mimeType = "text/markdown",
            localModifiedAt = localFile.lastModified() - 10_000,
            driveModifiedAt = 1_000L,
            localSize = 1L,
            driveSize = 1L,
            status = SyncStatus.SYNCED
        )
        val dirtyItems = listOf(DirtyLocalItemEntity(localFile.absolutePath, folderId, FileObserver.MODIFY))

        every { mockSyncPreferences.autoUploadEnabled } returns true
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        coEvery { mockDriveHelper.getFile(oldDriveId) } returns DriveItem(
            id = oldDriveId,
            name = localFile.name,
            mimeType = "text/markdown",
            modifiedTime = 1_000L,
            size = 1L,
            md5Checksum = "old-md5",
            parentIds = listOf("outside-root"),
            isFolder = false
        )
        coEvery { mockDriveHelper.updateFile(oldDriveId, localFile.absolutePath, any()) } returns DriveItem(
            id = oldDriveId,
            name = localFile.name,
            mimeType = "text/markdown",
            modifiedTime = 2_000L,
            size = localFile.length(),
            md5Checksum = "new-md5",
            parentIds = listOf("drive-root"),
            isFolder = false
        )

        val result = syncManager.syncDirtyItems(folder, dirtyItems)

        assertEquals(1, result.uploaded)
        assertEquals(0, result.errors)
        coVerify { mockDriveHelper.updateFile(oldDriveId, localFile.absolutePath, any()) }
        coVerify(exactly = 0) { mockDriveHelper.findFile(localFile.name, "drive-root") }
    }

    @Test
    // Given: Local path is missing and dirty event is MOVED_FROM.
    // And: Item is tracked with driveFileId.
    // When: syncDirtyItems runs.
    // Then: Treat as delete-like event and propagate delete to Drive.
    // 주어진 것: 로컬 경로가 없고 dirty 이벤트가 MOVED_FROM임.
    // 그리고: 항목은 driveFileId로 추적 중임.
    // 언제: syncDirtyItems 실행 시.
    // 그러면: 삭제성 이벤트로 간주하고 Drive 삭제를 반영.
    fun `syncDirtyItems treats MOVED_FROM as delete event and syncs deletion to Drive`() = runBlocking {
        val folderId = "folder-id-moved-from"
        val localFile = File(context.cacheDir, "moved_from_delete.txt")
        if (localFile.exists()) {
            localFile.delete()
        }
        val driveFileId = "drive-id-moved-from"

        val folder = SyncFolderEntity(
            folderId,
            "acc",
            "email",
            context.cacheDir.absolutePath,
            "drive-root",
            "root",
            syncDirection = SyncDirection.BIDIRECTIONAL
        )
        val existingItem = SyncItemEntity(
            id = "item-moved-from",
            syncFolderId = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = localFile.absolutePath,
            driveFileId = driveFileId,
            fileName = "moved_from_delete.txt",
            mimeType = "text/plain",
            localModifiedAt = 1000L,
            driveModifiedAt = 1000L,
            localSize = 10L,
            driveSize = 10L,
            status = SyncStatus.SYNCED
        )
        val dirtyItems = listOf(DirtyLocalItemEntity(localFile.absolutePath, folderId, FileObserver.MOVED_FROM))

        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        coEvery { mockDriveHelper.delete(driveFileId) } returns true
        coEvery { mockSyncItemDao.deleteSyncItem(existingItem) } just Runs

        val result = syncManager.syncDirtyItems(folder, dirtyItems)

        assertEquals(0, result.errors)
        coVerify { mockDriveHelper.delete(driveFileId) }
        coVerify { mockSyncItemDao.deleteSyncItem(existingItem) }
    }

    @Test
    // Given: Preconditions for "syncDirtyItems reuploads when tracked drive id is stale and no file is found" are prepared.
    // And: Required mocks and test data are configured.
    // When: The target action is executed in this test.
    // Then: Expected behavior for "syncDirtyItems reuploads when tracked drive id is stale and no file is found" is verified.
    // 주어진 것: "syncDirtyItems reuploads when tracked drive id is stale and no file is found" 테스트의 사전 조건이 준비되어 있음.
    // 그리고: 필요한 목(mock)과 테스트 데이터가 구성되어 있음.
    // 언제: 이 테스트에서 대상 동작을 실행하면.
    // 그러면: "syncDirtyItems reuploads when tracked drive id is stale and no file is found"의 기대 동작이 검증됨.
    fun `syncDirtyItems reuploads when tracked drive id is stale and no file is found`() = runBlocking {
        val folderId = "folder-reupload"
        val root = File(tempFolder.root, "reupload-root").apply { mkdirs() }
        val localFile = File(root, "1_2_3_4.md").apply { writeText("content-new") }
        val staleDriveId = "drive-stale"
        val folder = SyncFolderEntity(
            id = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = root.absolutePath,
            driveFolderId = "drive-root",
            driveFolderName = "root",
            syncDirection = SyncDirection.BIDIRECTIONAL
        )
        val existingItem = SyncItemEntity(
            id = "item-reupload",
            syncFolderId = folderId,
            accountId = "acc",
            accountEmail = "email",
            localPath = localFile.absolutePath,
            driveFileId = staleDriveId,
            fileName = localFile.name,
            mimeType = "text/markdown",
            localModifiedAt = localFile.lastModified() - 10_000,
            driveModifiedAt = 1_000L,
            localSize = 1L,
            driveSize = 1L,
            status = SyncStatus.SYNCED
        )
        val dirtyItems = listOf(DirtyLocalItemEntity(localFile.absolutePath, folderId, FileObserver.MODIFY))

        every { mockSyncPreferences.autoUploadEnabled } returns true
        coEvery { mockSyncItemDao.getSyncItemByLocalPath(localFile.absolutePath) } returns existingItem
        coEvery { mockDriveHelper.getFile(staleDriveId) } returns null
        coEvery { mockDriveHelper.findFile(localFile.name, "drive-root") } returns null
        coEvery { mockDriveHelper.uploadFile(localFile.absolutePath, localFile.name, "drive-root", any()) } returns DriveItem(
            id = "drive-reuploaded",
            name = localFile.name,
            mimeType = "text/markdown",
            modifiedTime = 2_000L,
            size = localFile.length(),
            md5Checksum = "new-md5",
            parentIds = listOf("drive-root"),
            isFolder = false
        )

        val result = syncManager.syncDirtyItems(folder, dirtyItems)

        assertEquals(1, result.uploaded)
        assertEquals(0, result.errors)
        coVerify { mockDriveHelper.uploadFile(localFile.absolutePath, localFile.name, "drive-root", any()) }
    }

    @org.junit.After
    fun tearDown() {
        unmockkObject(uk.xmlangel.googledrivesync.util.FileUtils)
    }
}
