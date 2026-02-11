package uk.xmlangel.googledrivesync.ui.navigation

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    object SyncExclusions : Screen("sync_exclusions")
    object SyncLogs : Screen("sync_logs")
    object SyncVerificationReport : Screen("sync_verification/{folderId}/{folderName}") {
        fun createRoute(folderId: String, folderName: String) =
            "sync_verification/$folderId/$folderName"
    }
    object SyncedFolderFile : Screen("synced_folder_files/{folderId}/{folderName}") {
        fun createRoute(folderId: String, folderName: String) = 
            "synced_folder_files/$folderId/$folderName"
    }
    object LocalFolderPicker : Screen("local_folder_picker/{driveFolderId}/{driveFolderName}/{isExistingDriveFolder}") {
        fun createRoute(driveFolderId: String, driveFolderName: String, isExistingDriveFolder: Boolean) =
            "local_folder_picker/$driveFolderId/$driveFolderName/$isExistingDriveFolder"
    }
}

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Account.route,
    initialRoute: String? = null
) {
    val context = LocalContext.current
    
    // Handle initial navigation from notification
    LaunchedEffect(initialRoute) {
        initialRoute?.let {
            navController.navigate(it)
        }
    }
    
    // Initialize dependencies
    val database = remember { SyncDatabase.getInstance(context) }
    val accountRepository = remember { 
        AccountRepository(
            context, 
            database.syncFolderDao(),
            database.syncItemDao(),
            database.syncHistoryDao()
        ) 
    }
    val driveHelper = remember { DriveServiceHelper(context) }
    val syncManager = remember { SyncManager.getInstance(context) }
    val syncPreferences = remember { SyncPreferences(context) }
    
    val activeAccount by accountRepository.activeAccount.collectAsState()
    
    // Initialize Drive service when account is available
    LaunchedEffect(activeAccount) {
        if (activeAccount != null) {
            driveHelper.initializeDriveService(activeAccount?.email)
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
                    navController.navigate(Screen.Dashboard.route) {
                        // Pop up to the account screen and make it inclusive to clear the stack
                        popUpTo(Screen.Account.route) { inclusive = true }
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToLogs = {
                    navController.navigate(Screen.SyncLogs.route)
                },
                autoNavigate = navController.previousBackStackEntry == null
            )
        }
        
        composable(Screen.Dashboard.route) {
            if (activeAccount != null) {
                DashboardScreen(
                    syncManager = syncManager,
                    database = database,
                    accountId = activeAccount!!.id,
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToFolderBrowser = {
                        navController.navigate(Screen.FolderBrowser.route)
                    },
                    onNavigateToAccounts = {
                        // When going back to accounts, we don't want to clear the stack 
                        // as we want to be able to come back to the dashboard if we cancel.
                        navController.navigate(Screen.Account.route)
                    },
                    onNavigateToLogs = {
                        navController.navigate(Screen.SyncLogs.route)
                    },
                    onNavigateToSyncedFolder = { folderId, folderName ->
                        navController.navigate(Screen.SyncedFolderFile.createRoute(folderId, folderName))
                    }
                )
            } else {
                // Fallback to prevent white screen while activeAccount is loading or if it's null
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                
                // If it remains null for some reason, we should eventually redirect to AccountScreen
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1000)
                    if (activeAccount == null) {
                        navController.navigate(Screen.Account.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            }
        }
        
        composable(Screen.FolderBrowser.route) {
            FolderBrowserScreen(
                driveHelper = driveHelper,
                onFolderSelected = { folderId, folderName, isExistingFolder ->
                    navController.navigate(
                        Screen.LocalFolderPicker.createRoute(folderId, folderName, isExistingFolder)
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
            val isExistingDriveFolder = backStackEntry.arguments?.getString("isExistingDriveFolder")?.toBooleanStrictOrNull() ?: true
            
            LocalFolderPickerScreen(
                driveFolderId = driveFolderId,
                driveFolderName = driveFolderName,
                isExistingDriveFolder = isExistingDriveFolder,
                onFolderSelected = { localPath: String, clearLocalData: Boolean ->
                    activeAccount?.let { account ->
                        CoroutineScope(Dispatchers.Main).launch {
                            if (clearLocalData) {
                                val cleared = syncManager.clearLocalFolderContents(localPath)
                                if (!cleared) {
                                    Toast.makeText(
                                        context,
                                        "로컬 폴더 초기화 중 일부 항목 삭제에 실패했습니다.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } else if (isExistingDriveFolder) {
                                Toast.makeText(
                                    context,
                                    "경고: 삭제 없이 연결하면 중복 파일 업로드/삭제가 발생할 수 있습니다.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            syncManager.addSyncFolder(
                                accountId = account.id,
                                accountEmail = account.email,
                                localPath = localPath,
                                driveFolderId = driveFolderId,
                                driveFolderName = driveFolderName
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
                onNavigateToExclusions = {
                    navController.navigate(Screen.SyncExclusions.route)
                },
                onScheduleSync = {
                    SyncWorker.schedule(context)
                    if (syncPreferences.autoSyncEnabled && syncPreferences.realtimeSyncEnabled) {
                        syncManager.startMonitoringFolders()
                    } else {
                        syncManager.stopMonitoringFolders()
                    }
                }
            )
        }

        composable(Screen.SyncExclusions.route) {
            SyncExclusionsScreen(
                database = database,
                syncPreferences = syncPreferences,
                onNavigateBack = {
                    navController.popBackStack()
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

        composable(Screen.SyncedFolderFile.route) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: ""
            val folderName = backStackEntry.arguments?.getString("folderName") ?: ""
            
            SyncedFolderFileScreen(
                database = database,
                folderId = folderId,
                folderName = folderName,
                onNavigateToVerification = { verifyFolderId, verifyFolderName ->
                    navController.navigate(
                        Screen.SyncVerificationReport.createRoute(verifyFolderId, verifyFolderName)
                    )
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.SyncVerificationReport.route) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: ""
            val folderName = backStackEntry.arguments?.getString("folderName") ?: ""
            SyncVerificationReportScreen(
                database = database,
                syncManager = syncManager,
                folderId = folderId,
                folderName = folderName,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
