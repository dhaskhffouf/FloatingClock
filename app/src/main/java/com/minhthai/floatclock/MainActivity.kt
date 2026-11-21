package com.minhthai.floatclock

import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var swMain: MaterialSwitch
    private lateinit var tvPerm: TextView
    private lateinit var btnPerm: TextView
    private lateinit var swAlarm: MaterialSwitch
    private lateinit var btnAlarmTime: TextView
    private lateinit var etNote: EditText
    private lateinit var colorRow: LinearLayout
    private lateinit var tvSize: TextView
    private lateinit var sliderSize: Slider
    private lateinit var tvOpacity: TextView
    private lateinit var sliderOpacity: Slider
    private lateinit var swSeconds: MaterialSwitch
    private lateinit var tvBlur: TextView
    private lateinit var swBlur: MaterialSwitch
    private lateinit var tvUpdate: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnUpdate: TextView

    private var syncing = false
    private var pendingEnable = false
    private var updateBusy = false

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* không bắt buộc */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        setContentView(R.layout.activity_main)

        val content = findViewById<LinearLayout>(R.id.content)
        val pad = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scroll)) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        bindViews()
        bindMain()
        bindAlarm()
        bindStyle()
        bindXiaomi()
        bindUpdate()
    }

    override fun onResume() {
        super.onResume()
        val draw = canDraw()
        refreshPermissionUi(draw)

        if (pendingEnable && draw) {
            pendingEnable = false
            enableClock()
        }
        if (prefs.enabled && !draw) prefs.enabled = false

        syncing = true
        swMain.isChecked = prefs.enabled && draw
        syncing = false

        if (prefs.enabled && draw) ClockService.start(this)
    }

    // ------------------------------------------------------------------ Bind

    private fun bindViews() {
        swMain = findViewById(R.id.swMain)
        tvPerm = findViewById(R.id.tvPerm)
        btnPerm = findViewById(R.id.btnPerm)
        swAlarm = findViewById(R.id.swAlarm)
        btnAlarmTime = findViewById(R.id.btnAlarmTime)
        etNote = findViewById(R.id.etNote)
        colorRow = findViewById(R.id.colorRow)
        tvSize = findViewById(R.id.tvSize)
        sliderSize = findViewById(R.id.sliderSize)
        tvOpacity = findViewById(R.id.tvOpacity)
        sliderOpacity = findViewById(R.id.sliderOpacity)
        swSeconds = findViewById(R.id.swSeconds)
        tvBlur = findViewById(R.id.tvBlur)
        swBlur = findViewById(R.id.swBlur)
        tvUpdate = findViewById(R.id.tvUpdate)
        progress = findViewById(R.id.progress)
        btnUpdate = findViewById(R.id.btnUpdate)

        findViewById<TextView>(R.id.tvVersion).text = "by Minh Thái · v${BuildConfig.VERSION_NAME}"
    }

    private fun bindMain() {
        swMain.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            if (on) {
                if (!canDraw()) {
                    syncing = true
                    swMain.isChecked = false
                    syncing = false
                    askOverlayPermission()
                } else {
                    enableClock()
                }
            } else {
                prefs.enabled = false
                ClockService.stop(this)
            }
        }
        btnPerm.setOnClickListener { askOverlayPermission() }
    }

    private fun bindAlarm() {
        swAlarm.isChecked = prefs.alarmOn
        swAlarm.setOnCheckedChangeListener { _, on ->
            prefs.alarmOn = on
            ClockService.scheduleAlarm(this)
        }

        updateAlarmText()
        btnAlarmTime.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                prefs.alarmH = h
                prefs.alarmM = m
                updateAlarmText()
                ClockService.scheduleAlarm(this)
            }, prefs.alarmH, prefs.alarmM, true).show()
        }

        etNote.setText(prefs.note)
        etNote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.note = s?.toString() ?: ""
            }
        })
    }

    private fun updateAlarmText() {
        btnAlarmTime.text = "Giờ báo thức: %02d:%02d".format(prefs.alarmH, prefs.alarmM)
    }

    private fun bindStyle() {
        renderColors()

        sliderSize.value = prefs.size.toFloat().coerceIn(10f, 60f)
        tvSize.text = "Cỡ chữ: ${prefs.size} sp"
        sliderSize.addOnChangeListener { _, v, fromUser ->
            if (fromUser) {
                prefs.size = v.toInt()
                tvSize.text = "Cỡ chữ: ${v.toInt()} sp"
            }
        }

        sliderOpacity.value = prefs.opacity.toFloat().coerceIn(20f, 100f)
        tvOpacity.text = "Độ đậm: ${prefs.opacity}%"
        sliderOpacity.addOnChangeListener { _, v, fromUser ->
            if (fromUser) {
                prefs.opacity = v.toInt()
                tvOpacity.text = "Độ đậm: ${v.toInt()}%"
            }
        }

        swSeconds.isChecked = prefs.seconds
        swSeconds.setOnCheckedChangeListener { _, on -> prefs.seconds = on }

        swBlur.isChecked = prefs.blur && Build.VERSION.SDK_INT >= 31
        swBlur.isEnabled = Build.VERSION.SDK_INT >= 31
        tvBlur.alpha = if (swBlur.isEnabled) 1f else 0.5f
        swBlur.setOnCheckedChangeListener { _, on -> prefs.blur = on }
    }

    private fun renderColors() {
        Swatches.render(colorRow, prefs.color, 44, onPick = { prefs.color = it }, onCustom = { askCustomColor() })
    }

    private fun askCustomColor() {
        val et = EditText(this).apply {
            hint = "#RRGGBB"
            setSingleLine()
            setText("#")
            setSelection(1)
        }
        val box = FrameLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("Nhập mã màu")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                try {
                    prefs.color = Color.parseColor(et.text.toString().trim()) or 0xFF000000.toInt()
                    renderColors()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, "Mã màu không hợp lệ", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ------------------------------------------------------------------ Quyền

    private fun canDraw() = Settings.canDrawOverlays(this)

    private fun refreshPermissionUi(draw: Boolean) {
        if (draw) {
            tvPerm.text = "✓ Đã cấp quyền hiển thị trên ứng dụng khác"
            btnPerm.visibility = View.GONE
        } else {
            tvPerm.text = "Cần cấp quyền \"Hiển thị trên các ứng dụng khác\" để đồng hồ nổi lên được."
            btnPerm.visibility = View.VISIBLE
        }
    }

    private fun askOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Cấp quyền hiển thị")
            .setMessage("Ở màn hình tiếp theo, bật \"Cho phép hiển thị trên các ứng dụng khác\" cho Đồng Hồ Nổi rồi quay lại.")
            .setPositiveButton("Mở cài đặt") { _, _ ->
                pendingEnable = true
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun enableClock() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        prefs.enabled = true
        ClockService.start(this)
        syncing = true
        swMain.isChecked = true
        syncing = false
    }

    // ------------------------------------------------------------------ Xiaomi / HyperOS

    private fun bindXiaomi() {
        findViewById<View>(R.id.btnBattery).setOnClickListener {
            toast("Chọn \"Không hạn chế\" cho Đồng Hồ Nổi")
            tryStart(
                Intent().setComponent(
                    ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                ).putExtra("package_name", packageName)
                    .putExtra("package_label", getString(R.string.app_name)),
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                appDetails()
            )
        }
        findViewById<View>(R.id.btnAutostart).setOnClickListener {
            toast("Bật công tắc Tự khởi động cho Đồng Hồ Nổi")
            tryStart(
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                ),
                appDetails()
            )
        }
        findViewById<View>(R.id.btnPopup).setOnClickListener {
            toast("Bật \"Hiển thị cửa sổ pop-up khi chạy nền\"")
            tryStart(
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                    )
                    .putExtra("extra_pkgname", packageName),
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                    )
                    .putExtra("extra_pkgname", packageName),
                appDetails()
            )
        }
        findViewById<View>(R.id.btnLock).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Khóa app trong đa nhiệm")
                .setMessage(
                    "1. Mở màn hình đa nhiệm (vuốt từ dưới lên và giữ, hoặc nút đa nhiệm).\n" +
                        "2. Nhấn giữ thẻ \"Đồng Hồ Nổi\" (hoặc chạm biểu tượng ⋮ trên thẻ).\n" +
                        "3. Chọn biểu tượng ổ khóa để khóa.\n\n" +
                        "Khi có ổ khóa, HyperOS sẽ không dọn app này khi bấm \"Xóa tất cả\"."
                )
                .setPositiveButton("Đã hiểu", null)
                .show()
        }
    }

    private fun appDetails() = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")
    )

    /** Thử lần lượt từng intent; cái nào mở được thì dừng. */
    private fun tryStart(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (e: ActivityNotFoundException) {
                // thử cái tiếp theo
            } catch (e: SecurityException) {
                // thử cái tiếp theo
            }
        }
        toast("Không mở được trang cài đặt, hãy vào Cài đặt > Ứng dụng thủ công")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ Cập nhật OTA

    private fun bindUpdate() {
        tvUpdate.text = "Phiên bản hiện tại: v${BuildConfig.VERSION_NAME}"
        btnUpdate.setOnClickListener { checkUpdate(silent = false) }

        // Tự kiểm tra âm thầm mỗi 6 giờ
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck > 6 * 3600 * 1000L) {
            prefs.lastUpdateCheck = now
            checkUpdate(silent = true)
        }
    }

    private fun checkUpdate(silent: Boolean) {
        if (updateBusy) return
        updateBusy = true
        if (!silent) {
            tvUpdate.text = "Đang kiểm tra…"
            btnUpdate.isEnabled = false
        }
        Updater.check { res ->
            updateBusy = false
            if (isFinishing || isDestroyed) return@check
            btnUpdate.isEnabled = true
            res.onSuccess { rel ->
                if (rel == null) {
                    tvUpdate.text = "Bạn đang dùng bản mới nhất (v${BuildConfig.VERSION_NAME})"
                } else {
                    tvUpdate.text = "Có bản mới: ${rel.tag}"
                    showUpdateDialog(rel)
                }
            }.onFailure {
                if (!silent) tvUpdate.text = "Không kiểm tra được: ${it.message}"
            }
        }
    }

    private fun showUpdateDialog(rel: Updater.Release) {
        val mb = if (rel.size > 0) " (%.1f MB)".format(rel.size / 1048576.0) else ""
        val notes = rel.notes.trim().take(600)
        AlertDialog.Builder(this)
            .setTitle("Có bản mới ${rel.tag}$mb")
            .setMessage(if (notes.isEmpty()) "Bạn muốn cập nhật ngay không?" else notes)
            .setPositiveButton("Cập nhật") { _, _ -> startDownload(rel) }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun startDownload(rel: Updater.Release) {
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("Cần cấp quyền cài đặt")
                .setMessage("Bật \"Cho phép từ nguồn này\" cho Đồng Hồ Nổi, rồi quay lại và bấm Kiểm tra cập nhật lần nữa.")
                .setPositiveButton("Mở cài đặt") { _, _ -> Updater.openInstallPermission(this) }
                .setNegativeButton("Huỷ", null)
                .show()
            return
        }
        progress.visibility = View.VISIBLE
        progress.progress = 0
        btnUpdate.isEnabled = false
        tvUpdate.text = "Đang tải ${rel.tag}… 0%"

        Updater.download(this, rel,
            onProgress = { pct ->
                if (!isFinishing && !isDestroyed) {
                    progress.progress = pct
                    tvUpdate.text = "Đang tải ${rel.tag}… $pct%"
                }
            },
            onDone = { res ->
                if (!isFinishing && !isDestroyed) {
                    progress.visibility = View.GONE
                    btnUpdate.isEnabled = true
                    res.onSuccess {
                        tvUpdate.text = "Đã tải xong, đang mở trình cài đặt…"
                        Updater.install(this, it)
                    }.onFailure {
                        tvUpdate.text = "Tải thất bại: ${it.message}"
                    }
                }
            })
    }
}
