package uk.xmlangel.googledrivesync.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SyncLoggerTest {

    private lateinit var context: Context
    private lateinit var logger: SyncLogger
    private val logFileName = "sync.log"
    private val oldLogFileName = "sync.log.old"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        logger = SyncLogger(context)
        logger.clearLogs()
    }

    @After
    fun tearDown() {
        logger.clearLogs()
    }

    @Test
    fun testLogWriting() {
        val testMessage = "Test log message"
        logger.log(testMessage)
        
        val logs = logger.readLogs()
        assertTrue(logs.isNotEmpty())
        assertTrue(logs[0].contains(testMessage))
    }

    @Test
    fun testClearLogs() {
        logger.log("Message to be cleared")
        logger.clearLogs()
        
        val logs = logger.readLogs()
        assertTrue(logs.isEmpty())
        
        val logFile = File(context.cacheDir, logFileName)
        val oldLogFile = File(context.cacheDir, oldLogFileName)
        assertFalse(logFile.exists())
        assertFalse(oldLogFile.exists())
    }

    @Test
    fun testLogRotation() {
        // SyncLogger.MAX_SIZE is 10MB. We need to exceed this to trigger rotation.
        // For testing, we might want to use a smaller size if possible, 
        // but since it's a constant, we'll write a large amount of data or 
        // mock the file size if we had access. 
        // Since we can't easily change the constant, let's write 11MB of data.
        
        val largeMessage = "A".repeat(1024 * 1024) // 1MB
        
        // Write 11MB to trigger rotation
        repeat(11) {
            logger.log(largeMessage)
        }
        
        val logFile = File(context.cacheDir, logFileName)
        val oldLogFile = File(context.cacheDir, oldLogFileName)
        
        assertTrue("Old log file should exist after rotation", oldLogFile.exists())
        assertTrue("Current log file should exist (newly created)", logFile.exists())
        assertTrue("Old log file should be around 10MB-11MB", oldLogFile.length() >= 10 * 1024 * 1024)
        assertTrue("New log file should be smaller than 10MB", logFile.length() < 10 * 1024 * 1024)
    }
}
