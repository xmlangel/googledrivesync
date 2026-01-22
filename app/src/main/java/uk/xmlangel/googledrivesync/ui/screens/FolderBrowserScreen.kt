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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xmlangel.googledrivesync.data.drive.DriveItem
import uk.xmlangel.googledrivesync.data.drive.DriveServiceHelper
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(
    driveHelper: DriveServiceHelper,
    onFolderSelected: (folderId: String, folderName: String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var currentFolderName by remember { mutableStateOf("내 드라이브") }
    var items by remember { mutableStateOf<List<DriveItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var folderStack by remember { mutableStateOf(listOf<Pair<String?, String>>()) }
    
    LaunchedEffect(currentFolderId) {
        isLoading = true
        error = null
        try {
            val result = driveHelper.listFiles(currentFolderId)
            items = result.files.sortedWith(
                compareByDescending<DriveItem> { it.isFolder }
                    .thenBy { it.name.lowercase() }
            )
        } catch (e: Exception) {
            error = e.message
        }
        isLoading = false
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(currentFolderName)
                        if (folderStack.isNotEmpty()) {
                            Text(
                                text = "폴더 선택",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (folderStack.isNotEmpty()) {
                            val (prevId, prevName) = folderStack.last()
                            folderStack = folderStack.dropLast(1)
                            currentFolderId = prevId
                            currentFolderName = prevName
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
                    onFolderSelected(currentFolderId ?: "root", currentFolderName)
                },
                icon = { Icon(Icons.Default.Check, "선택") },
                text = { Text("이 폴더 선택") }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = error ?: "알 수 없는 오류",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "폴더가 비어있습니다",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(items) { item ->
                            DriveItemRow(
                                item = item,
                                onClick = {
                                    if (item.isFolder) {
                                        folderStack = folderStack + (currentFolderId to currentFolderName)
                                        currentFolderId = item.id
                                        currentFolderName = item.name
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DriveItemRow(
    item: DriveItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = item.isFolder) { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isFolder) 
                MaterialTheme.colorScheme.surfaceVariant 
            else 
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (item.isFolder) Icons.Default.Folder else getFileIcon(item.mimeType),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (item.isFolder) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    Text(
                        text = formatDate(item.modifiedTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!item.isFolder && item.size > 0) {
                        Text(
                            text = " • ${formatSize(item.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            if (item.isFolder) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "열기",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun getFileIcon(mimeType: String) = when {
    mimeType.startsWith("image/") -> Icons.Default.Image
    mimeType.startsWith("video/") -> Icons.Default.VideoFile
    mimeType.startsWith("audio/") -> Icons.Default.AudioFile
    mimeType.contains("pdf") -> Icons.Default.PictureAsPdf
    mimeType.contains("document") || mimeType.contains("word") -> Icons.Default.Description
    mimeType.contains("spreadsheet") || mimeType.contains("excel") -> Icons.Default.TableChart
    else -> Icons.Default.InsertDriveFile
}

private fun formatDate(timestamp: Long): String {
    if (timestamp == 0L) return ""
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
