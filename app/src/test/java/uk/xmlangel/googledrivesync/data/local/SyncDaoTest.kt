package uk.xmlangel.googledrivesync.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.data.model.SyncStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SyncDaoTest {

    private lateinit var database: SyncDatabase
    private lateinit var folderDao: SyncFolderDao
    private lateinit var itemDao: SyncItemDao

    @Before
    fun setUp() {
        // Use in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SyncDatabase::class.java
        ).allowMainThreadQueries().build()
        
        folderDao = database.syncFolderDao()
        itemDao = database.syncItemDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert and get sync folder`() = runBlocking {
        val folder = SyncFolderEntity(
            id = "test-folder",
            accountId = "account-1",
            accountEmail = "user@example.com",
            localPath = "/sdcard/docs",
            driveFolderId = "drive-id",
            driveFolderName = "GoogleDocs",
            syncDirection = SyncDirection.BIDIRECTIONAL
        )
        
        folderDao.insertSyncFolder(folder)
        
        val result = folderDao.getSyncFolderById("test-folder")
        assertNotNull(result)
        assertEquals("account-1", result?.accountId)
        assertEquals("GoogleDocs", result?.driveFolderName)
    }

    @Test
    fun `getEnabledSyncFolders returns only enabled folders`() = runBlocking {
        val folder1 = SyncFolderEntity(
            id = "f1", accountId = "a1", accountEmail = "e1", localPath = "p1", 
            driveFolderId = "d1", driveFolderName = "n1", isEnabled = true
        )
        val folder2 = SyncFolderEntity(
            id = "f2", accountId = "a1", accountEmail = "e1", localPath = "p2", 
            driveFolderId = "d2", driveFolderName = "n2", isEnabled = false
        )
        
        folderDao.insertSyncFolder(folder1)
        folderDao.insertSyncFolder(folder2)
        
        val enabledFolders = folderDao.getEnabledSyncFolders().first()
        assertEquals(1, enabledFolders.size)
        assertEquals("f1", enabledFolders[0].id)
    }

    @Test
    fun `insert and count items by status`() = runBlocking {
        val folderId = "folder-1"
        val item1 = SyncItemEntity(
            id = "i1", syncFolderId = folderId, accountId = "a1", accountEmail = "e1",
            localPath = "path1", driveFileId = "d1", fileName = "file1",
            mimeType = "text/plain", localModifiedAt = 0, driveModifiedAt = 0,
            localSize = 0, driveSize = 0, status = SyncStatus.SYNCED
        )
        val item2 = SyncItemEntity(
            id = "i2", syncFolderId = folderId, accountId = "a1", accountEmail = "e1",
            localPath = "path2", driveFileId = "d2", fileName = "file2",
            mimeType = "text/plain", localModifiedAt = 0, driveModifiedAt = 0,
            localSize = 0, driveSize = 0, status = SyncStatus.ERROR
        )
        
        itemDao.insertSyncItem(item1)
        itemDao.insertSyncItem(item2)
        
        val syncedCount = itemDao.countItemsByStatus(folderId, SyncStatus.SYNCED)
        val errorCount = itemDao.countItemsByStatus(folderId, SyncStatus.ERROR)
        
        assertEquals(1, syncedCount)
        assertEquals(1, errorCount)
    }

    @Test
    fun `deleteFoldersByAccount removes folders but Room might not cascade if not defined`() = runBlocking {
        // SyncEntities.kt does not define ForeignKey with CASCADE.
        // So we manually verify the DAOs' delete methods as implemented in SyncDao.kt
        
        val folder = SyncFolderEntity(
            id = "f1", accountId = "acc-delete", accountEmail = "e1", localPath = "p1",
            driveFolderId = "d1", driveFolderName = "n1"
        )
        folderDao.insertSyncFolder(folder)
        
        folderDao.deleteFoldersByAccount("acc-delete")
        
        val result = folderDao.getSyncFolderById("f1")
        assertNull(result)
    }
}
