package uk.xmlangel.googledrivesync.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.xmlangel.googledrivesync.data.local.SyncDatabase
import uk.xmlangel.googledrivesync.data.local.SyncFolderEntity
import uk.xmlangel.googledrivesync.sync.SyncConflict
import uk.xmlangel.googledrivesync.sync.ConflictResolution
import uk.xmlangel.googledrivesync.sync.SyncManager
import uk.xmlangel.googledrivesync.sync.SyncResult
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
    
    val syncFolders by database.syncFolderDao()
        .getSyncFoldersByAccount(accountId)
        .collectAsState(initial = emptyList())
    
    val isSyncing by syncManager.isSyncing.collectAsState()
    val syncProgress by syncManager.syncProgress.collectAsState()
    val lastSyncResult by syncManager.lastSyncResult.collectAsState()
    val pendingConflicts by syncManager.pendingConflicts.collectAsState()
    
    var showConflictDialog by remember { mutableStateOf(false) }
    var currentConflict by remember { mutableStateOf<SyncConflict?>(null) }
    
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Drive Sync") },
                actions = {
                    IconButton(onClick = onNavigateToAccounts) {
                        Icon(Icons.Default.AccountCircle, "계정")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "설정")
                    }
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Default.List, "로그")
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
