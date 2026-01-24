package uk.xmlangel.googledrivesync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.xmlangel.googledrivesync.util.SyncLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val logger = remember { SyncLogger(context) }
    var logs by remember { mutableStateOf(emptyList<String>()) }
    
    LaunchedEffect(Unit) {
        logs = logger.readLogs()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("동기화 로그") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        logger.clearLogs()
                        logs = emptyList()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "로그 삭제")
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text("기록된 로그가 없습니다.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs) { log ->
                    LogEntry(log)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
fun LogEntry(log: String) {
    Text(
        text = log,
        fontSize = 12.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
    )
}
