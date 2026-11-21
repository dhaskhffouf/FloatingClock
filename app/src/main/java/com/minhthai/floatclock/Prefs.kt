package com.minhthai.floatclock

import android.content.Context
import android.content.SharedPreferences

/** Toàn bộ cài đặt của app. Service lắng nghe thay đổi nên chỉnh là thấy ngay (live preview). */
class Prefs(context: Context) {

    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    var alarmOn: Boolean
        get() = sp.getBoolean(K_ALARM_ON, false)
        set(v) = sp.edit().putBoolean(K_ALARM_ON, v).apply()

    var alarmH: Int
        get() = sp.getInt(K_ALARM_H, 7)
        set(v) = sp.edit().putInt(K_ALARM_H, v).apply()

    var alarmM: Int
        get() = sp.getInt(K_ALARM_M, 0)
        set(v) = sp.edit().putInt(K_ALARM_M, v).apply()

    var note: String
        get() = sp.getString(K_NOTE, "") ?: ""
        set(v) = sp.edit().putString(K_NOTE, v).apply()

    var color: Int
        get() = sp.getInt(K_COLOR, 0xFFFFFFFF.toInt())
        set(v) = sp.edit().putInt(K_COLOR, v).apply()

    /** Cỡ chữ (sp) 10..60 */
    var size: Int
        get() = sp.getInt(K_SIZE, 22)
        set(v) = sp.edit().putInt(K_SIZE, v).apply()

    /** "Độ đậm" = độ rõ của chữ, 20..100 (%) */
    var opacity: Int
        get() = sp.getInt(K_OPACITY, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v).apply()

    var blur: Boolean
        get() = sp.getBoolean(K_BLUR, false)
        set(v) = sp.edit().putBoolean(K_BLUR, v).apply()

    var seconds: Boolean
        get() = sp.getBoolean(K_SECONDS, false)
        set(v) = sp.edit().putBoolean(K_SECONDS, v).apply()

    /** Vị trí đồng hồ (px). -1 = chưa đặt, dùng mặc định. */
    var x: Int
        get() = sp.getInt(K_X, -1)
        set(v) = sp.edit().putInt(K_X, v).apply()

    var y: Int
        get() = sp.getInt(K_Y, -1)
        set(v) = sp.edit().putInt(K_Y, v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(K_LAST_CHECK, 0L)
        set(v) = sp.edit().putLong(K_LAST_CHECK, v).apply()

    companion object {
        const val FILE = "floatclock_prefs"
        const val K_ENABLED = "enabled"
        const val K_ALARM_ON = "alarm_on"
        const val K_ALARM_H = "alarm_h"
        const val K_ALARM_M = "alarm_m"
        const val K_NOTE = "note"
        const val K_COLOR = "color"
        const val K_SIZE = "size"
        const val K_OPACITY = "opacity"
        const val K_BLUR = "blur"
        const val K_SECONDS = "seconds"
        const val K_X = "x"
        const val K_Y = "y"
        const val K_LAST_CHECK = "last_check"

        val PALETTE: IntArray = intArrayOf(
            0xFFFFFFFF.toInt(), // trắng
            0xFF000000.toInt(), // đen
            0xFFFF5252.toInt(), // đỏ
            0xFFFFC107.toInt(), // vàng
            0xFF4CAF50.toInt(), // xanh lá
            0xFF00BCD4.toInt(), // xanh ngọc
            0xFF448AFF.toInt(), // xanh dương
            0xFFE040FB.toInt(), // tím
            0xFFFF4081.toInt(), // hồng
            0xFFFF9800.toInt()  // cam
        )
    }
}
