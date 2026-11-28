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
    private const val PREFS_LAST_CHECK = "updater_last_check"
    private const val PREFS_CACHED_TAG = "updater_cached_tag"
    private const val PREFS_CACHED_URL = "updater_cached_url"
    private const val PREFS_CACHED_NOTES = "updater_cached_notes"
    private const val CHECK_COOLDOWN_MS = 6 * 3600 * 1000L // 6 hours

    fun currentVersion(c: Context): String = runCatching {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(c.packageName, 0).versionName
    }.getOrNull() ?: "0.0"

    fun getCachedRelease(c: Context): ReleaseInfo? {
        val tag = c.prefs.getString(PREFS_CACHED_TAG, null) ?: return null
        val url = c.prefs.getString(PREFS_CACHED_URL, "") ?: ""
        val notes = c.prefs.getString(PREFS_CACHED_NOTES, "") ?: ""
        return ReleaseInfo(tag = tag, versionName = tag.trimStart('v', 'V'), apkUrl = url, notes = notes)
    }

    fun isNewerVersion(remote: String, current: String): Boolean {
        // Normalise: strip leading v/V, then split on dots and dashes to get segments
        fun segments(v: String): List<String> =
            v.trimStart('v', 'V').replace('-', '.').split('.')

        fun segValue(s: String): Int = s.takeWhile(Char::isDigit).toIntOrNull() ?: -1
        fun isPreRelease(s: String) = s.any { it.isLetter() } // e.g. "alpha", "beta", "rc"

        val rs = segments(remote)
        val cs = segments(current)
        val maxLen = maxOf(rs.size, cs.size)
        for (i in 0 until maxLen) {
            val r = rs.getOrElse(i) { "" }
            val c = cs.getOrElse(i) { "" }
            if (r == c) continue
            val rv = segValue(r); val cv = segValue(c)
            if (rv != cv) return rv > cv
            // Same numeric part but different pre-release labels — compare lexicographically
            if (isPreRelease(r) && isPreRelease(c)) return r > c
            if (isPreRelease(r)) return false // pre-release < release
            if (isPreRelease(c)) return true  // release > pre-release
        }
        return false
    }

    suspend fun checkLatestRelease(c: Context? = null, force: Boolean = false): ReleaseInfo = withContext(Dispatchers.IO) {
        if (!force && c != null) {
            val lastCheck = c.prefs.getLong(PREFS_LAST_CHECK, 0L)
            if (System.currentTimeMillis() - lastCheck < CHECK_COOLDOWN_MS) {
                val cached = getCachedRelease(c)
                if (cached != null) return@withContext cached
            }
        }

        val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LesStress-Android")
        }
        try {
            val code = conn.responseCode
            if (code == 403) {
                if (c != null) {
                    val cached = getCachedRelease(c)
                    if (cached != null) return@withContext cached
                }
                throw IllegalStateException("GitHub API limiet bereikt. Probeer later opnieuw.")
            }
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
            val info = ReleaseInfo(tag = tag, versionName = versionName, apkUrl = finalUrl, notes = notes)

            if (c != null) {
                c.prefs.edit()
                    .putLong(PREFS_LAST_CHECK, System.currentTimeMillis())
                    .putString(PREFS_CACHED_TAG, tag)
                    .putString(PREFS_CACHED_URL, finalUrl)
                    .putString(PREFS_CACHED_NOTES, notes)
                    .apply()
            }

            info
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

    fun getDownloadedApk(c: Context): File? {
        val f = File(c.cacheDir, "updates/LesStress.apk")
        return if (f.exists() && f.length() > 0) f else null
    }

    fun clearDownloadedApk(c: Context) {
        runCatching {
            File(c.cacheDir, "updates").deleteRecursively()
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
