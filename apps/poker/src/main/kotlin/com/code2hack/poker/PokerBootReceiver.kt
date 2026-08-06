package com.code2hack.poker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PokerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && PokerListenerService.isEnabled(context)) {
            PokerListenerService.retry(context)
        }
    }
}
