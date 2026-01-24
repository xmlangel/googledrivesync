package uk.xmlangel.googledrivesync.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*
import coil.compose.AsyncImage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import uk.xmlangel.googledrivesync.data.model.GoogleAccount
import uk.xmlangel.googledrivesync.data.repository.AccountRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    accountRepository: AccountRepository,
    onNavigateToFolders: () -> Unit
) {
    val context = LocalContext.current
    val accounts by accountRepository.accounts.collectAsState()
    val activeAccount by accountRepository.activeAccount.collectAsState()
    
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var accountToDelete by remember { mutableStateOf<GoogleAccount?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val scope = rememberCoroutineScope()
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val googleAccount = task.getResult(ApiException::class.java)
                accountRepository.handleSignInResult(googleAccount)
            } catch (e: ApiException) {
                errorMessage = "로그인 실패: ${e.message}"
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("계정 관리") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { 
                    // Force sign out from Google Play Services to show account picker
                    accountRepository.getGoogleSignInClient().signOut().addOnCompleteListener {
                        signInLauncher.launch(accountRepository.getSignInIntent())
                    }
                },
                icon = { Icon(Icons.Default.Add, "계정 추가") },
                text = { Text("계정 추가") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Error message
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { errorMessage = null }) {
                            Icon(Icons.Default.Close, "닫기")
                        }
                    }
                }
            }
            
            // Delete confirmation dialog
            accountToDelete?.let { account ->
                AlertDialog(
                    onDismissRequest = { accountToDelete = null },
                    title = { Text("계정 삭제") },
                    text = { 
                        Text("'${account.displayName}' 계정을 삭제하시겠습니까?\n\n" +
                             "동기화 관련 설정 및 이력만 삭제되며, 기기나 Google Drive의 실제 파일은 삭제되지 않습니다.") 
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    accountRepository.signOut(account.id)
                                    accountToDelete = null
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("삭제")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { accountToDelete = null }) {
                            Text("취소")
                        }
                    }
                )
            }
            
            if (accounts.isEmpty()) {
                // Empty state
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
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(120.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "계정을 추가하세요",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Google 계정을 추가하여\nDrive와 동기화를 시작하세요",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Account list
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(accounts) { account ->
                        AccountCard(
                            account = account,
                            isActive = account.id == activeAccount?.id,
                            onSelect = { accountRepository.switchAccount(account.id) },
                            onDelete = { accountToDelete = account },
                            onNavigateToFolders = onNavigateToFolders
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AccountCard(
    account: GoogleAccount,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onNavigateToFolders: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Profile photo
            if (account.photoUrl != null) {
                AsyncImage(
                    model = account.photoUrl,
                    contentDescription = "프로필 사진",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = account.displayName?.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.displayName ?: "Unknown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = account.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "활성 계정",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (account.lastSyncedAt != null && account.lastSyncedAt > 0) {
                            "최근 동기화: ${formatTimestamp(account.lastSyncedAt)}"
                        } else {
                            "동기화 내역 없음"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            
            if (isActive) {
                IconButton(onClick = onNavigateToFolders) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = "폴더 관리",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "계정 삭제",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val now = Calendar.getInstance()
    val syncDate = Calendar.getInstance().apply { time = date }
    
    return when {
        // Today: HH:mm
        now.get(Calendar.YEAR) == syncDate.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == syncDate.get(Calendar.DAY_OF_YEAR) -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        }
        // This year: MM월 dd일 HH:mm
        now.get(Calendar.YEAR) == syncDate.get(Calendar.YEAR) -> {
            SimpleDateFormat("MM월 dd일 HH:mm", Locale.getDefault()).format(date)
        }
        // Longer ago: yyyy-MM-dd
        else -> {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        }
    }
}
