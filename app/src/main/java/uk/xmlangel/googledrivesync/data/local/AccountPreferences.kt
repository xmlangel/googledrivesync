package uk.xmlangel.googledrivesync.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import uk.xmlangel.googledrivesync.data.model.GoogleAccount

/**
 * Secure storage for account data using EncryptedSharedPreferences
 */
class AccountPreferences(context: Context) {
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val prefs: SharedPreferences = try {
        createEncryptedPrefs(context)
    } catch (e: Exception) {
        uk.xmlangel.googledrivesync.util.SyncLogger(context).log("EncryptedSharedPreferences 생성 실패: ${e.message}. 초기화 시도 중...")
        e.printStackTrace()
        // Delete the corrupted preferences file
        context.deleteSharedPreferences("account_prefs")
        try {
            // Retry creation
            createEncryptedPrefs(context)
        } catch (e2: Exception) {
            uk.xmlangel.googledrivesync.util.SyncLogger(context).log("EncryptedSharedPreferences 재시도 실패: ${e2.message}. 일반 SharedPreferences 사용.")
            e2.printStackTrace()
            // Fallback to regular SharedPreferences if encryption is completely broken on this device
            context.getSharedPreferences("account_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            "account_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    private val gson = Gson()
    
    companion object {
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE_ACCOUNT_ID = "active_account_id"
    }
    
    /**
     * Save all accounts
     */
    fun saveAccounts(accounts: List<GoogleAccount>) {
        val json = gson.toJson(accounts)
        prefs.edit().putString(KEY_ACCOUNTS, json).apply()
    }
    
    /**
     * Get all saved accounts
     */
    fun getAccounts(): List<GoogleAccount> {
        val json = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val type = object : TypeToken<List<GoogleAccount>>() {}.type
        return gson.fromJson(json, type)
    }
    
    /**
     * Add a new account
     */
    fun addAccount(account: GoogleAccount) {
        val accounts = getAccounts().toMutableList()
        // Remove if exists and re-add
        accounts.removeAll { it.id == account.id }
        accounts.add(account)
        saveAccounts(accounts)
    }
    
    /**
     * Remove an account
     */
    fun removeAccount(accountId: String) {
        val accounts = getAccounts().filter { it.id != accountId }
        saveAccounts(accounts)
        
        // If removed account was active, clear active
        if (getActiveAccountId() == accountId) {
            setActiveAccountId(accounts.firstOrNull()?.id)
        }
    }
    
    /**
     * Get active account ID
     */
    fun getActiveAccountId(): String? {
        return prefs.getString(KEY_ACTIVE_ACCOUNT_ID, null)
    }
    
    /**
     * Set active account ID
     */
    fun setActiveAccountId(accountId: String?) {
        prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, accountId).apply()
    }
    
    /**
     * Get the currently active account
     */
    fun getActiveAccount(): GoogleAccount? {
        val activeId = getActiveAccountId() ?: return null
        return getAccounts().find { it.id == activeId }
    }
    
    /**
     * Clear all data
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
