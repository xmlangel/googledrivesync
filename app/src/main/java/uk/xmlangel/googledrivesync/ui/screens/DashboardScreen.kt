package uk.xmlangel.googledrivesync.ui.screens

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.xmlangel.googledrivesync.R
import uk.xmlangel.googledrivesync.data.local.SyncDatabase
import uk.xmlangel.googledrivesync.data.local.SyncFolderEntity
import uk.xmlangel.googledrivesync.sync.SyncConflict
import uk.xmlangel.googledrivesync.sync.ConflictResolution
import uk.xmlangel.googledrivesync.sync.SyncManager
import uk.xmlangel.googledrivesync.sync.SyncResult
import uk.xmlangel.googledrivesync.sync.SyncWorker
import uk.xmlangel.googledrivesync.util.AppVersionUtil
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    syncManager: SyncManager,
    database: SyncDatabase,
    accountId: String,
    onNavigateToSettings: () -> Unit,
    onNavigateToFolderBrowser: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToSyncedFolder: (folderId: String, folderName: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val versionInfo = remember { AppVersionUtil.getVersionString(context) }
    
    val syncFolders by database.syncFolderDao()
        .getSyncFoldersByAccount(accountId)
        .collectAsState(initial = emptyList())
    
    val isSyncing by syncManager.isSyncing.collectAsState()
    val syncProgress by syncManager.syncProgress.collectAsState()
    val lastSyncResult by syncManager.lastSyncResult.collectAsState()
    val pendingConflicts by syncManager.pendingConflicts.collectAsState()
    val pendingUploads by syncManager.pendingUploads.collectAsState()
    
    var showConflictDialog by remember { mutableStateOf(false) }
    var currentConflict by remember { mutableStateOf<SyncConflict?>(null) }
    
    var showUploadDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    
    // Intercept back button to move app to background
    BackHandler {
        val activity = context as? Activity
        activity?.moveTaskToBack(true)
    }
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(lastSyncResult) {
        lastSyncResult?.let { result ->
            if (result is SyncResult.Success) {
                snackbarHostState.showSnackbar("동기화 완료: ${result.uploaded}개 업로드, ${result.downloaded}개 다운로드")
                syncManager.dismissLastResult()
            } else if (result is SyncResult.Error) {
                snackbarHostState.showSnackbar("오류: ${result.message}")
                syncManager.dismissLastResult()
            }
        }
    }
    
    LaunchedEffect(pendingConflicts) {
        if (pendingConflicts.isNotEmpty()) {
            currentConflict = pendingConflicts.first()
            showConflictDialog = true
        }
    }

    LaunchedEffect(pendingUploads) {
        if (pendingUploads.isNotEmpty() && !isSyncing) {
            showUploadDialog = true
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Drive Sync $versionInfo") },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onNavigateToAccounts,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.AccountCircle, "계정")
                        }
                        IconButton(
                            onClick = {
                                val packageName = "md.obsidian"
                                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                                if (intent != null) {
                                    context.startActivity(intent)
                                } else {
                                    Toast.makeText(context, "Obsidian 앱이 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_obsidian),
                                contentDescription = "Obsidian 실행",
                                modifier = Modifier.size(24.dp),
                                tint = androidx.compose.ui.graphics.Color.Unspecified
                            )
                        }
                        IconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Settings, "설정")
                        }
                        IconButton(
                            onClick = onNavigateToLogs,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.List, "로그")
                        }
                        IconButton(
                            onClick = { showExitDialog = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.ExitToApp, 
                                contentDescription = "앱 종료",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            Column {
                FloatingActionButton(
                    onClick = onNavigateToFolderBrowser,
                    modifier = Modifier.padding(bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.Add, "폴더 추가")
                }
                
                ExtendedFloatingActionButton(
                    onClick = {
                        syncManager.syncAllFolders()
                    },
                    icon = { 
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Sync, "동기화")
                        }
                    },
                    text = { Text(if (isSyncing) "동기화 중..." else "지금 동기화") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    )
 { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Sync Progress
            val currentProgress = syncProgress
            if (isSyncing && currentProgress != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "동기화 중...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentProgress.currentFile,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        currentProgress.statusMessage?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { 
                                if (currentProgress.totalFiles > 0) {
                                    currentProgress.currentIndex.toFloat() / currentProgress.totalFiles 
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${currentProgress.currentIndex} / ${currentProgress.totalFiles}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            // Sync Folders List
            if (syncFolders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(120.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "동기화 폴더 없음",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "+ 버튼을 눌러 동기화할 폴더를 추가하세요",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(syncFolders) { folder ->
                        SyncFolderCard(
                            folder = folder,
                            onToggleEnabled = { enabled ->
                                scope.launch {
                                    database.syncFolderDao().setFolderEnabled(folder.id, enabled)
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    database.syncFolderDao().deleteSyncFolder(folder)
                                }
                            },
                            onClick = {
                                onNavigateToSyncedFolder(folder.id, folder.driveFolderName)
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Conflict Resolution Dialog
    if (showConflictDialog && currentConflict != null) {
        ConflictDialog(
            conflict = currentConflict!!,
            onResolve = { resolution ->
                scope.launch {
                    syncManager.resolveConflict(currentConflict!!, resolution)
                    showConflictDialog = false
                }
            },
            onDismiss = { showConflictDialog = false }
        )
    }

    // Upload Confirmation Dialog
    if (showUploadDialog && pendingUploads.isNotEmpty()) {
        UploadConfirmationDialog(
            pendingUploads = pendingUploads,
            onResolve = { pendingUpload, resolution ->
                scope.launch {
                    syncManager.resolvePendingUpload(pendingUpload, resolution)
                }
            },
            onResolveAll = { resolution ->
                scope.launch {
                    val currentUploads = pendingUploads.toList()
                    currentUploads.forEach { upload ->
                        syncManager.resolvePendingUpload(upload, resolution)
                    }
                    showUploadDialog = false
                }
            },
            onDismiss = { 
                showUploadDialog = false
                syncManager.dismissPendingUploads()
            }
        )
    }

    // Exit Confirmation Dialog
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("앱 종료 확인")
                }
            },
            text = {
                Column {
                    Text("앱을 완전히 종료하시겠습니까?")
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "팁: '아니오'를 누르면 앱이 백그라운드에서 동기화를 계속 수행합니다. 뒤로가기 버튼을 눌러도 백그라운드로 전환됩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val activity = context as? Activity
                        // Cancel background sync and stop monitoring
                        SyncWorker.cancel(context)
                        syncManager.stopMonitoringFolders()
                        activity?.finish()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("예 (완전 종료)")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        val activity = context as? Activity
                        activity?.moveTaskToBack(true)
                    }
                ) {
                    Text("아니오 (백그라운드 유지)")
                }
            }
        )
    }
}

@Composable
fun SyncFolderCard(
    folder: SyncFolderEntity,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.driveFolderName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = folder.localPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = folder.isEnabled,
                    onCheckedChange = onToggleEnabled
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (folder.lastSyncedAt > 0) 
                        "마지막 동기화: ${formatDate(folder.lastSyncedAt)}"
                    else 
                        "동기화 안됨",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                TextButton(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("삭제")
                }
            }
        }
    }
    
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("폴더 삭제") },
            text = { Text("이 동기화 폴더를 삭제하시겠습니까?\n로컬 및 Drive의 파일은 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
fun ConflictDialog(
    conflict: SyncConflict,
    onResolve: (ConflictResolution) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("파일 충돌")
            }
        },
        text = {
            Column {
                Text(
                    text = conflict.localFileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("로컬 파일", style = MaterialTheme.typography.labelMedium)
                        Text("수정: ${formatDate(conflict.localModifiedAt)}")
                        Text("크기: ${formatSize(conflict.localSize)}")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Drive 파일", style = MaterialTheme.typography.labelMedium)
                        Text("수정: ${formatDate(conflict.driveModifiedAt)}")
                        Text("크기: ${formatSize(conflict.driveSize)}")
                    }
                }
            }
        },
        confirmButton = {
            Column {
                Row {
                    TextButton(onClick = { onResolve(ConflictResolution.USE_LOCAL) }) {
                        Text("로컬 사용")
                    }
                    TextButton(onClick = { onResolve(ConflictResolution.USE_DRIVE) }) {
                        Text("Drive 사용")
                    }
                }
                Row {
                    TextButton(onClick = { onResolve(ConflictResolution.KEEP_BOTH) }) {
                        Text("둘 다 유지")
                    }
                    TextButton(onClick = { onResolve(ConflictResolution.SKIP) }) {
                        Text("건너뛰기")
                    }
                }
            }
        }
    )
}

@Composable
fun UploadConfirmationDialog(
    pendingUploads: List<uk.xmlangel.googledrivesync.sync.PendingUpload>,
    onResolve: (uk.xmlangel.googledrivesync.sync.PendingUpload, uk.xmlangel.googledrivesync.sync.PendingUploadResolution) -> Unit,
    onResolveAll: (uk.xmlangel.googledrivesync.sync.PendingUploadResolution) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Upload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("업로드 확인")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "다음 파일들을 Google Drive에 업로드하시겠습니까?",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "주의: 드라이브에 이미 동일한 이름의 파일이 있는 경우 중복 파일이 생성될 수 있습니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pendingUploads) { upload ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = upload.localFile.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (upload.isNewFile) "새 파일" else "수정됨",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { onResolve(upload, uk.xmlangel.googledrivesync.sync.PendingUploadResolution.UPLOAD) }) {
                                    Icon(Icons.Default.Check, "업로드", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { onResolve(upload, uk.xmlangel.googledrivesync.sync.PendingUploadResolution.SKIP) }) {
                                    Icon(Icons.Default.Close, "건너뛰기", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolveAll(uk.xmlangel.googledrivesync.sync.PendingUploadResolution.UPLOAD) }) {
                Text("모두 업로드")
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolveAll(uk.xmlangel.googledrivesync.sync.PendingUploadResolution.SKIP) }) {
                Text("모두 건너뛰기")
            }
        }
    )
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
