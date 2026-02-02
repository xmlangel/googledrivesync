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
        val largeMessage = "A".repeat(1024 * 1024) // 1MB
        
        // Write 11MB to trigger first rotation
        repeat(11) {
            logger.log(largeMessage)
        }
        
        val logFile = File(context.cacheDir, logFileName)
        val oldLogFile = File(context.cacheDir, oldLogFileName)
        
        assertTrue("Old log file should exist after rotation", oldLogFile.exists())
        
        // Write another 11MB to trigger second rotation (this will cover oldLogFile.delete())
        repeat(11) {
            logger.log(largeMessage)
        }
        
        assertTrue("Old log file should still exist (newly rotated)", oldLogFile.exists())
    }

    @Test
    fun testLogWithAccount() {
        val testMessage = "Message with account"
        val account = "test@gmail.com"
        logger.log(testMessage, account)
        
        val logs = logger.readLogs()
        assertTrue(logs[0].contains(testMessage))
        assertTrue(logs[0].contains("[$account]"))
    }

    @Test
    fun testErrorLogging() {
        val errorMessage = "[ERROR] Something went wrong"
        logger.log(errorMessage)
        
        val logs = logger.readLogs()
        assertTrue(logs[0].contains(errorMessage))
    }

    @Test
    fun testGetLogFile() {
        val file = logger.getLogFile()
        assertEquals(logFileName, file.name)
        assertEquals(context.cacheDir.absolutePath, file.parentFile?.absolutePath)
    }

    @Test
    fun testLogException() {
        // Triggering the catch block in log()
        // We can't easily mock FileOutputStream for line 29-31, 
        // but we can mock dateFormat to throw an exception in line 25.
        // SyncLogger has dateFormat which is private.
        // Let's use reflection to inject a broken dateFormat.
        
        val brokenDateFormat = object : java.text.SimpleDateFormat("yyyy") {
            override fun format(date: java.util.Date, toAppendTo: StringBuffer, pos: java.text.FieldPosition): StringBuffer {
                throw RuntimeException("Simulated format error")
            }
        }
        
        val field = SyncLogger::class.java.getDeclaredField("dateFormat")
        field.isAccessible = true
        field.set(logger, brokenDateFormat)
        
        // This should trigger the catch block (lines 39-41)
        logger.log("This will fail")
    }
}
