package uk.xmlangel.googledrivesync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import uk.xmlangel.googledrivesync.data.local.SyncPreferences
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.sync.ConflictResolution

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    syncPreferences: SyncPreferences,
    onNavigateBack: () -> Unit,
    onScheduleSync: () -> Unit
) {
    val context = LocalContext.current
    var syncInterval by remember { mutableStateOf(syncPreferences.syncIntervalMinutes) }
    var wifiOnly by remember { mutableStateOf(syncPreferences.syncWifiOnly) }
    var whileCharging by remember { mutableStateOf(syncPreferences.syncWhileCharging) }
    var autoSync by remember { mutableStateOf(syncPreferences.autoSyncEnabled) }
    var notifications by remember { mutableStateOf(syncPreferences.notificationsEnabled) }
    var defaultConflictResolution by remember { mutableStateOf(syncPreferences.defaultConflictResolution) }
    var defaultSyncDirection by remember { mutableStateOf(syncPreferences.defaultSyncDirection) }
    
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showConflictResolutionDialog by remember { mutableStateOf(false) }
    var showSyncDirectionDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("동기화 설정") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Auto Sync Section
            SettingsSection(title = "자동 동기화") {
                SettingsSwitchItem(
                    icon = Icons.Default.Sync,
                    title = "자동 동기화",
                    subtitle = "백그라운드에서 자동으로 동기화",
                    checked = autoSync,
                    onCheckedChange = {
                        autoSync = it
                        syncPreferences.autoSyncEnabled = it
                        onScheduleSync()
                    }
                )
                
                if (autoSync) {
                    SettingsClickItem(
                        icon = Icons.Default.Schedule,
                        title = "동기화 주기",
                        subtitle = syncPreferences.formatInterval(syncInterval),
                        onClick = { showIntervalDialog = true }
                    )
                    
                    Text(
                        text = "* Android 시스템 제한으로 인해 백그라운드 동기화는 최소 15분 주기로 동작하며, 시스템 상황에 따라 지연될 수 있습니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            
            // System Section
            SettingsSection(title = "시스템 설정") {
                SettingsClickItem(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "배터리 최적화 예외 설정",
                    subtitle = "더 안정적인 백그라운드 동기화를 위해 필요합니다",
                    onClick = {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        } else {
                            null
                        }
                        intent?.let { context.startActivity(it) }
                    }
                )
                Text(
                    text = "* 동기화가 자주 끊긴다면 배터리 최적화를 '제한 없음'으로 설정해주세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            
            // Network Section
            SettingsSection(title = "네트워크") {
                SettingsSwitchItem(
                    icon = Icons.Default.Wifi,
                    title = "Wi-Fi에서만 동기화",
                    subtitle = "모바일 데이터 사용 방지",
                    checked = wifiOnly,
                    onCheckedChange = {
                        wifiOnly = it
                        syncPreferences.syncWifiOnly = it
                        onScheduleSync()
                    }
                )
                
                SettingsSwitchItem(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "충전 중에만 동기화",
                    subtitle = "배터리 소모 방지",
                    checked = whileCharging,
                    onCheckedChange = {
                        whileCharging = it
                        syncPreferences.syncWhileCharging = it
                        onScheduleSync()
                    }
                )
            }
            
            // Notifications Section
            SettingsSection(title = "알림") {
                SettingsSwitchItem(
                    icon = Icons.Default.Notifications,
                    title = "동기화 알림",
                    subtitle = "동기화 완료 및 오류 발생 시 알림 표시",
                    checked = notifications,
                    onCheckedChange = {
                        notifications = it
                        syncPreferences.notificationsEnabled = it
                    }
                )
                
                if (notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    SettingsClickItem(
                        icon = Icons.Default.NotificationsActive,
                        title = "알림 권한 설정",
                        subtitle = "시스템에서 알림 권한을 허용해야 합니다",
                        onClick = {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
            
            // Conflict Resolution Section
            SettingsSection(title = "충돌 해결") {
                SettingsClickItem(
                    icon = Icons.Default.Warning,
                    title = "기본 충돌 해결 전략",
                    subtitle = when (defaultConflictResolution) {
                        ConflictResolution.USE_LOCAL -> "로컬 사용"
                        ConflictResolution.USE_DRIVE -> "Drive 사용"
                        ConflictResolution.KEEP_BOTH -> "둘 다 유지"
                        ConflictResolution.SKIP -> "건너뛰기"
                        null -> "매번 확인"
                    },
                    onClick = { showConflictResolutionDialog = true }
                )
                
                SettingsClickItem(
                    icon = Icons.Default.SyncAlt,
                    title = "기본 동기화 모드",
                    subtitle = when (defaultSyncDirection) {
                        SyncDirection.BIDIRECTIONAL -> "양방향 (권장)"
                        SyncDirection.DOWNLOAD_ONLY -> "다운로드 전용 (Drive → 로컬)"
                        SyncDirection.UPLOAD_ONLY -> "업로드 전용 (로컬 → Drive)"
                    },
                    onClick = { showSyncDirectionDialog = true }
                )
                
                Text(
                    text = "* 설정된 모드는 모든 폴더에 실시간으로 적용됩니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            
            // App Info Section
            val versionInfo = remember {
                try {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val name = packageInfo.versionName ?: "Unknown"
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toLong()
                    }
                    "$name ($code)"
                } catch (e: Exception) {
                    "Unknown"
                }
            }
            
            SettingsSection(title = "앱 정보") {
                SettingsInfoItem(
                    icon = Icons.Default.Info,
                    title = "버전",
                    subtitle = versionInfo
                )
            }
        }
    }
    
    // Interval Selection Dialog
    if (showIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text("동기화 주기 선택") },
            text = {
                Column {
                    syncPreferences.availableIntervals.forEach { interval ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = interval == syncInterval,
                                onClick = {
                                    syncInterval = interval
                                    syncPreferences.syncIntervalMinutes = interval
                                    showIntervalDialog = false
                                    onScheduleSync()
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(syncPreferences.formatInterval(interval))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntervalDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
    
    // Conflict Resolution Selection Dialog
    if (showConflictResolutionDialog) {
        AlertDialog(
            onDismissRequest = { showConflictResolutionDialog = false },
            title = { Text("기본 충돌 해결 전략 선택") },
            text = {
                Column {
                    val options = listOf(
                        null to "매번 확인",
                        ConflictResolution.USE_LOCAL to "로컬 사용",
                        ConflictResolution.USE_DRIVE to "Drive 사용",
                        ConflictResolution.KEEP_BOTH to "둘 다 유지"
                    )
                    
                    options.forEach { (option, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultConflictResolution == option,
                                onClick = {
                                    defaultConflictResolution = option
                                    syncPreferences.defaultConflictResolution = option
                                    showConflictResolutionDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConflictResolutionDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    // Sync Direction Selection Dialog
    if (showSyncDirectionDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDirectionDialog = false },
            title = { Text("기본 동기화 모드 선택") },
            text = {
                Column {
                    val options = listOf(
                        SyncDirection.BIDIRECTIONAL to "양방향 (권장)",
                        SyncDirection.DOWNLOAD_ONLY to "다운로드 전용 (Drive → 로컬)",
                        SyncDirection.UPLOAD_ONLY to "업로드 전용 (로컬 → Drive)"
                    )
                    
                    options.forEach { (option, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultSyncDirection == option,
                                onClick = {
                                    defaultSyncDirection = option
                                    syncPreferences.defaultSyncDirection = option
                                    showSyncDirectionDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSyncDirectionDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingsClickItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsInfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
