package uk.xmlangel.googledrivesync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.xmlangel.googledrivesync.data.local.SyncDatabase
import uk.xmlangel.googledrivesync.data.local.SyncPreferences
import uk.xmlangel.googledrivesync.sync.SyncExclusionType
import uk.xmlangel.googledrivesync.sync.SyncExclusions
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncExclusionsScreen(
    database: SyncDatabase,
    syncPreferences: SyncPreferences,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val defaultRules = remember { SyncExclusions.defaults() }
    val obsidianExcludeToken = remember {
        SyncExclusions.buildUserRuleToken(SyncExclusionType.DIRECTORY, ".obsidian") ?: "directory:.obsidian"
    }
    var userRules by remember { mutableStateOf(SyncExclusions.parseUserRules(syncPreferences.userExcludedPaths)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var loadingCandidates by remember { mutableStateOf(false) }
    var candidateFilePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var candidateDirectoryPaths by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refreshUserRules() {
        userRules = SyncExclusions.parseUserRules(syncPreferences.userExcludedPaths)
    }

    LaunchedEffect(Unit) {
        syncPreferences.ensureObsidianExclusionDefault()
        refreshUserRules()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("동기화 제외 목록") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            showAddDialog = true
                            loadingCandidates = true
                            scope.launch {
                                val filePaths = loadSelectableRelativeFilePaths(database)
                                candidateFilePaths = filePaths
                                    .filterNot { path ->
                                        val token = SyncExclusions.buildUserRuleToken(SyncExclusionType.FILE, path)
                                        token != null && syncPreferences.userExcludedPaths.contains(token)
                                    }
                                candidateDirectoryPaths = extractDirectoryCandidates(filePaths)
                                    .filterNot { dir ->
                                        val token = SyncExclusions.buildUserRuleToken(SyncExclusionType.DIRECTORY, dir)
                                        token != null && syncPreferences.userExcludedPaths.contains(token)
                                    }
                                loadingCandidates = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.NoteAdd, contentDescription = "제외 규칙 추가")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("기본 제외", fontWeight = FontWeight.Bold)
                    Text("시스템 기본 제외 항목은 제거할 수 없습니다.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val obsidianExcluded = syncPreferences.userExcludedPaths.contains(obsidianExcludeToken)
                    Checkbox(
                        checked = obsidianExcluded,
                        onCheckedChange = { checked ->
                            if (checked) {
                                syncPreferences.addUserExcludedRule(SyncExclusionType.DIRECTORY, ".obsidian")
                            } else {
                                syncPreferences.removeUserExcludedRule(obsidianExcludeToken)
                            }
                            refreshUserRules()
                        }
                    )
                    Column {
                        Text(".obsidian 제외", fontWeight = FontWeight.SemiBold)
                        Text("기본 체크: 동기화 제외(업/다운로드 모두 안 함)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text("기본 제외 항목 (${defaultRules.size})", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(defaultRules) { rule ->
                    Card {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.weight(0.05f))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("[${rule.type.name}] ${rule.value}", fontWeight = FontWeight.SemiBold)
                                Text(rule.reason, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Text("사용자 제외 항목 (${userRules.size})", style = MaterialTheme.typography.titleMedium)
            if (userRules.isEmpty()) {
                Text("추가된 사용자 제외 항목이 없습니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(userRules) { rule ->
                        Card {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Block, contentDescription = null)
                                Spacer(modifier = Modifier.weight(0.05f))
                                Text("[${rule.type.name}] ${rule.value}", modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        syncPreferences.removeUserExcludedRule(rule.toStorageToken())
                                        refreshUserRules()
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "제외 목록에서 제거")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var selectedType by remember { mutableStateOf(SyncExclusionType.FILE) }
        var patternInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("제외 규칙 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("규칙 타입을 선택하세요.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedType == SyncExclusionType.FILE, onClick = { selectedType = SyncExclusionType.FILE })
                        Text("파일")
                        Spacer(modifier = Modifier.weight(1f))
                        RadioButton(selected = selectedType == SyncExclusionType.DIRECTORY, onClick = { selectedType = SyncExclusionType.DIRECTORY })
                        Text("폴더(하위 포함)")
                        Spacer(modifier = Modifier.weight(1f))
                        RadioButton(selected = selectedType == SyncExclusionType.PATTERN, onClick = { selectedType = SyncExclusionType.PATTERN })
                        Text("패턴")
                    }

                    when {
                        loadingCandidates -> Text("목록을 불러오는 중...")
                        selectedType == SyncExclusionType.FILE -> {
                            Text("파일 선택")
                            RuleCandidatesList(candidateFilePaths) { selected ->
                                syncPreferences.addUserExcludedRule(SyncExclusionType.FILE, selected)
                                refreshUserRules()
                            }
                        }
                        selectedType == SyncExclusionType.DIRECTORY -> {
                            Text("폴더 선택 (선택한 폴더의 하위 전체 제외)")
                            RuleCandidatesList(candidateDirectoryPaths) { selected ->
                                syncPreferences.addUserExcludedRule(SyncExclusionType.DIRECTORY, selected)
                                refreshUserRules()
                            }
                        }
                        else -> {
                            Text("패턴 입력 (예: *.tmp, .obsidian/*.bak)")
                            OutlinedTextField(
                                value = patternInput,
                                onValueChange = { patternInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            TextButton(
                                onClick = {
                                    syncPreferences.addUserExcludedRule(SyncExclusionType.PATTERN, patternInput)
                                    refreshUserRules()
                                    patternInput = ""
                                }
                            ) {
                                Text("패턴 추가")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }
}

@Composable
private fun RuleCandidatesList(
    candidates: List<String>,
    onAdd: (String) -> Unit
) {
    if (candidates.isEmpty()) {
        Text("추가 가능한 항목이 없습니다.")
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(candidates) { path ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onAdd(path) }) {
                        Text("추가")
                    }
                }
            }
        }
    }
}

private suspend fun loadSelectableRelativeFilePaths(database: SyncDatabase): List<String> {
    return withContext(Dispatchers.IO) {
        val folders = database.syncFolderDao().getAllSyncFoldersOnce().associateBy { it.id }
        val items = database.syncItemDao().getAllSyncItems()
        val relativePaths = linkedSetOf<String>()

        for (item in items) {
            val folder = folders[item.syncFolderId] ?: continue
            val localFile = File(item.localPath)
            if (!localFile.isFile) continue
            val relative = try {
                localFile.relativeTo(File(folder.localPath)).invariantSeparatorsPath
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (relative.isBlank()) continue
            relativePaths += SyncExclusions.normalizeRelativePath(relative)
        }
        relativePaths.sorted()
    }
}

private fun extractDirectoryCandidates(filePaths: List<String>): List<String> {
    val dirs = linkedSetOf<String>()
    for (path in filePaths) {
        val parent = File(path).parent?.replace('\\', '/') ?: continue
        if (parent.isNotBlank() && parent != ".") {
            dirs += parent
        }
    }
    return dirs.sorted()
}
