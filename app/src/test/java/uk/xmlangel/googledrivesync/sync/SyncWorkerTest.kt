package uk.xmlangel.googledrivesync.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xmlangel.googledrivesync.data.local.SyncDatabase
import uk.xmlangel.googledrivesync.data.local.SyncFolderDao
import uk.xmlangel.googledrivesync.data.local.SyncFolderEntity
import uk.xmlangel.googledrivesync.data.local.SyncPreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SyncWorkerTest {

    private lateinit var context: Context
    private lateinit var mockSyncManager: SyncManager
    private lateinit var mockDatabase: SyncDatabase
    private lateinit var mockSyncFolderDao: SyncFolderDao
    private lateinit var mockPrefs: SyncPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        // Mock SyncManager singleton
        mockkObject(SyncManager.Companion)
        mockSyncManager = mockk(relaxed = true)
        every { SyncManager.getInstance(any()) } returns mockSyncManager
        
        // Mock Database singleton
        mockkObject(SyncDatabase.Companion)
        mockDatabase = mockk(relaxed = true)
        mockSyncFolderDao = mockk(relaxed = true)
        every { SyncDatabase.getInstance(any()) } returns mockDatabase
        every { mockDatabase.syncFolderDao() } returns mockSyncFolderDao
        
        mockPrefs = mockk(relaxed = true)
        // Note: In real app SyncWorker creates its own SyncPreferences(context)
        // To mock that, we might need to mock the constructor or just let it use real prefs.
        // For this test, let's assume real prefs but configured.
    }

    @Test
    fun `doWork calls syncFolder for all enabled folders`() = runBlocking {
        val folder1 = SyncFolderEntity("id1", "acc1", "email1", "/path1", "drive1", "folder1", isEnabled = true)
        val folder2 = SyncFolderEntity("id2", "acc2", "email2", "/path2", "drive2", "folder2", isEnabled = true)
        
        coEvery { mockSyncFolderDao.getEnabledSyncFolders() } returns flowOf(listOf(folder1, folder2))
        coEvery { mockSyncManager.syncFolder(any()) } returns SyncResult.Success(1, 0, 0)
        
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        val result = worker.doWork()
        
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { mockSyncManager.syncFolder("id1") }
        coVerify(exactly = 1) { mockSyncManager.syncFolder("id2") }
    }

    @Test
    fun `doWork retries when syncFolder returns error`() = runBlocking {
        val folder1 = SyncFolderEntity("id1", "acc1", "email1", "/path1", "drive1", "folder1", isEnabled = true)
        
        coEvery { mockSyncFolderDao.getEnabledSyncFolders() } returns flowOf(listOf(folder1))
        coEvery { mockSyncManager.syncFolder("id1") } returns SyncResult.Error("Network error")
        
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        val result = worker.doWork()
        
        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
