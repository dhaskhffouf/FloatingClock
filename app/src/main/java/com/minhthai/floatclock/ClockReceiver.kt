package com.minhthai.floatclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = Prefs(context)
        if (!prefs.enabled || !Settings.canDrawOverlays(context)) return

        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ClockService.ACTION_RESTART -> {
                ClockService.start(context)
                ClockService.scheduleAlarm(context)
            }
            ClockService.ACTION_ALARM -> {
                ClockService.start(context, ClockService.ACTION_ALARM)
                ClockService.scheduleAlarm(context)
            }
        }
    }
}