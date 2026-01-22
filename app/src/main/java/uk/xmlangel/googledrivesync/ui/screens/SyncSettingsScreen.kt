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
import uk.xmlangel.googledrivesync.data.local.SyncPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    syncPreferences: SyncPreferences,
    onNavigateBack: () -> Unit,
    onScheduleSync: () -> Unit
) {
    var syncInterval by remember { mutableStateOf(syncPreferences.syncIntervalMinutes) }
    var wifiOnly by remember { mutableStateOf(syncPreferences.syncWifiOnly) }
    var whileCharging by remember { mutableStateOf(syncPreferences.syncWhileCharging) }
    var autoSync by remember { mutableStateOf(syncPreferences.autoSyncEnabled) }
    var notifications by remember { mutableStateOf(syncPreferences.notificationsEnabled) }
    
    var showIntervalDialog by remember { mutableStateOf(false) }
    
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
                }
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
                    subtitle = "동기화 완료 시 알림 표시",
                    checked = notifications,
                    onCheckedChange = {
                        notifications = it
                        syncPreferences.notificationsEnabled = it
                    }
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
