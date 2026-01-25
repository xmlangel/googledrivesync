package uk.xmlangel.googledrivesync.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SyncManagerSingletonTest {

    @Test
    fun `getInstance returns the same instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        val instance1 = SyncManager.getInstance(context)
        val instance2 = SyncManager.getInstance(context)
        
        assertSame("SyncManager should return the same instance", instance1, instance2)
    }

    @Test
    fun `getInstance returns non-null instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instance = SyncManager.getInstance(context)
        assertNotNull(instance)
    }
}
