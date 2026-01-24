package uk.xmlangel.googledrivesync.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.xmlangel.googledrivesync.data.drive.DriveServiceHelper
import uk.xmlangel.googledrivesync.data.local.SyncDatabase
import uk.xmlangel.googledrivesync.data.local.SyncPreferences
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.data.repository.AccountRepository
import uk.xmlangel.googledrivesync.sync.SyncManager
import uk.xmlangel.googledrivesync.sync.SyncWorker
import uk.xmlangel.googledrivesync.ui.screens.*

sealed class Screen(val route: String) {
    object Account : Screen("account")
    object Dashboard : Screen("dashboard")
    object FolderBrowser : Screen("folder_browser")
    object Settings : Screen("settings")
    object SyncLogs : Screen("sync_logs")
    object LocalFolderPicker : Screen("local_folder_picker/{driveFolderId}/{driveFolderName}") {
        fun createRoute(driveFolderId: String, driveFolderName: String) = 
            "local_folder_picker/$driveFolderId/$driveFolderName"
    }
}

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Account.route
) {
    val context = LocalContext.current
    
    // Initialize dependencies
    val accountRepository = remember { AccountRepository(context) }
    val driveHelper = remember { DriveServiceHelper(context) }
    val syncManager = remember { SyncManager(context) }
    val database = remember { SyncDatabase.getInstance(context) }
    val syncPreferences = remember { SyncPreferences(context) }
    
    val activeAccount by accountRepository.activeAccount.collectAsState()
    
    // Initialize Drive service when account is available
    LaunchedEffect(activeAccount) {
        if (activeAccount != null) {
            driveHelper.initializeDriveService()
            syncManager.initialize()
        }
    }
    
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Account.route) {
            AccountScreen(
                accountRepository = accountRepository,
                onNavigateToFolders = {
                    navController.navigate(Screen.Dashboard.route)
                }
            )
        }
        
        composable(Screen.Dashboard.route) {
            activeAccount?.let { account ->
                DashboardScreen(
                    syncManager = syncManager,
                    database = database,
                    accountId = account.id,
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToFolderBrowser = {
                        navController.navigate(Screen.FolderBrowser.route)
                    },
                    onNavigateToAccounts = {
                        navController.navigate(Screen.Account.route)
                    },
                    onNavigateToLogs = {
                        navController.navigate(Screen.SyncLogs.route)
                    }
                )
            }
        }
        
        composable(Screen.FolderBrowser.route) {
            FolderBrowserScreen(
                driveHelper = driveHelper,
                onFolderSelected = { folderId, folderName ->
                    navController.navigate(
                        Screen.LocalFolderPicker.createRoute(folderId, folderName)
                    )
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(Screen.LocalFolderPicker.route) { backStackEntry ->
            val driveFolderId = backStackEntry.arguments?.getString("driveFolderId") ?: "root"
            val driveFolderName = backStackEntry.arguments?.getString("driveFolderName") ?: "내 드라이브"
            
            LocalFolderPickerScreen(
                driveFolderId = driveFolderId,
                driveFolderName = driveFolderName,
                onFolderSelected = { localPath: String ->
                    activeAccount?.let { account ->
                        CoroutineScope(Dispatchers.Main).launch {
                            syncManager.addSyncFolder(
                                accountId = account.id,
                                localPath = localPath,
                                driveFolderId = driveFolderId,
                                driveFolderName = driveFolderName,
                                direction = SyncDirection.BIDIRECTIONAL
                            )
                        }
                    }
                    navController.popBackStack(Screen.Dashboard.route, false)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(Screen.Settings.route) {
            SyncSettingsScreen(
                syncPreferences = syncPreferences,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onScheduleSync = {
                    SyncWorker.schedule(context)
                }
            )
        }

        composable(Screen.SyncLogs.route) {
            SyncLogScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
