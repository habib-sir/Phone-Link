package com.example.service

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
import com.example.MainActivity
import com.example.R
import com.example.network.ConnectionStatus
import com.example.network.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SignalingForegroundService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SignalingForegroundService created")
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PhoneLink::SignalingWakeLock"
        )
        wakeLock?.acquire(60 * 60 * 1000L) // Safe 1h timeout

        observeConnectionStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: "ws://10.0.2.2:8080"
        val pairCode = intent?.getStringExtra(EXTRA_PAIR_CODE) ?: ""

        val notification = buildNotification("Connecting to signaling server...", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (pairCode.isNotEmpty()) {
            SignalingClient.getInstance().connect(serverUrl, pairCode)
        }

        return START_STICKY
    }

    private fun observeConnectionStatus() {
        val signaling = SignalingClient.getInstance()
        serviceScope.launch {
            signaling.connectionStatus.collectLatest { status ->
                val isLaptopOnline = signaling.isLaptopOnline.value
                val text = when (status) {
                    ConnectionStatus.CONNECTED -> if (isLaptopOnline) "Connected • Laptop is Online" else "Connected • Waiting for Laptop"
                    ConnectionStatus.CONNECTING -> "Connecting to Signaling Server..."
                    ConnectionStatus.DISCONNECTED -> "Disconnected from Signaling Server"
                }
                updateNotification(text, isLaptopOnline)
            }
        }

        serviceScope.launch {
            signaling.isLaptopOnline.collectLatest { online ->
                val isConnected = signaling.connectionStatus.value == ConnectionStatus.CONNECTED
                val text = if (isConnected) {
                    if (online) "Connected • Laptop is Online" else "Connected • Waiting for Laptop"
                } else {
                    "Disconnected from Signaling Server"
                }
                updateNotification(text, online)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phone Link Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps connection open for calls and SMS commands from Laptop"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String, isOnline: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = if (isOnline) android.R.drawable.presence_online else android.R.drawable.stat_notify_sync

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Link Active")
            .setContentText(statusText)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String, isOnline: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText, isOnline))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SignalingForegroundService destroyed")
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakelock: ${e.message}")
        }
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SignalingService"
        const val CHANNEL_ID = "phone_link_service_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_PAIR_CODE = "extra_pair_code"

        fun start(context: Context, serverUrl: String, pairCode: String) {
            val intent = Intent(context, SignalingForegroundService::class.java).apply {
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_PAIR_CODE, pairCode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SignalingForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
