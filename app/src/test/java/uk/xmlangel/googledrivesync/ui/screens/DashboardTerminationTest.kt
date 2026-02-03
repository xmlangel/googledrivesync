package uk.xmlangel.googledrivesync.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xmlangel.googledrivesync.data.local.SyncDatabase
import uk.xmlangel.googledrivesync.sync.SyncManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DashboardTerminationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dashboardScreen_showsExitButton() {
        val syncManager = mockk<SyncManager>(relaxed = true)
        val database = mockk<SyncDatabase>(relaxed = true)
        
        every_sync_manager_flows(syncManager)

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

        // Check for Exit button by content description
        composeTestRule.onNodeWithContentDescription("앱 종료").assertExists()
    }

    @Test
    fun dashboardScreen_showsExitConfirmationDialog() {
        val syncManager = mockk<SyncManager>(relaxed = true)
        val database = mockk<SyncDatabase>(relaxed = true)
        
        every_sync_manager_flows(syncManager)

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

        // Click Exit button
        composeTestRule.onNodeWithContentDescription("앱 종료").performClick()

        // Check if dialog is shown
        composeTestRule.onNodeWithText("앱 종료 확인").assertIsDisplayed()
        composeTestRule.onNodeWithText("앱을 완전히 종료하시겠습니까?").assertIsDisplayed()
        
        // Check for the guide text
        composeTestRule.onNodeWithText("팁: '아니오'를 누르면 앱이 백그라운드에서 동기화를 계속 수행합니다. 뒤로가기 버튼을 눌러도 백그라운드로 전환됩니다.", substring = true).assertIsDisplayed()
        
        // Check buttons
        composeTestRule.onNodeWithText("예 (완전 종료)").assertIsDisplayed()
        composeTestRule.onNodeWithText("아니오 (백그라운드 유지)").assertIsDisplayed()
    }

    private fun every_sync_manager_flows(syncManager: SyncManager) {
        io.mockk.every { syncManager.isSyncing } returns MutableStateFlow(false)
        io.mockk.every { syncManager.syncProgress } returns MutableStateFlow(null)
        io.mockk.every { syncManager.lastSyncResult } returns MutableStateFlow(null)
        io.mockk.every { syncManager.pendingConflicts } returns MutableStateFlow(emptyList())
        io.mockk.every { syncManager.pendingUploads } returns MutableStateFlow(emptyList())
    }
}
