package com.minhthai.floatclock

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Calendar
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max

/**
 * Dịch vụ nền hiển thị đồng hồ nổi (overlay) kiểu soft glass trong nhẹ.
 *  - Kéo để di chuyển (khung tự co dãn theo nội dung khi bật/tắt giây), chạm 1 lần để tắt nhấp nháy.
 *  - Nhấn giữ để mở bảng chỉnh nhanh; bấm "Cài đặt" để mở bảng cài đặt nổi mà vẫn thấy app bên dưới.
 */
class ClockService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val themed by lazy { ContextThemeWrapper(this, R.style.Theme_FloatClock) }
    private val dm get() = resources.displayMetrics

    // ---- Đồng hồ ----
    private var chip: LinearLayout? = null
    private var timeView: TextClock? = null
    private var noteView: TextView? = null
    private var chipLp: WindowManager.LayoutParams? = null
    private var blinkAnim: ObjectAnimator? = null
    private var activeAlarmNote: String = ""
    private val firedKeys = mutableSetOf<String>()

    // ---- Bảng chỉnh nhanh & bảng cài đặt nổi ----
    private var panel: View? = null
    private var overlaySettings: View? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.K_X, Prefs.K_Y, Prefs.K_ENABLED, Prefs.K_LAST_CHECK, Prefs.K_LAST_UPDATE_CHECK -> Unit
            Prefs.K_ALARMS_JSON, Prefs.K_ALARM_ON, Prefs.K_ALARM_H, Prefs.K_ALARM_M -> {
                scheduleAlarm(this)
                applyStyle()
            }
            else -> applyStyle()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            checkAlarm()
            handler.postDelayed(this, 5_000)
        }
    }
    private val autoStopBlink = Runnable { stopBlink() }
    private val autoHidePanel = Runnable { hidePanel() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs.enabled || !Settings.canDrawOverlays(this)) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChip()
        scheduleAlarm(this)
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (intent?.action == ACTION_ALARM) checkAlarm()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.enabled) scheduleRestart(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        blinkAnim?.cancel()
        hidePanel()
        hideOverlaySettings()
        chip?.let { runCatching { wm.removeView(it) } }
        chip = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Foreground

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Đồng hồ nổi", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ClockService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Đồng hồ nổi đang chạy")
            .setContentText("Nhấn giữ đồng hồ để chỉnh nhanh · by Minh Thái")
            .setContentIntent(open)
            .addAction(0, "Tắt", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    // ------------------------------------------------------------------ Đồng hồ (co dãn theo nội dung / giây)

    private fun dp(v: Float) = (v * dm.density).toInt()

    private fun ensureChip() {
        if (chip != null) {
            applyStyle()
            return
        }
        val root = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(0x1AFFFFFF)
                setStroke(dp(1f), 0x38FFFFFF)
            }
        }
        val time = TextClock(themed).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        val note = TextView(themed).apply {
            gravity = Gravity.CENTER
            maxWidth = dp(240f)
            visibility = View.GONE
            setPadding(0, dp(2f), 0, 0)
        }
        root.addView(time)
        root.addView(note)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.x >= 0) prefs.x else dp(16f)
            y = if (prefs.y >= 0) prefs.y else dp(40f)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        chip = root
        timeView = time
        noteView = note
        chipLp = lp
        attachTouch(root)
        applyStyle()
        runCatching { wm.addView(root, lp) }.onFailure { Log.e(TAG, "addView lỗi", it) }
    }

    private fun applyStyle() {
        val tv = timeView ?: return
        val nv = noteView ?: return
        val c = chip ?: return

        val alpha = (prefs.opacity * 255 / 100).coerceIn(51, 255)
        val color = ColorUtils.setAlphaComponent(prefs.color, alpha)
        val size = prefs.size.toFloat()
        val shadow = if (ColorUtils.calculateLuminance(prefs.color) > 0.5) 0x99000000.toInt() else 0x99FFFFFF.toInt()

        val fmt = if (prefs.seconds) "HH:mm:ss" else "HH:mm"
        tv.format24Hour = fmt
        tv.format12Hour = fmt
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        tv.setTextColor(color)
        tv.setShadowLayer(6f, 0f, 1f, shadow)

        nv.setTextSize(TypedValue.COMPLEX_UNIT_SP, max(11f, size * 0.55f))
        nv.setTextColor(color)
        nv.setShadowLayer(6f, 0f, 1f, shadow)
        nv.text = activeAlarmNote
        nv.visibility = if (blinkAnim != null && activeAlarmNote.isNotBlank()) View.VISIBLE else View.GONE

        c.requestLayout()
        c.post {
            val lp = chipLp ?: return@post
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.x = lp.x.coerceIn(0, max(0, dm.widthPixels - c.width))
            lp.y = lp.y.coerceIn(0, max(0, dm.heightPixels - c.height))
            applyBlur(lp, prefs.blur)
            runCatching { wm.updateViewLayout(c, lp) }
        }
    }

    private fun applyBlur(lp: WindowManager.LayoutParams, on: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        if (on && wm.isCrossWindowBlurEnabled) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = 24
        } else {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            lp.blurBehindRadius = 0
        }
    }

    /** Kéo = di chuyển · chạm = tắt nhấp nháy · giữ = mở bảng chỉnh nhanh. */
    private fun attachTouch(v: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        val longPress = Runnable {
            longFired = true
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            togglePanel()
        }

        v.setOnTouchListener { _, e ->
            val lp = chipLp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longFired = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = (startX + dx).toInt().coerceIn(0, max(0, dm.widthPixels - v.width))
                        lp.y = (startY + dy).toInt().coerceIn(0, max(0, dm.heightPixels - v.height))
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (moved) {
                        prefs.x = lp.x
                        prefs.y = lp.y
                    } else if (!longFired) {
                        stopBlink()
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }
    }

    // ------------------------------------------------------------------ 5 Báo thức nhấp nháy

    private fun checkAlarm() {
        val alarms = prefs.getAlarms().filter { it.enabled }
        if (alarms.isEmpty()) return

        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val dayStamp = "${now.get(Calendar.YEAR)}-${now.get(Calendar.DAY_OF_YEAR)}"

        if (firedKeys.size > 40) {
            firedKeys.removeAll { !it.startsWith(dayStamp) }
        }

        for (alarm in alarms) {
            val target = alarm.hour * 60 + alarm.minute
            val key = "$dayStamp-${alarm.id}-$target"
            if (nowMin - target in 0..2 && key !in firedKeys) {
                firedKeys.add(key)
                activeAlarmNote = alarm.note
                startBlink()
                scheduleAlarm(this)
                break
            }
        }
    }

    private fun startBlink() {
        ensureChip()
        val c = chip ?: return
        blinkAnim?.cancel()
        blinkAnim = ObjectAnimator.ofFloat(c, View.ALPHA, 1f, 0.1f).apply {
            duration = 450
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        applyStyle()
        handler.removeCallbacks(autoStopBlink)
        handler.postDelayed(autoStopBlink, 10 * 60 * 1000L)
    }

    private fun stopBlink() {
        handler.removeCallbacks(autoStopBlink)
        if (blinkAnim == null) return
        blinkAnim?.cancel()
        blinkAnim = null
        activeAlarmNote = ""
        chip?.alpha = 1f
        applyStyle()
    }

    // ------------------------------------------------------------------ Bảng chỉnh nhanh (nhấn giữ)

    private fun togglePanel() {
        if (panel != null) hidePanel() else showPanel()
    }

    private fun bumpPanelTimer() {
        handler.removeCallbacks(autoHidePanel)
        handler.postDelayed(autoHidePanel, 15_000)
    }

    private fun showPanel() {
        hideOverlaySettings()
        val v = LayoutInflater.from(themed).inflate(R.layout.panel_clock, null)

        v.findViewById<TextView>(R.id.tvPanelCredit).text = "by Minh Thái · v${BuildConfig.VERSION_NAME}"

        val tvSize = v.findViewById<TextView>(R.id.tvPanelSize)
        val sSize = v.findViewById<Slider>(R.id.sliderPanelSize)
        sSize.value = prefs.size.toFloat().coerceIn(10f, 60f)
        tvSize.text = "Cỡ chữ: ${prefs.size} sp"
        sSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                prefs.size = value.toInt()
                tvSize.text = "Cỡ chữ: ${value.toInt()} sp"
                bumpPanelTimer()
            }
        }

        val tvOp = v.findViewById<TextView>(R.id.tvPanelOpacity)
        val sOp = v.findViewById<Slider>(R.id.sliderPanelOpacity)
        sOp.value = prefs.opacity.toFloat().coerceIn(20f, 100f)
        tvOp.text = "Độ đậm: ${prefs.opacity}%"
        sOp.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                prefs.opacity = value.toInt()
                tvOp.text = "Độ đậm: ${value.toInt()}%"
                bumpPanelTimer()
            }
        }

        val colors = v.findViewById<LinearLayout>(R.id.panelColors)
        Swatches.render(colors, prefs.color, 38, onPick = {
            prefs.color = it
            bumpPanelTimer()
        })

        v.findViewById<View>(R.id.btnPanelHide).setOnClickListener {
            prefs.enabled = false
            stopSelf()
        }
        v.findViewById<View>(R.id.btnPanelSettings).setOnClickListener {
            hidePanel()
            showOverlaySettings()
        }
        v.findViewById<View>(R.id.btnPanelClose).setOnClickListener { hidePanel() }

        val lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.9f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        val clockOnTop = (chipLp?.y ?: 0) < dm.heightPixels / 2
        lp.gravity = (if (clockOnTop) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        lp.y = dp(56f)
        if (Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = 24
        }

        runCatching { wm.addView(v, lp) }
            .onSuccess {
                panel = v
                bumpPanelTimer()
            }
            .onFailure { Log.e(TAG, "show panel lỗi", it) }
    }

    private fun hidePanel() {
        handler.removeCallbacks(autoHidePanel)
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    // ------------------------------------------------------------------ Bảng cài đặt nổi (5 báo thức + thấy app bên dưới)

    private fun showOverlaySettings() {
        if (overlaySettings != null) return
        handler.removeCallbacks(autoHidePanel)

        val container = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(0x88161926.toInt())
                setStroke(dp(1f), 0x38FFFFFF)
            }
        }

        val header = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(themed).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "Cài đặt đồng hồ nổi"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val btnClose = TextView(themed).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10f), dp(4f), dp(6f), dp(4f))
            setOnClickListener { hideOverlaySettings() }
        }
        header.addView(title)
        header.addView(btnClose)
        container.addView(header)

        val scroll = ScrollView(themed).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.52f).toInt()
            ).apply { topMargin = dp(10f) }
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
        }

        val swSec = MaterialSwitch(themed).apply {
            isChecked = prefs.seconds
            setOnCheckedChangeListener { _, on -> prefs.seconds = on }
        }
        body.addView(makeSwitchRow("Hiện giây (khung tự co dãn)", swSec))

        val swBlur = MaterialSwitch(themed).apply {
            val supported = Build.VERSION.SDK_INT >= 31
            isEnabled = supported
            alpha = if (supported) 1f else 0.5f
            isChecked = prefs.blur && supported
            setOnCheckedChangeListener { _, on -> if (supported) prefs.blur = on }
        }
        body.addView(makeSwitchRow("Làm mờ nền phía sau (Android 12+)", swBlur))

        val tvSize = TextView(themed).apply {
            text = "Cỡ chữ: ${prefs.size} sp"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(10f), 0, 0)
        }
        val sSize = Slider(themed).apply {
            valueFrom = 10f
            valueTo = 60f
            stepSize = 1f
            value = prefs.size.toFloat().coerceIn(10f, 60f)
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) {
                    prefs.size = v.toInt()
                    tvSize.text = "Cỡ chữ: ${v.toInt()} sp"
                }
            }
        }
        body.addView(tvSize)
        body.addView(sSize)

        val tvOp = TextView(themed).apply {
            text = "Độ đậm: ${prefs.opacity}%"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(4f), 0, 0)
        }
        val sOp = Slider(themed).apply {
            valueFrom = 20f
            valueTo = 100f
            stepSize = 5f
            value = prefs.opacity.toFloat().coerceIn(20f, 100f)
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) {
                    prefs.opacity = v.toInt()
                    tvOp.text = "Độ đậm: ${v.toInt()}%"
                }
            }
        }
        body.addView(tvOp)
        body.addView(sOp)

        val tvColor = TextView(themed).apply {
            text = "Màu chữ"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(6f), 0, dp(4f))
        }
        val colorRow = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        Swatches.render(colorRow, prefs.color, 36, onPick = { prefs.color = it })
        body.addView(tvColor)
        body.addView(colorRow)

        val alarms = prefs.getAlarms()

        val alarmHeader = LinearLayout(themed).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvAlarmTitle = TextView(themed).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "Báo thức nhấp nháy (${alarms.size}/${Prefs.MAX_ALARMS})"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val btnAddAlarm = TextView(themed).apply {
            text = "+ Thêm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(0x28FFFFFF)
                setStroke(dp(1f), 0x44FFFFFF)
            }
        }
        alarmHeader.addView(tvAlarmTitle)
        alarmHeader.addView(btnAddAlarm)
        body.addView(alarmHeader)

        val alarmsContainer = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(alarmsContainer)

        fun renderAlarmList() {
            alarmsContainer.removeAllViews()
            tvAlarmTitle.text = "Báo thức nhấp nháy (${alarms.size}/${Prefs.MAX_ALARMS})"
            btnAddAlarm.alpha = if (alarms.size < Prefs.MAX_ALARMS) 1f else 0.45f

            if (alarms.isEmpty()) {
                val emptyTv = TextView(themed).apply {
                    text = "Chưa có báo thức nào. Bấm \"+ Thêm\" để tạo (tối đa 5)."
                    setTextColor(0xB3FFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(6f), 0, dp(4f))
                }
                alarmsContainer.addView(emptyTv)
                return
            }

            alarms.forEachIndexed { index, item ->
                val card = LinearLayout(themed).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8f) }
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12f), dp(8f), dp(12f), dp(10f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16f).toFloat()
                        setColor(0x18FFFFFF)
                        setStroke(dp(1f), 0x33FFFFFF)
                    }
                }

                val topRow = LinearLayout(themed).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val tvClock = TextView(themed).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "#${index + 1} · %02d:%02d".format(item.hour, item.minute)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setOnClickListener {
                        openOverlayTimePicker(item.hour, item.minute) { h, m ->
                            item.hour = h
                            item.minute = m
                            text = "#${index + 1} · %02d:%02d".format(h, m)
                            prefs.saveAlarms(alarms)
                            scheduleAlarm(this@ClockService)
                        }
                    }
                }

                val swItem = MaterialSwitch(themed).apply {
                    isChecked = item.enabled
                    setOnCheckedChangeListener { _, on ->
                        item.enabled = on
                        prefs.saveAlarms(alarms)
                        scheduleAlarm(this@ClockService)
                    }
                }

                val btnDel = TextView(themed).apply {
                    text = "✕"
                    setTextColor(0xCCFF8A80.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
                    setOnClickListener {
                        alarms.removeAt(index)
                        prefs.saveAlarms(alarms)
                        scheduleAlarm(this@ClockService)
                        renderAlarmList()
                    }
                }

                topRow.addView(tvClock)
                topRow.addView(swItem)
                topRow.addView(btnDel)
                card.addView(topRow)

                val etItemNote = EditText(themed).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4f) }
                    hint = "Ghi chú báo thức #${index + 1}..."
                    setHintTextColor(0x88FFFFFF.toInt())
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setText(item.note)
                    setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        setColor(0x14FFFFFF)
                    }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            item.note = s?.toString() ?: ""
                            prefs.saveAlarms(alarms)
                        }
                    })
                }
                card.addView(etItemNote)
                alarmsContainer.addView(card)
            }
        }

        btnAddAlarm.setOnClickListener {
            if (alarms.size >= Prefs.MAX_ALARMS) {
                Toast.makeText(this, "Đã đạt tối đa 5 báo thức", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val now = Calendar.getInstance()
            val defaultH = now.get(Calendar.HOUR_OF_DAY)
            val defaultM = (now.get(Calendar.MINUTE) + 5) % 60
            alarms.add(
                AlarmItem(
                    id = UUID.randomUUID().toString().take(8),
                    enabled = true,
                    hour = defaultH,
                    minute = defaultM,
                    note = ""
                )
            )
            prefs.saveAlarms(alarms)
            scheduleAlarm(this)
            renderAlarmList()
        }

        renderAlarmList()

        val btnFull = TextView(themed).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14f) }
            text = "Mở trang đầy đủ (HyperOS / OTA)"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(0x22FFFFFF)
                setStroke(dp(1f), 0x44FFFFFF)
            }
            setOnClickListener {
                hideOverlaySettings()
                runCatching {
                    startActivity(
                        Intent(this@ClockService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        body.addView(btnFull)

        scroll.addView(body)
        container.addView(scroll)

        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            val clockOnTop = (chipLp?.y ?: 0) < dm.heightPixels / 2
            gravity = (if (clockOnTop) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
            y = dp(44f)
            if (Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled) {
                this.flags = this.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = 24
            }
        }

        runCatching { wm.addView(container, lp) }
            .onSuccess { overlaySettings = container }
            .onFailure {
                Log.e(TAG, "show overlay settings lỗi", it)
                runCatching {
                    startActivity(
                        Intent(this@ClockService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    private fun makeSwitchRow(label: String, sw: MaterialSwitch): LinearLayout {
        return LinearLayout(themed).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6f) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            val tv = TextView(themed).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            addView(tv)
            addView(sw)
        }
    }

    private fun openOverlayTimePicker(initH: Int, initM: Int, onPicked: (Int, Int) -> Unit) {
        val dialog = TimePickerDialog(
            themed,
            { _, h, m -> onPicked(h, m) },
            initH,
            initM,
            true
        )
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        try {
            dialog.show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Không mở được bộ chọn giờ", Toast.LENGTH_SHORT).show()
        } catch (e: WindowManager.BadTokenException) {
            val comp = ComponentName(packageName, MainActivity::class.java.name)
            Log.w(TAG, "Không mở được TimePicker overlay cho $comp", e)
            Toast.makeText(this, "Mở trang đầy đủ để chọn giờ báo thức", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideOverlaySettings() {
        overlaySettings?.let { runCatching { wm.removeView(it) } }
        overlaySettings = null
    }

    // ------------------------------------------------------------------ Companion

    companion object {
        private const val TAG = "ClockService"
        private const val CHANNEL_ID = "clock_service"
        private const val NOTIF_ID = 1001

        const val ACTION_STOP = "com.minhthai.floatclock.STOP"
        const val ACTION_ALARM = "com.minhthai.floatclock.ALARM"
        const val ACTION_RESTART = "com.minhthai.floatclock.RESTART"

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, ClockService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                Log.w(TAG, "Không start được service: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ClockService::class.java))
        }

        private fun canExact(am: AlarmManager) =
            Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

        private fun setWake(ctx: Context, am: AlarmManager, whenMs: Long, pi: PendingIntent) {
            try {
                if (canExact(am)) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            }
        }

        fun scheduleAlarm(ctx: Context) {
            val p = Prefs(ctx)
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 10,
                Intent(ctx, ClockReceiver::class.java).setAction(ACTION_ALARM),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val enabledAlarms = p.getAlarms().filter { it.enabled }
            if (enabledAlarms.isEmpty()) {
                am.cancel(pi)
                return
            }

            val nowMs = System.currentTimeMillis()
            val nextTriggerMs = enabledAlarms.minOf { item ->
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, item.hour)
                    set(Calendar.MINUTE, item.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
            }

            setWake(ctx, am, nextTriggerMs, pi)
        }

        fun scheduleRestart(ctx: Context, delayMs: Long = 1_500) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 11,
                Intent(ctx, ClockReceiver::class.java).setAction(ACTION_RESTART),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            setWake(ctx, am, System.currentTimeMillis() + delayMs, pi)
        }
    }
}