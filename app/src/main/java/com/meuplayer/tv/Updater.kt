package com.meuplayer.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val apkUrl: String?,
    val size: Long
)

enum class InstallResult { STARTED, NEEDS_PERMISSION, FAILED }

/** Verifica as Releases do GitHub, baixa o APK novo e abre o instalador do Android. */
object Updater {
    private const val API = "https://api.github.com/repos/Natan-Tec/MyPlayer/releases/latest"
    private const val PREFS = "m3u"
    private const val KEY_LAST_CHECK = "update_checked_at"
    private const val KEY_LAST_TAG = "update_tag"
    private const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000

    fun installedVersion(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** Última versão estável publicada (não conta rascunhos nem pré-lançamentos). */
    @Throws(IOException::class)
    fun fetchLatest(): ReleaseInfo? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "M3UFlow")
        try {
            val code = conn.responseCode
            if (code == 404) return null // ainda não há nenhuma release
            if (code != 200) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isEmpty()) return null
            var apk: String? = null
            var size = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            return ReleaseInfo(tag, tag.removePrefix("v").removePrefix("V"), json.optString("body"), apk, size)
        } finally {
            conn.disconnect()
        }
    }

    /** "1.10.0" é maior que "1.9.2". Versões que não são números (ex.: "dev") contam como 0. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").split('.', '-', '+').map { it.toIntOrNull() ?: 0 }

    // ---------- Aviso na tela inicial ----------

    /** Confere em segundo plano, no máximo a cada 12 horas, e guarda o resultado. */
    fun checkQuietly(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        try {
            val rel = fetchLatest()
            p.edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putString(KEY_LAST_TAG, rel?.version.orEmpty())
                .apply()
        } catch (e: Exception) {
            // Sem internet agora: tenta de novo na próxima abertura.
        }
    }

    fun knownNewerVersion(ctx: Context): String? {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = p.getString(KEY_LAST_TAG, null)?.takeIf { it.isNotBlank() } ?: return null
        return if (isNewer(tag, installedVersion(ctx))) tag else null
    }

    // ---------- Download e instalação ----------

    fun updatesDir(ctx: Context): File = File(ctx.applicationContext.cacheDir, "updates").also { it.mkdirs() }

    @Throws(IOException::class)
    fun download(ctx: Context, rel: ReleaseInfo, onProgress: (Int) -> Unit): File {
        val url = rel.apkUrl ?: throw IOException("Esta versão não tem arquivo para instalar")
        val dir = updatesDir(ctx)
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, "M3UFlow-${rel.version}.apk")
        val tmp = File(dir, dest.name + ".part")

        var current = url
        for (attempt in 0 until 6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "M3UFlow")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) throw IOException("Redirecionamento inválido")
                current = URL(URL(current), loc).toString()
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.size
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return dest
        }
        throw IOException("Muitos redirecionamentos")
    }

    fun install(activity: Activity, apk: File): InstallResult {
        return try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                // O Android pede uma autorização única para este app instalar atualizações.
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                } catch (e: Exception) {
                    try {
                        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    } catch (e2: Exception) {
                        // Sem tela de configurações disponível: o aviso na tela explica.
                    }
                }
                InstallResult.NEEDS_PERMISSION
            } else {
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                InstallResult.STARTED
            }
        } catch (e: Exception) {
            InstallResult.FAILED
        }
    }
}
