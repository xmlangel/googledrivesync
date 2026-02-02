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
    fun getVersionString_handlesNullVersionName() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowPackageManager = Shadows.shadowOf(context.packageManager)
        
        val packageInfo = android.content.pm.PackageInfo().apply {
            packageName = context.packageName
            versionName = null
            @Suppress("DEPRECATION")
            versionCode = 1
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                longVersionCode = 1L
            }
        }
        
        shadowPackageManager.addPackage(packageInfo)
        
        val versionString = AppVersionUtil.getVersionString(context)
        
        assertEquals("vUnknown (1)", versionString)
    }
}
