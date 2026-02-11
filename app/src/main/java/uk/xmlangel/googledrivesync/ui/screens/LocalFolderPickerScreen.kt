package uk.xmlangel.googledrivesync.ui.screens

import android.os.Environment
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
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFolderPickerScreen(
    driveFolderId: String,
    driveFolderName: String,
    isExistingDriveFolder: Boolean,
    onFolderSelected: (localPath: String, clearLocalData: Boolean) -> Unit,
    onNavigateBack: () -> Unit
) {
    val rootPath = Environment.getExternalStorageDirectory().absolutePath
    var currentPath by remember { mutableStateOf(rootPath) }
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var pathStack by remember { mutableStateOf(listOf<String>()) }
    var showSelectionConfirmDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(currentPath) {
        val dir = File(currentPath)
        folders = dir.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("로컬 폴더 선택")
                        Text(
                            text = "Drive: $driveFolderName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (pathStack.isNotEmpty()) {
                            currentPath = pathStack.last()
                            pathStack = pathStack.dropLast(1)
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (isExistingDriveFolder) {
                        showSelectionConfirmDialog = true
                    } else {
                        onFolderSelected(currentPath, false)
                    }
                },
                icon = { Icon(Icons.Default.Check, "선택") },
                text = { Text("이 폴더 선택") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Current path display
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentPath.replace(rootPath, "내부 저장소"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            if (folders.isEmpty()) {
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
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("하위 폴더 없음")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(folders) { folder ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    pathStack = pathStack + currentPath
                                    currentPath = folder.absolutePath
                                },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSelectionConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSelectionConfirmDialog = false },
            title = { Text("로컬 데이터 처리 선택") },
            text = {
                Column {
                    Text(
                        "기존 Drive 폴더를 선택했습니다.\n" +
                            "초기 정합성을 위해 로컬 폴더 데이터를 비우고 시작하는 것을 권장합니다."
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AssistChip(
                        onClick = { },
                        enabled = false,
                        label = { Text("권장: 삭제 후 연결") }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "삭제하지 않으면 중복 파일 업로드/삭제가 발생할 수 있습니다.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSelectionConfirmDialog = false
                        onFolderSelected(currentPath, true)
                    }
                ) {
                    Text("삭제 후 연결 (권장)")
                }
            },
            dismissButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    onClick = {
                        showSelectionConfirmDialog = false
                        onFolderSelected(currentPath, false)
                    }
                ) {
                    Text("삭제 안함")
                }
            }
        )
    }

}
