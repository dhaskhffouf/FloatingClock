package com.minhthai.floatclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Khởi động lại đồng hồ sau khi bật máy / cập nhật app / bị hệ thống dọn, và xử lý báo thức. */
class ClockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val canRun = prefs.enabled && Settings.canDrawOverlays(context)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ClockService.ACTION_RESTART -> {
                if (canRun) ClockService.start(context)
            }

            ClockService.ACTION_ALARM -> {
                ClockService.scheduleAlarm(context) // hẹn luôn ngày mai
                if (canRun) ClockService.start(context, ClockService.ACTION_ALARM)
            }
        }
    }
}
