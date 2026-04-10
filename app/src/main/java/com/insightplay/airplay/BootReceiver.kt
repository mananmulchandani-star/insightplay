package com.insightplay.airplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED broadcast to auto-start the AirPlay service.
 * Ensures Insight Play is ready to receive AirPlay connections without
 * requiring the user to manually launch the app after a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed — starting AirPlay service")

            val serviceIntent = Intent(context, AirPlayService::class.java).apply {
                action = AirPlayService.ACTION_START
            }

            try {
                context.startForegroundService(serviceIntent)
                Log.i(TAG, "AirPlay service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AirPlay service on boot", e)
            }
        }
    }
}
