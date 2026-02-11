package uk.xmlangel.googledrivesync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.xmlangel.googledrivesync.data.local.SyncDatabase
import uk.xmlangel.googledrivesync.sync.SyncManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncVerificationReportScreen(
    database: SyncDatabase,
    syncManager: SyncManager,
    folderId: String,
    folderName: String,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var loading by remember { mutableStateOf(true) }
    var runningVerification by remember { mutableStateOf(false) }
    var runningForcePull by remember { mutableStateOf(false) }
    var showForcePullDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reportContent by remember { mutableStateOf<String?>(null) }
    var reportPath by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf<Long?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var syncLocalRootPath by remember { mutableStateOf<String?>(null) }
    var syncDriveFolderId by remember { mutableStateOf<String?>(null) }
    var forcePullConfirmed by remember { mutableStateOf(false) }
    var lastForcePullSummary by remember { mutableStateOf<String?>(null) }
    var forcePullPreview by remember { mutableStateOf<SyncManager.ForcePullPreview?>(null) }
    var loadingForcePullPreview by remember { mutableStateOf(false) }
    var forcePullPreviewError by remember { mutableStateOf<String?>(null) }
    var showLeaveWhileForcePullDialog by remember { mutableStateOf(false) }
    var forcePullStartedAt by remember { mutableStateOf<Long?>(null) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val syncProgress by syncManager.syncProgress.collectAsState()

    val requestNavigateBack: () -> Unit = {
        if (runningForcePull) {
            showLeaveWhileForcePullDialog = true
        } else {
            onNavigateBack()
        }
    }

    BackHandler {
        requestNavigateBack()
    }

    LaunchedEffect(runningForcePull) {
        if (runningForcePull && forcePullStartedAt == null) {
            forcePullStartedAt = System.currentTimeMillis()
        } else if (!runningForcePull) {
            forcePullStartedAt = null
        }
    }

    LaunchedEffect(runningForcePull) {
        while (runningForcePull) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    LaunchedEffect(folderId, reloadKey) {
        loading = true
        errorMessage = null
        try {
            val folder = withContext(Dispatchers.IO) {
                database.syncFolderDao().getSyncFolderById(folderId)
            }
            if (folder == null) {
                errorMessage = "동기화 폴더를 찾을 수 없습니다."
                syncLocalRootPath = null
                syncDriveFolderId = null
            } else {
                syncLocalRootPath = File(folder.localPath).absolutePath
                syncDriveFolderId = folder.driveFolderId
                val verifyFile = File(File(folder.localPath, "conflicts_backup"), "verify.md")
                reportPath = verifyFile.absolutePath
                if (verifyFile.exists()) {
                    reportContent = withContext(Dispatchers.IO) { verifyFile.readText(Charsets.UTF_8) }
                    updatedAt = verifyFile.lastModified()
                } else {
                    reportContent = null
                    updatedAt = null
                }
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "검증 리포트를 불러오지 못했습니다."
        } finally {
            loading = false
        }
    }

    val resultLine = reportContent
        ?.lineSequence()
        ?.firstOrNull { it.startsWith("- Result:") }
        ?.removePrefix("- Result:")
        ?.trim()

    val resultColor = when (resultLine) {
        "PASS" -> MaterialTheme.colorScheme.primary
        "FAIL" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("검증 리포트")
                        Text(
                            text = folderName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = requestNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            forcePullConfirmed = false
                            forcePullPreview = null
                            forcePullPreviewError = null
                            loadingForcePullPreview = true
                            showForcePullDialog = true
                            scope.launch {
                                try {
                                    forcePullPreview = syncManager.previewForcePullFromServer(
                                        folderId = folderId,
                                        sampleLimit = 10
                                    )
                                } catch (e: Exception) {
                                    forcePullPreviewError = e.message ?: e.javaClass.simpleName
                                } finally {
                                    loadingForcePullPreview = false
                                }
                            }
                        },
                        enabled = !runningVerification && !runningForcePull
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "강제 서버 동기화")
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                runningVerification = true
                                try {
                                    val execution = syncManager.runVerificationOnly(folderId)
                                    val message = "수동 비교 결과: ${execution.result} | ${execution.summary}"
                                    snackbarHostState.showSnackbar(message)
                                    reloadKey++
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("수동 비교 실패: ${e.message ?: e.javaClass.simpleName}")
                                } finally {
                                    runningVerification = false
                                }
                            }
                        },
                        enabled = !runningVerification && !runningForcePull
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "수동 비교 실행")
                    }
                    IconButton(onClick = { reloadKey++ }, enabled = !runningVerification && !runningForcePull) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (runningVerification) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            if (runningForcePull) {
                                Text(
                                    text = "서버 파일 강제 동기화 실행 중...",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val progress = syncProgress
                                val progressRatio = if (progress != null && progress.totalFiles > 0) {
                                    (progress.currentIndex.toFloat() / progress.totalFiles.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                val elapsedMs = forcePullStartedAt?.let { nowMs - it } ?: 0L
                                LinearProgressIndicator(
                                    progress = { progressRatio },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                val progressPercent = (progressRatio * 100f).roundToInt()
                                Text(
                                    text = progress?.statusMessage ?: "강제 동기화 진행 중...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = if (progress != null) {
                                        "${progress.currentFile} • ${progress.currentIndex}/${progress.totalFiles} (${progressPercent}%)"
                                    } else {
                                        "진행률 계산 중..."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "경과 시간: ${formatElapsedTime(elapsedMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Text(
                                text = "검증 결과: ${resultLine ?: "리포트 없음"}",
                                color = resultColor,
                                style = MaterialTheme.typography.titleMedium
                            )
                            updatedAt?.let {
                                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it))
                                Text(
                                    text = "갱신 시각: $time",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            reportPath?.let {
                                Text(
                                    text = "경로: $it",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            lastForcePullSummary?.let {
                                Text(
                                    text = "마지막 강제 동기화: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    if (reportContent.isNullOrBlank()) {
                        Text("검증 리포트가 없습니다. 동기화를 실행하면 생성됩니다.")
                    } else {
                        SelectionContainer {
                            Text(
                                text = reportContent ?: "",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }

    if (showForcePullDialog) {
        AlertDialog(
            onDismissRequest = { showForcePullDialog = false },
            title = { Text("강제 서버 동기화") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("로컬 변경사항을 무시하고 서버 상태로 강제 동기화합니다.")
                    Text("동기화 로컬 경로: ${syncLocalRootPath ?: "(확인 불가)"}")
                    Text("동기화 Drive 폴더 ID: ${syncDriveFolderId ?: "(확인 불가)"}")
                    Text("삭제/정리 동작:")
                    Text("- 서버에 없는 로컬 파일은 삭제됩니다.")
                    Text("- 서버에 없는 로컬 폴더는 비어 있으면 삭제됩니다.")
                    Text("- conflicts_backup 폴더는 삭제 대상에서 제외됩니다.")
                    if (loadingForcePullPreview) {
                        CircularProgressIndicator()
                        Text("삭제 예정 항목을 계산하는 중...")
                    } else if (forcePullPreviewError != null) {
                        Text(
                            text = "삭제 예정 항목 계산 실패: $forcePullPreviewError",
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        val preview = forcePullPreview
                        if (preview != null) {
                            Text("삭제 예정 요약: ${preview.summary}")
                            Text("삭제 후보 샘플(최대 10개):")
                            if (preview.sampleRemovalCandidates.isEmpty()) {
                                Text("- 없음")
                            } else {
                                preview.sampleRemovalCandidates.forEach { item ->
                                    Text("- $item")
                                }
                                if (preview.hasMoreCandidates) {
                                    val hiddenCount = preview.totalRemovalCandidates - preview.sampleRemovalCandidates.size
                                    Text("- ... 외 ${hiddenCount}개")
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = forcePullConfirmed,
                            onCheckedChange = { forcePullConfirmed = it }
                        )
                        Text("위 내용을 확인했고, 삭제 가능성을 이해했습니다.")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForcePullDialog = false
                        scope.launch {
                            runningForcePull = true
                            try {
                                val execution = syncManager.forcePullFromServer(folderId)
                                lastForcePullSummary = execution.summary
                                snackbarHostState.showSnackbar(execution.summary)
                                reloadKey++
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("강제 서버 동기화 실패: ${e.message ?: e.javaClass.simpleName}")
                            } finally {
                                runningForcePull = false
                            }
                        }
                    },
                    enabled = forcePullConfirmed && !loadingForcePullPreview
                ) {
                    Text("삭제 포함 실행")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForcePullDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    if (showLeaveWhileForcePullDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveWhileForcePullDialog = false },
            title = { Text("강제 동기화 진행 중") },
            text = {
                Text("지금 화면을 벗어나면 강제 서버 동기화가 중지될 수 있습니다. 계속 진행할까요?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveWhileForcePullDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("나가기 (동기화 중지)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveWhileForcePullDialog = false }) {
                    Text("화면 유지")
                }
            }
        )
    }
}

private fun formatElapsedTime(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
