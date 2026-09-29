package io.github.pisces312.droidllm.service

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
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.pisces312.droidllm.MainActivity
import io.github.pisces312.droidllm.R
import io.github.pisces312.droidllm.api.ApiServerPreferences
import io.github.pisces312.droidllm.api.DefaultApiInferenceBridge
import io.github.pisces312.droidllm.apiserver.ApiServer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the local OpenAI-compatible API server.
 * Start via [start]; the server binds to the configured address/port.
 */
@AndroidEntryPoint
class ApiForegroundService : Service() {

    @Inject lateinit var prefs: ApiServerPreferences
    @Inject lateinit var bridge: DefaultApiInferenceBridge

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var apiServer: ApiServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = buildNotification(running = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()

        serviceScope.launch {
            val server = ApiServer(prefs, bridge)
            val ok = server.start()
            apiServer = server
            val config = prefs.current()
            val text = if (ok) {
                getString(R.string.api_notification_running, config.bindAddress, config.port)
            } else {
                getString(R.string.api_notification_failed, server.lastError ?: "unknown")
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(running = ok, content = text))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.launch {
            apiServer?.stop()
            apiServer = null
            bridge.release()
        }
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "droidllm:ApiServer").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60L) // safety cap 10h
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.api_server_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(running: Boolean, content: String? = null): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ApiForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.api_server_notification_title))
            .setContentText(content ?: getString(R.string.api_server_notification_text))
            .setContentIntent(open)
            .setOngoing(running)
            .addAction(0, getString(R.string.api_server_stop), stop)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "api_server"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "io.github.pisces312.droidllm.action.API_STOP"

        fun start(context: Context) {
            val intent = Intent(context, ApiForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ApiForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
