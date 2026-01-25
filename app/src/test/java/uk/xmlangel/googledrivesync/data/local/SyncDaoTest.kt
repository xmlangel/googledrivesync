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
        println("Testing SyncFolder insertion and retrieval...")
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
        println("  Inserted folder with ID: test-folder")
        
        val result = folderDao.getSyncFolderById("test-folder")
        assertNotNull(result)
        assertEquals("account-1", result?.accountId)
        assertEquals("GoogleDocs", result?.driveFolderName)
        println("  Retrieved folder successfully and verified accountId and name.")
    }

    @Test
    fun `getEnabledSyncFolders returns only enabled folders`() = runBlocking {
        println("Testing retrieval of enabled folders...")
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
        println("  Inserted one enabled folder (f1) and one disabled folder (f2)")
        
        val enabledFolders = folderDao.getEnabledSyncFolders().first()
        assertEquals(1, enabledFolders.size)
        assertEquals("f1", enabledFolders[0].id)
        println("  Verified that only folder f1 was returned.")
    }

    @Test
    fun `insert and count items by status`() = runBlocking {
        println("Testing item counting by status...")
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
        println("  Inserted one SYNCED item and one ERROR item")
        
        val syncedCount = itemDao.countItemsByStatus(folderId, SyncStatus.SYNCED)
        val errorCount = itemDao.countItemsByStatus(folderId, SyncStatus.ERROR)
        
        assertEquals(1, syncedCount)
        assertEquals(1, errorCount)
        println("  Counted SYNCED: $syncedCount, ERROR: $errorCount. Verified correctly.")
    }

    @Test
    fun `deleteFoldersByAccount removes folders but Room might not cascade if not defined`() = runBlocking {
        println("Testing folder deletion by account...")
        val folder = SyncFolderEntity(
            id = "f1", accountId = "acc-delete", accountEmail = "e1", localPath = "p1",
            driveFolderId = "d1", driveFolderName = "n1"
        )
        folderDao.insertSyncFolder(folder)
        println("  Inserted folder for account: acc-delete")
        
        folderDao.deleteFoldersByAccount("acc-delete")
        println("  Executed deleteFoldersByAccount(\"acc-delete\")")
        
        val result = folderDao.getSyncFolderById("f1")
        assertNull(result)
        println("  Verified that folder f1 was deleted from database.")
    }
}
