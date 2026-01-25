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
    onNavigateToFolders: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val context = LocalContext.current
    val accounts by accountRepository.accounts.collectAsState()
    val activeAccount by accountRepository.activeAccount.collectAsState()
    
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var accountToDelete by remember { mutableStateOf<GoogleAccount?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    
    val logger = remember { uk.xmlangel.googledrivesync.util.SyncLogger(context) }
    
    val scope = rememberCoroutineScope()
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isLoading = false
        logger.log("signInLauncher result: resultCode=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                logger.log("signInLauncher intent data received")
                val googleAccount = task.getResult(ApiException::class.java)
                if (googleAccount != null) {
                    logger.log("구글 로그인 성공: ${googleAccount.email}, id=${googleAccount.id}")
                    accountRepository.handleSignInResult(googleAccount)
                    // We don't navigate immediately here. 
                    // Let the LaunchedEffect below handle it when activeAccount state updates.
                } else {
                    errorMessage = "로그인 정보를 가져올 수 없습니다."
                    logger.log("구글 로그인 실패: account is null")
                }
            } catch (e: ApiException) {
                errorMessage = "로그인 실패: ${e.message} (Status Code: ${e.statusCode})"
                logger.log("구글 로그인 API 오류: ${e.message}, code=${e.statusCode}")
                if (e.statusCode == 10) {
                    errorMessage += "\n(개발자 매개변수 오류: SHA-1이 Google Cloud Console에 등록되었는지 확인하세요)"
                }
            } catch (e: Exception) {
                errorMessage = "예기치 못한 오류가 발생했습니다: ${e.message}"
                logger.log("구글 로그인 처리 중 오류: ${e.message}")
                e.printStackTrace()
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            logger.log("구글 로그인 취소됨")
        } else {
            errorMessage = "로그인 과정에서 오류가 발생했습니다. (Result Code: ${result.resultCode})"
            logger.log("구글 로그인 실패: resultCode=${result.resultCode}")
        }
    }

    // Auto-navigate to folders only on first load or when a new account is signed in
    var navigationHandled by remember { mutableStateOf(false) }
    
    LaunchedEffect(activeAccount) {
        if (activeAccount != null && isLoading == false && !navigationHandled) {
            logger.log("활성 계정 감지됨: ${activeAccount?.email}. 폴더 화면으로 이동합니다.")
            navigationHandled = true
            onNavigateToFolders()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Drive Sync") },
                actions = {
                    IconButton(onClick = { /* Already on Account screen */ }) {
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
            ExtendedFloatingActionButton(
                onClick = { 
                    if (!isLoading) {
                        isLoading = true
                        logger.log("계정 추가 버튼 클릭")
                        // Force sign out from Google Play Services to show account picker
                        accountRepository.getGoogleSignInClient().signOut().addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                logger.log("기존 세션 로그아웃 성공, 계정 선택창 실행")
                                signInLauncher.launch(accountRepository.getSignInIntent())
                            } else {
                                isLoading = false
                                errorMessage = "로그아웃 실패: ${task.exception?.message}"
                                logger.log("로그아웃 실패: ${task.exception?.message}")
                            }
                        }
                    }
                },
                icon = { 
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(Icons.Default.Add, "계정 추가")
                    }
                },
                text = { Text(if (isLoading) "처리 중..." else "계정 추가") }
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
