package be.lesstress

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

object AppUpdater {
    private const val REPO = "sanderdelanghe/LesStress"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    fun currentVersion(c: Context): String = runCatching {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(c.packageName, 0).versionName
    }.getOrNull() ?: "0.0"

    fun isNewerVersion(remote: String, current: String): Boolean {
        val r = remote.trimStart('v', 'V').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val c = current.trimStart('v', 'V').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val maxLen = maxOf(r.size, c.size)
        for (i in 0 until maxLen) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    suspend fun checkLatestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LesStress-Android")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("GitHub HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val tag = j.optString("tag_name").ifBlank { throw IllegalStateException("Missing tag_name") }
            val versionName = tag.trimStart('v', 'V')
            val notes = j.optString("body", "").trim()

            var apkUrl: String? = null
            val assets = j.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    val dl = a.optString("browser_download_url")
                    if (name.equals("LesStress.apk", ignoreCase = true) && dl.isNotBlank()) {
                        apkUrl = dl
                        break
                    }
                    if (apkUrl == null && name.endsWith(".apk", ignoreCase = true) && dl.isNotBlank()) {
                        apkUrl = dl
                    }
                }
            }
            val finalUrl = apkUrl ?: "https://github.com/$REPO/releases/download/$tag/LesStress.apk"
            ReleaseInfo(tag = tag, versionName = versionName, apkUrl = finalUrl, notes = notes)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun downloadApk(c: Context, url: String, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        val tmp = File(dir, "LesStress.apk.tmp")
        val target = File(dir, "LesStress.apk")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LesStress-Android")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("Download HTTP $code")
            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val p = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                        withContext(Dispatchers.Main) { onProgress(p) }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } finally {
            conn.disconnect()
        }
    }

    fun canInstallPackages(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || c.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { c.startActivity(intent) }
        }
    }

    fun installApk(c: Context, file: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        c.startActivity(intent)
    }
}
