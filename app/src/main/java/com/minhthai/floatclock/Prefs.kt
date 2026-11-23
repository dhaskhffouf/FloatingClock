package com.minhthai.floatclock

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class AlarmItem(
    val id: String,
    var enabled: Boolean,
    var hour: Int,
    var minute: Int,
    var note: String
)

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("float_clock", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    var size: Int
        get() = sp.getInt(K_SIZE, 28)
        set(v) = sp.edit().putInt(K_SIZE, v).apply()

    var opacity: Int
        get() = sp.getInt(K_OPACITY, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v).apply()

    var glassAlpha: Int
        get() = sp.getInt(K_GLASS_ALPHA, 0)
        set(v) = sp.edit().putInt(K_GLASS_ALPHA, v.coerceIn(0, 100)).apply()

    var color: Int
        get() = sp.getInt(K_COLOR, Color.WHITE)
        set(v) = sp.edit().putInt(K_COLOR, v).apply()

    var seconds: Boolean
        get() = sp.getBoolean(K_SECONDS, false)
        set(v) = sp.edit().putBoolean(K_SECONDS, v).apply()

    var blur: Boolean
        get() = sp.getBoolean(K_BLUR, false)
        set(v) = sp.edit().putBoolean(K_BLUR, v).apply()

    var alarmSound: Boolean
        get() = sp.getBoolean(K_ALARM_SOUND, true)
        set(v) = sp.edit().putBoolean(K_ALARM_SOUND, v).apply()

    var x: Int
        get() = sp.getInt(K_X, -1)
        set(v) = sp.edit().putInt(K_X, v).apply()

    var y: Int
        get() = sp.getInt(K_Y, -1)
        set(v) = sp.edit().putInt(K_Y, v).apply()

    var lastCheck: Long
        get() = sp.getLong(K_LAST_CHECK, 0L)
        set(v) = sp.edit().putLong(K_LAST_CHECK, v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(K_LAST_UPDATE_CHECK, lastCheck)
        set(v) {
            sp.edit()
                .putLong(K_LAST_UPDATE_CHECK, v)
                .putLong(K_LAST_CHECK, v)
                .apply()
        }

    var alarmOn: Boolean
        get() = getAlarms().any { it.enabled }
        set(v) {
            val list = getAlarms()
            if (list.isEmpty()) {
                list.add(AlarmItem("alarm_1", v, 7, 0, ""))
                saveAlarms(list)
            } else {
                list[0].enabled = v
                saveAlarms(list)
            }
        }

    var alarmH: Int
        get() = getAlarms().firstOrNull()?.hour ?: 7
        set(v) {
            val list = getAlarms()
            if (list.isEmpty()) {
                list.add(AlarmItem("alarm_1", true, v.coerceIn(0, 23), 0, ""))
            } else {
                list[0].hour = v.coerceIn(0, 23)
            }
            saveAlarms(list)
        }

    var alarmM: Int
        get() = getAlarms().firstOrNull()?.minute ?: 0
        set(v) {
            val list = getAlarms()
            if (list.isEmpty()) {
                list.add(AlarmItem("alarm_1", true, 7, v.coerceIn(0, 59), ""))
            } else {
                list[0].minute = v.coerceIn(0, 59)
            }
            saveAlarms(list)
        }

    var note: String
        get() = getAlarms().firstOrNull()?.note ?: ""
        set(v) {
            val list = getAlarms()
            if (list.isEmpty()) {
                list.add(AlarmItem("alarm_1", false, 7, 0, v))
            } else {
                list[0].note = v
            }
            saveAlarms(list)
        }

    fun getAlarms(): MutableList<AlarmItem> {
        val raw = sp.getString(K_ALARMS_JSON, null)
        if (raw == null) {
            val oldOn = sp.getBoolean(K_ALARM_ON, false)
            val oldH = sp.getInt(K_ALARM_H, 7)
            val oldM = sp.getInt(K_ALARM_M, 0)
            val oldNote = sp.getString(K_NOTE, "") ?: ""
            val initial = if (oldOn || oldNote.isNotBlank() || sp.contains(K_ALARM_H)) {
                mutableListOf(AlarmItem("alarm_1", oldOn, oldH, oldM, oldNote))
            } else {
                mutableListOf()
            }
            saveAlarms(initial)
            return initial
        }

        val list = mutableListOf<AlarmItem>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                if (list.size >= MAX_ALARMS) break
                val o = arr.getJSONObject(i)
                list.add(
                    AlarmItem(
                        id = o.optString("id", "alarm_${i + 1}"),
                        enabled = o.optBoolean("enabled", true),
                        hour = o.optInt("hour", 7).coerceIn(0, 23),
                        minute = o.optInt("minute", 0).coerceIn(0, 59),
                        note = o.optString("note", "")
                    )
                )
            }
        }
        return list
    }

    fun saveAlarms(alarms: List<AlarmItem>) {
        val arr = JSONArray()
        alarms.take(MAX_ALARMS).forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("enabled", item.enabled)
                    put("hour", item.hour.coerceIn(0, 23))
                    put("minute", item.minute.coerceIn(0, 59))
                    put("note", item.note)
                }
            )
        }
        sp.edit().putString(K_ALARMS_JSON, arr.toString()).apply()
    }

    companion object {
        const val MAX_ALARMS = 5

        const val K_ENABLED = "enabled"
        const val K_SIZE = "size"
        const val K_OPACITY = "opacity"
        const val K_GLASS_ALPHA = "glass_alpha"
        const val K_COLOR = "color"
        const val K_SECONDS = "seconds"
        const val K_BLUR = "blur"
        const val K_ALARM_SOUND = "alarm_sound"
        const val K_X = "x"
        const val K_Y = "y"
        const val K_LAST_CHECK = "last_check"
        const val K_LAST_UPDATE_CHECK = "last_update_check"

        const val K_ALARMS_JSON = "alarms_json"
        const val K_ALARM_ON = "alarm_on"
        const val K_ALARM_H = "alarm_h"
        const val K_ALARM_M = "alarm_m"
        const val K_NOTE = "note"
    }
}