package uk.xmlangel.googledrivesync.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import androidx.work.*
import kotlinx.coroutines.flow.first
import uk.xmlangel.googledrivesync.R
import uk.xmlangel.googledrivesync.data.local.SyncDatabase
import uk.xmlangel.googledrivesync.data.local.SyncPreferences
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for background sync
 */
class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    private val syncManager = SyncManager(context)
    private val database = SyncDatabase.getInstance(context)
    private val prefs = SyncPreferences(context)
    
    override suspend fun doWork(): Result {
        // Show notification
        setForeground(createForegroundInfo())
        
        // Get all enabled sync folders
        val folders = database.syncFolderDao().getEnabledSyncFolders().first()
        
        var hasErrors = false
        var totalUploaded = 0
        var totalDownloaded = 0
        
        for (folder in folders) {
            when (val result = syncManager.syncFolder(folder.id)) {
                is SyncResult.Success -> {
                    totalUploaded += result.uploaded
                    totalDownloaded += result.downloaded
                }
                is SyncResult.Conflict -> {
                    // Conflicts need user resolution - don't count as error
                    showConflictNotification(result.conflicts.size)
                }
                is SyncResult.Error -> {
                    hasErrors = true
                }
                is SyncResult.Cancelled -> {
                    return Result.failure()
                }
            }
        }
        
        // Show completion notification
        if (prefs.notificationsEnabled && (totalUploaded > 0 || totalDownloaded > 0)) {
            showCompletionNotification(totalUploaded, totalDownloaded)
        }
        
        return if (hasErrors) Result.retry() else Result.success()
    }
    
    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("동기화 중...")
            .setContentText("Google Drive와 동기화하고 있습니다")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ForegroundInfo(
                NOTIFICATION_ID, 
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
        
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
    
    private fun showConflictNotification(count: Int) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) 
            as NotificationManager
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("동기화 충돌")
            .setContentText("${count}개의 파일에 충돌이 있습니다. 앱에서 해결해주세요.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(CONFLICT_NOTIFICATION_ID, notification)
    }
    
    private fun showCompletionNotification(uploaded: Int, downloaded: Int) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) 
            as NotificationManager
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("동기화 완료")
            .setContentText("업로드: ${uploaded}개, 다운로드: ${downloaded}개")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(COMPLETE_NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "동기화",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Google Drive 동기화 알림"
            }
            
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) 
                as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    companion object {
        const val CHANNEL_ID = "sync_channel"
        const val NOTIFICATION_ID = 1001
        const val CONFLICT_NOTIFICATION_ID = 1002
        const val COMPLETE_NOTIFICATION_ID = 1003
        const val WORK_NAME = "periodic_sync"
        
        /**
         * Schedule periodic sync with user-configured interval
         */
        fun schedule(context: Context) {
            val prefs = SyncPreferences(context)
            
            if (!prefs.autoSyncEnabled) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (prefs.syncWifiOnly) NetworkType.UNMETERED 
                    else NetworkType.CONNECTED
                )
                .apply {
                    if (prefs.syncWhileCharging) {
                        setRequiresCharging(true)
                    }
                }
                .build()
            
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                prefs.syncIntervalMinutes.toLong(),
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1,
                    TimeUnit.MINUTES
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
            )
        }
        
        /**
         * Trigger immediate sync
         */
        fun syncNow(context: Context) {
            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(syncRequest)
        }
        
        /**
         * Cancel all sync work
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
