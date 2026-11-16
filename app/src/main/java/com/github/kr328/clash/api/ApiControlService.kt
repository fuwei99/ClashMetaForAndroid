package com.github.kr328.clash.api

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.common.log.Log

/**
 * 让 9091 控制面在 App 退到后台后仍然活着。
 * App 一启动 MainApplication 就 startForegroundService(this)，故端口「立即开放」。
 */
class ApiControlService : Service() {

    companion object {
        private const val CHANNEL = "cmfa_api_control"
        private const val NOTIFICATION_ID = 0x5a91

        fun launch(context: Context) {
            runCatching {
                val i = Intent(context, ApiControlService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }.onFailure { Log.w("CMFA API service launch failed: ${it.message}") }
        }
    }

    private var server: ApiControlServer? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            val app = applicationContext as? android.app.Application
                ?: application as? android.app.Application
            if (app == null) {
                Log.w("CMFA API service: no Application context, abort")
                return
            }
            server = ApiControlServer(app).also { it.start() }
            Log.d("CMFA API foreground service created")
        } catch (e: Throwable) {
            Log.w("CMFA API service init failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val c = NotificationChannel(CHANNEL, "API Control", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the local CMFA control API available"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(c)
    }

    private fun buildNotification(): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.github.kr328.clash.service.R.drawable.ic_logo_service)
            .setContentTitle("CMFA API")
            .setContentText("127.0.0.1:9091 控制面已就绪")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
