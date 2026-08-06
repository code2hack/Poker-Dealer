package com.code2hack.poker

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.code2hack.pokerdealer.protocol.PokerWakeCapability

internal object PokerForegroundWake {
    fun capability(context: Context): PokerWakeCapability {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true && keyguard.isKeyguardSecure) {
            return PokerWakeCapability.KEYGUARD_BLOCKED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return PokerWakeCapability.OVERLAY_PERMISSION_REQUIRED
        }
        return PokerWakeCapability.AVAILABLE
    }

    fun request(context: Context) {
        if (PokerBindingRuntime.isForeground) return
        if (capability(context) != PokerWakeCapability.AVAILABLE) return

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
