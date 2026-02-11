@file:Suppress("DEPRECATION")

package uk.xmlangel.googledrivesync.data.repository

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.xmlangel.googledrivesync.data.local.AccountPreferences
import uk.xmlangel.googledrivesync.data.local.SyncFolderDao
import uk.xmlangel.googledrivesync.data.local.SyncItemDao
import uk.xmlangel.googledrivesync.data.local.SyncHistoryDao
import uk.xmlangel.googledrivesync.data.model.GoogleAccount

/**
 * Repository for managing Google account authentication and multi-account support
 */
class AccountRepository(
    private val context: Context,
    private val syncFolderDao: SyncFolderDao,
    private val syncItemDao: SyncItemDao,
    private val syncHistoryDao: SyncHistoryDao
) {
    
    private val accountPrefs = AccountPreferences(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private val _accounts = MutableStateFlow<List<GoogleAccount>>(emptyList())
    val accounts: StateFlow<List<GoogleAccount>> = _accounts.asStateFlow()
    
    private val _activeAccount = MutableStateFlow<GoogleAccount?>(null)
    val activeAccount: StateFlow<GoogleAccount?> = _activeAccount.asStateFlow()
    
    init {
        loadAccounts()
    }
    
    private fun loadAccounts() {
        val savedAccounts = accountPrefs.getAccounts()
        val activeId = accountPrefs.getActiveAccountId()
        
        scope.launch {
            val accountsWithSyncTime = savedAccounts.map { account ->
                val lastSyncedAt = syncFolderDao.getMaxLastSyncTimeByAccount(account.id)
                account.copy(
                    isActive = account.id == activeId,
                    lastSyncedAt = lastSyncedAt
                )
            }
            _accounts.value = accountsWithSyncTime
            _activeAccount.value = accountsWithSyncTime.find { it.id == activeId }
        }
    }
    
    /**
     * Create GoogleSignInClient for authentication
     */
    fun getGoogleSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()
        
        return GoogleSignIn.getClient(context, gso)
    }
    
    /**
     * Get sign-in intent for launching sign-in flow
     */
    fun getSignInIntent(): Intent {
        return getGoogleSignInClient().signInIntent
    }
    
    /**
     * Handle successful sign-in result
     */
    fun handleSignInResult(googleAccount: GoogleSignInAccount): GoogleAccount? {
        return try {
            val account = GoogleAccount(
                id = googleAccount.id ?: googleAccount.email ?: "",
                email = googleAccount.email ?: "",
                displayName = googleAccount.displayName,
                photoUrl = googleAccount.photoUrl?.toString(),
                isActive = true
            )
            
            // Add to stored accounts
            accountPrefs.addAccount(account)
            
            // Set as active
            accountPrefs.setActiveAccountId(account.id)
            
            // Reload accounts
            loadAccounts()
            
            account
        } catch (e: Exception) {
            uk.xmlangel.googledrivesync.util.SyncLogger(context).log("계정 저장 중 오류: ${e.message}")
            null
        }
    }
    
    /**
     * Switch to a different account
     */
    fun switchAccount(accountId: String) {
        accountPrefs.setActiveAccountId(accountId)
        loadAccounts()
    }
    
    /**
     * Sign out from specific account and cleanup its data
     */
    suspend fun signOut(accountId: String) {
        // Cleanup database records for this account
        syncFolderDao.deleteFoldersByAccount(accountId)
        syncItemDao.deleteItemsByAccount(accountId)
        syncHistoryDao.deleteHistoryByAccount(accountId)
        
        accountPrefs.removeAccount(accountId)
        loadAccounts()
    }
    
    /**
     * Sign out from all accounts
     */
    suspend fun signOutAll() {
        getGoogleSignInClient().signOut()
        accountPrefs.clear()
        loadAccounts()
    }
    
    /**
     * Get GoogleSignInAccount for API access
     */
    fun getGoogleSignInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }
    
    /**
     * Check if user has required scopes
     */
    fun hasRequiredScopes(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(
            account,
            Scope(DriveScopes.DRIVE_FILE),
            Scope(DriveScopes.DRIVE)
        )
    }
}
