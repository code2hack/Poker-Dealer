package com.code2hack.poker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import com.code2hack.pokerdealer.protocol.PokerReconnectController
import com.code2hack.pokerdealer.protocol.PokerReconnectTrigger

/** Owns only listener lifecycle state; synchronized card content is never stored here. */
class PokerListenerService : Service() {
    private val reconnect = PokerReconnectController()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                setEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }

            ACTION_RETRY,
            ACTION_ENABLE,
            null,
            -> if (!isEnabled(this)) stopSelfResult(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }
        networkCallback = null
        reconnect.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reconnect.request(PokerReconnectTrigger.NETWORK_CHANGE)
            }

            override fun onLost(network: Network) {
                reconnect.request(PokerReconnectTrigger.FAILURE, jitterUnit = 0.5)
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun startForegroundCompat() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Poker listener")
            .setContentText("Ready for Dealer")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, PokerActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Poker listener",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val ACTION_ENABLE = "com.code2hack.poker.action.ENABLE_LISTENER"
        const val ACTION_DISABLE = "com.code2hack.poker.action.DISABLE_LISTENER"
        const val ACTION_RETRY = "com.code2hack.poker.action.RETRY_LISTENER"
        private const val PREFS = "poker_listener"
        private const val ENABLED = "enabled"
        private const val CHANNEL_ID = "poker_listener"
        private const val NOTIFICATION_ID = 4_201

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(ENABLED, enabled)
                .apply()
        }

        fun enable(context: Context) {
            setEnabled(context, true)
            start(context, ACTION_ENABLE)
        }

        fun disable(context: Context) {
            setEnabled(context, false)
            context.stopService(Intent(context, PokerListenerService::class.java))
        }

        fun retry(context: Context) = start(context, ACTION_RETRY)

        private fun start(context: Context, action: String) {
            val intent = Intent(context, PokerListenerService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
