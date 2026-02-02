package uk.xmlangel.googledrivesync.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppVersionUtilTest {

    @Test
    fun getVersionString_returnsCorrectFormat() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowPackageManager = Shadows.shadowOf(context.packageManager)
        
        val packageInfo = android.content.pm.PackageInfo().apply {
            packageName = context.packageName
            versionName = "1.2.3"
            @Suppress("DEPRECATION")
            versionCode = 456
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                longVersionCode = 456L
            }
        }
        
        shadowPackageManager.addPackage(packageInfo)
        
        val versionString = AppVersionUtil.getVersionString(context)
        
        assertEquals("v1.2.3 (456)", versionString)
    }

    @Test
    @Config(sdk = [27])
    fun getVersionString_handlesOldSdkVersion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowPackageManager = Shadows.shadowOf(context.packageManager)
        
        val packageInfo = android.content.pm.PackageInfo().apply {
            packageName = context.packageName
            versionName = "1.0.0"
            @Suppress("DEPRECATION")
            versionCode = 100
        }
        
        shadowPackageManager.addPackage(packageInfo)
        
        val versionString = AppVersionUtil.getVersionString(context)
        
        assertEquals("v1.0.0 (100)", versionString)
    }

    @Test
    @Config(sdk = [31])
    fun getVersionString_handlesPreTiramisu() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowPackageManager = Shadows.shadowOf(context.packageManager)
        
        val packageInfo = android.content.pm.PackageInfo().apply {
            packageName = context.packageName
            versionName = "1.1.0"
            @Suppress("DEPRECATION")
            versionCode = 110
            longVersionCode = 110L
        }
        
        shadowPackageManager.addPackage(packageInfo)
        
        val versionString = AppVersionUtil.getVersionString(context)
        
        assertEquals("v1.1.0 (110)", versionString)
    }

    @Test
    fun getVersionString_handlesException() {
        val mockContext = io.mockk.mockk<android.content.Context>()
        io.mockk.every { mockContext.packageName } returns "uk.xmlangel.googledrivesync"
        io.mockk.every { mockContext.packageManager } throws RuntimeException("Simulated check failure")
        
        val versionString = AppVersionUtil.getVersionString(mockContext)
        
        assertEquals("Unknown", versionString)
    }

    @Test
    fun getVersionString_handlesNullVersionName() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowPackageManager = Shadows.shadowOf(context.packageManager)
        
        val packageInfo = android.content.pm.PackageInfo().apply {
            packageName = context.packageName
            versionName = null
            @Suppress("DEPRECATION")
            versionCode = 1
            longVersionCode = 1L
        }
        
        shadowPackageManager.addPackage(packageInfo)
        
        val versionString = AppVersionUtil.getVersionString(context)
        
        assertEquals("vUnknown (1)", versionString)
    }
}
