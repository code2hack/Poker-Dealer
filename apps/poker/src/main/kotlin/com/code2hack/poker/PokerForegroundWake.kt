package com.code2hack.poker

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

internal object PokerForegroundWake {
    fun request(context: Context) {
        if (PokerBindingRuntime.isForeground) return
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true && keyguard.isKeyguardSecure) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return
        }

        val wakeLock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Poker:foreground",
            )
        runCatching {
            wakeLock?.acquire(1_500L)
            context.startActivity(
                Intent(context, PokerActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
        }.also {
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
    }
}
