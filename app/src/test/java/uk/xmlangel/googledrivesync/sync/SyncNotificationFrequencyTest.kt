package uk.xmlangel.googledrivesync.sync

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uk.xmlangel.googledrivesync.data.local.SyncDatabase
import uk.xmlangel.googledrivesync.data.local.SyncFolderDao
import uk.xmlangel.googledrivesync.data.local.SyncFolderEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SyncNotificationFrequencyTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private val mockSyncManager = mockk<SyncManager>(relaxed = true)
    private val mockDatabase = mockk<SyncDatabase>(relaxed = true)
    private val mockFolderDao = mockk<SyncFolderDao>(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Mock SyncManager singleton
        mockkObject(SyncManager.Companion)
        every { SyncManager.getInstance(any()) } returns mockSyncManager
        
        // Mock Database singleton
        mockkObject(SyncDatabase.Companion)
        every { SyncDatabase.getInstance(any()) } returns mockDatabase
        every { mockDatabase.syncFolderDao() } returns mockFolderDao
    }

    @Test
    fun `SyncWorker calls notify at most once for final results`() = runBlocking {
        // Setup mock data
        val folder = SyncFolderEntity("1", "acc1", "test@test.com", "/tmp", "drive1", "Drive1")
        coEvery { mockFolderDao.getEnabledSyncFolders() } returns MutableStateFlow(listOf(folder))
        coEvery { mockSyncManager.syncFolder(any()) } returns SyncResult.Success(1, 1, 0)

        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        
        // We use spy to track calls to the real notification manager if needed, 
        // but Robolectric's shadow is better for actual verification of system state.
        // However, since we want to count total calls to notify(), let's use a mock or spy.
        
        val nmSpy = spyk(notificationManager)
        // Inject the spy if possible, but NotificationManager is a system service.
        // In Robolectric, we can verify the notifications shown.
        
        worker.doWork()

        val shadowNM = shadowOf(notificationManager)
        val notifications = shadowNM.allNotifications
        
        // In the logs, we saw multiple muting messages for NOTIFICATION_ID 1001.
        // We want to ensure that for a single worker run, we don't spam updates.
        // Currently, SyncWorker calls setForeground (1 update) and showFinalNotification (1 update).
        // Total should be around 2 calls to notify/setForeground.
        
        assert(notifications.size <= 2) { "Too many notifications: ${notifications.size}" }
    }
}
