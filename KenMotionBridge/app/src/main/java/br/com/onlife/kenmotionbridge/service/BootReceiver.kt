package br.com.onlife.kenmotionbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Reinicia a ponte automaticamente após o boot do tablet. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ContextCompat.startForegroundService(
                context, Intent(context, BridgeService::class.java)
            )
        }
    }
}
