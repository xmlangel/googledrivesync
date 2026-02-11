package uk.xmlangel.googledrivesync.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xmlangel.googledrivesync.data.local.SyncDatabase
import uk.xmlangel.googledrivesync.data.local.SyncPreferences
import uk.xmlangel.googledrivesync.data.repository.AccountRepository
import uk.xmlangel.googledrivesync.sync.SyncManager
import uk.xmlangel.googledrivesync.util.AppVersionUtil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VersionDisplayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: android.content.Context
    private lateinit var expectedVersion: String

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Set up package info for Robolectric
        val shadowPackageManager = org.robolectric.Shadows.shadowOf(context.packageManager)
        val packageInfo = android.content.pm.PackageInfo().apply {
            packageName = context.packageName
            versionName = "1.2.0"
            @Suppress("DEPRECATION")
            versionCode = 18
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                longVersionCode = 18L
            }
        }
        shadowPackageManager.addPackage(packageInfo)
        
        expectedVersion = AppVersionUtil.getVersionString(context)
    }

    @Test
    fun accountScreen_displaysCorrectVersion() {
        val accountRepository = mockk<AccountRepository>(relaxed = true)
        every { accountRepository.accounts } returns MutableStateFlow(emptyList())
        every { accountRepository.activeAccount } returns MutableStateFlow(null)

        composeTestRule.setContent {
            AccountScreen(
                accountRepository = accountRepository,
                onNavigateToFolders = {},
                onNavigateToSettings = {},
                onNavigateToLogs = {}
            )
        }

        // AccountScreen title is currently hardcoded "Google Drive Sync v1.0.9"
        // After modification it should be "Google Drive Sync $expectedVersion"
        composeTestRule.onNodeWithText("Google Drive Sync $expectedVersion").assertExists()
    }

    @Test
    fun dashboardScreen_displaysCorrectVersion() {
        val syncManager = mockk<SyncManager>(relaxed = true)
        val database = mockk<SyncDatabase>(relaxed = true)
        
        every { syncManager.isSyncing } returns MutableStateFlow(false)
        every { syncManager.syncProgress } returns MutableStateFlow(null)
        every { syncManager.lastSyncResult } returns MutableStateFlow(null)
        every { syncManager.pendingConflicts } returns MutableStateFlow(emptyList())
        every { syncManager.pendingUploads } returns MutableStateFlow(emptyList())

        composeTestRule.setContent {
            DashboardScreen(
                syncManager = syncManager,
                database = database,
                accountId = "test_account",
                onNavigateToSettings = {},
                onNavigateToFolderBrowser = {},
                onNavigateToAccounts = {},
                onNavigateToLogs = {},
                onNavigateToSyncedFolder = { _, _ -> }
            )
        }

        composeTestRule.onNodeWithText("Google Drive Sync $expectedVersion").assertExists()
    }

    @Test
    fun syncSettingsScreen_displaysCorrectVersion() {
        val syncPreferences = mockk<SyncPreferences>(relaxed = true)

        composeTestRule.setContent {
            SyncSettingsScreen(
                syncPreferences = syncPreferences,
                onNavigateBack = {},
                onNavigateToExclusions = {},
                onScheduleSync = {}
            )
        }

        composeTestRule.onNodeWithText(expectedVersion).assertExists()
    }
}
