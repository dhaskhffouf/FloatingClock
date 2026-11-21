package com.minhthai.floatclock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Cập nhật OTA qua GitHub Releases:
 *   1. Gọi API releases/latest của repo.
 *   2. So tag (vd v1.0.1) với versionName hiện tại.
 *   3. Tải file .apk đính kèm release rồi mở trình cài đặt của hệ thống.
 */
object Updater {

    data class Release(
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val size: Long
    )

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun isNewer(remote: String, local: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v").removePrefix("V")
            .substringBefore("-")
            .split(".")
            .map { p -> p.filter { it.isDigit() }.toIntOrNull() ?: 0 }

        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Kết quả trả về trên luồng chính. Release = null nghĩa là đã là bản mới nhất. */
    fun check(callback: (Result<Release?>) -> Unit) {
        io.execute {
            val result = runCatching { fetchLatest() }
            main.post { callback(result) }
        }
    }

    private fun fetchLatest(): Release? {
        val url = URL(
            "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
        )
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "FloatingClock-Updater")

            val code = c.responseCode
            if (code == 404) return null // repo chưa có release nào
            if (code !in 200..299) error("GitHub trả về mã $code")

            val body = c.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.getString("tag_name")
            if (!isNewer(tag, BuildConfig.VERSION_NAME)) return null

            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        tag = tag,
                        notes = json.optString("body", ""),
                        apkUrl = a.getString("browser_download_url"),
                        size = a.optLong("size", 0L)
                    )
                }
            }
            error("Release $tag không có file .apk")
        } finally {
            c.disconnect()
        }
    }

    /** Tải APK. onProgress (0..100) và onDone đều chạy trên luồng chính. */
    fun download(
        ctx: Context,
        release: Release,
        onProgress: (Int) -> Unit,
        onDone: (Result<File>) -> Unit
    ) {
        val app = ctx.applicationContext
        io.execute {
            val result = runCatching {
                val dir = File(app.externalCacheDir ?: app.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val part = File(dir, "FloatingClock-${release.tag}.apk.part")
                val out = File(dir, "FloatingClock-${release.tag}.apk")

                val c = URL(release.apkUrl).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    c.setRequestProperty("User-Agent", "FloatingClock-Updater")
                    if (c.responseCode !in 200..299) error("Tải lỗi, mã ${c.responseCode}")

                    val total = if (c.contentLengthLong > 0) c.contentLengthLong else release.size
                    var read = 0L
                    var lastPct = -1
                    c.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                read += n
                                if (total > 0) {
                                    val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        main.post { onProgress(pct) }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                if (!part.renameTo(out)) error("Không lưu được file APK")
                out
            }
            main.post { onDone(result) }
        }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Mở trang cấp quyền "Cài ứng dụng không rõ nguồn" cho chính app này. */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
