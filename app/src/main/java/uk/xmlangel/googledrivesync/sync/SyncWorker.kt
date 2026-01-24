package uk.xmlangel.googledrivesync.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import androidx.work.*
import kotlinx.coroutines.flow.first
import uk.xmlangel.googledrivesync.MainActivity
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
        val logger = uk.xmlangel.googledrivesync.util.SyncLogger(applicationContext)
        logger.log("백그라운드 동기화 작업 시작 (WorkManager)")
        
        setForeground(createForegroundInfo())
        
        // Get all enabled sync folders
        val folders = database.syncFolderDao().getEnabledSyncFolders().first()
        
        var hasErrors = false
        var totalUploaded = 0
        var totalDownloaded = 0
        var totalConflicts = 0
        
        for (folder in folders) {
            when (val result = syncManager.syncFolder(folder.id)) {
                is SyncResult.Success -> {
                    totalUploaded += result.uploaded
                    totalDownloaded += result.downloaded
                }
                is SyncResult.Conflict -> {
                    totalConflicts += result.conflicts.size
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
        if (prefs.notificationsEnabled) {
            showFinalNotification(totalUploaded, totalDownloaded, totalConflicts, hasErrors)
        }
        
        return if (hasErrors) Result.retry() else Result.success()
    }
    
    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("동기화 중...")
            .setContentText("Google Drive와 데이터를 대조하고 있습니다")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Use LOW priority for steady progress
            .setContentIntent(createPendingIntent())
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
    
    private fun showFinalNotification(uploaded: Int, downloaded: Int, conflicts: Int, hasErrors: Boolean) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) 
            as NotificationManager
        
        val title = when {
            hasErrors -> "동기화 오류 발생"
            conflicts > 0 -> "동기화 완료 (충돌 있음)"
            else -> "동기화 완료"
        }
        
        val content = StringBuilder()
        if (uploaded > 0) content.append("업로드: ${uploaded}개 ")
        if (downloaded > 0) content.append("다운로드: ${downloaded}개 ")
        if (conflicts > 0) content.append("충돌: ${conflicts}개 ")
        if (hasErrors) content.append("\n일부 작업 중 오류가 발생했습니다.")
        
        if (content.isEmpty() && !hasErrors) return // Nothing to notify

        val icon = when {
            hasErrors -> android.R.drawable.stat_notify_error
            conflicts > 0 -> android.R.drawable.ic_dialog_alert
            else -> android.R.drawable.stat_sys_download_done
        }
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content.toString().trim())
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setOngoing(false) // Final results should not be ongoing
            .setPriority(if (hasErrors) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(createPendingIntent())
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "동기화",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Google Drive 동기화 상태 및 결과 알림"
                enableLights(true)
                enableVibration(true)
            }
            
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) 
                as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = ACTION_SHOW_LOGS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        return PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    companion object {
        const val ACTION_SHOW_LOGS = "uk.xmlangel.googledrivesync.ACTION_SHOW_LOGS"
        const val CHANNEL_ID = "sync_channel"
        const val NOTIFICATION_ID = 1001
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
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
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
