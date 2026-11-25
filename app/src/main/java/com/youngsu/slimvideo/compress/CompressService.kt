package com.youngsu.slimvideo.compress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.youngsu.slimvideo.R
import com.youngsu.slimvideo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 압축 중에만 떠 있는 포그라운드 서비스.
 *
 * - 앱을 내려도 압축이 계속되도록 하고, 진행률을 알림으로 보여 준다.
 * - 실제 인코딩은 [CompressManager] 가 하므로, 이 서비스는 그 상태를 알림으로 옮기기만 한다.
 */
class CompressService : Service() {

    companion object {
        private const val TAG = "CompressService"
        private const val CHANNEL_ID = "slim_video_compress"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, CompressService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CompressService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 서비스 시작 직후 5초 안에 startForeground 를 불러야 한다
        val notification = buildNotification(getString(R.string.compressing), 0, indeterminate = true)
        val foregroundStarted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                } else {
                    0
                }
            )
        }.isSuccess

        if (!foregroundStarted) {
            // 포그라운드로 못 뜨면 서비스를 바로 내린다 (압축 자체는 앱 안에서 계속된다)
            Log.w(TAG, "포그라운드 서비스를 시작하지 못했습니다")
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        if (observer == null) {
            observer = scope.launch {
                var sawRunning = false
                CompressManager.state.collectLatest { state ->
                    if (state is CompressManager.State.Running) sawRunning = true
                    when (state) {
                        is CompressManager.State.Running -> {
                            postNotification(
                                buildNotification(
                                    getString(
                                        R.string.notif_text,
                                        state.info.displayName,
                                        state.progress
                                    ),
                                    state.progress,
                                    indeterminate = state.progress <= 0
                                )
                            )
                        }
                        is CompressManager.State.Success -> {
                            postNotification(
                                buildNotification(
                                    getString(R.string.notif_done_text, state.result.displayName),
                                    100,
                                    indeterminate = false,
                                    title = getString(R.string.notif_done_title),
                                    ongoing = false
                                )
                            )
                            // 완료 알림은 남겨 두고 포그라운드만 내린다
                            shutdown(keepNotification = true)
                        }
                        is CompressManager.State.Failure -> shutdown()
                        CompressManager.State.Cancelled -> shutdown()
                        CompressManager.State.Idle -> {
                            // 시작 직후 아직 Running 이 되기 전에는 기다린다
                            if (sawRunning) shutdown()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun postNotification(notification: Notification) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun shutdown(keepNotification: Boolean = false) {
        releaseWakeLock()
        runCatching {
            ServiceCompat.stopForeground(
                this,
                if (keepNotification) ServiceCompat.STOP_FOREGROUND_DETACH
                else ServiceCompat.STOP_FOREGROUND_REMOVE
            )
        }
        stopSelf()
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15 부터는 mediaProcessing 포그라운드 서비스에 시간 제한(6시간)이 있다.
     * 이 콜백을 구현하지 않으면 시스템이 ANR 로 처리하므로, 작업을 정리하고 서비스를 내린다.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "포그라운드 서비스 시간 초과 — 작업을 정리합니다")
        CompressManager.cancel()
        shutdown()
    }

    // -------------------------------------------------------------- helpers

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = getString(R.string.compressing)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        text: String,
        progress: Int,
        indeterminate: Boolean,
        title: String = getString(R.string.notif_title),
        ongoing: Boolean = true
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_compress)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setSilent(true)
            .setProgress(if (ongoing) 100 else 0, progress, indeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SlimVideo:compress").apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }
}
