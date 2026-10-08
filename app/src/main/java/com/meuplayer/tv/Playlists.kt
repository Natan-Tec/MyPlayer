package com.meuplayer.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class Playlist(val id: String, val name: String, val url: String)

/** As listas que o usuário adicionou e qual delas está ativa. */
object PlaylistStore {
    private const val PREFS = "m3u"
    private const val KEY_LISTS = "lists_json"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_LEGACY_URL = "url"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Playlist> {
        val raw = prefs(ctx).getString(KEY_LISTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Playlist(o.getString("id"), o.getString("name"), o.getString("url"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(ctx: Context, lists: List<Playlist>) {
        val arr = JSONArray()
        for (l in lists) {
            arr.put(JSONObject().put("id", l.id).put("name", l.name).put("url", l.url))
        }
        prefs(ctx).edit().putString(KEY_LISTS, arr.toString()).apply()
    }

    fun active(ctx: Context): Playlist? {
        val lists = all(ctx)
        val id = prefs(ctx).getString(KEY_ACTIVE, null)
        return lists.firstOrNull { it.id == id } ?: lists.firstOrNull()
    }

    fun setActive(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun add(ctx: Context, url: String, name: String? = null): Playlist {
        val lists = all(ctx).toMutableList()
        val p = Playlist(
            UUID.randomUUID().toString().take(8),
            name?.trim()?.takeIf { it.isNotEmpty() } ?: suggestName(url),
            url.trim()
        )
        lists.add(p)
        write(ctx, lists)
        // A primeira lista, ou a mais recente, vira a ativa.
        setActive(ctx, p.id)
        return p
    }

    fun update(ctx: Context, id: String, name: String, url: String) {
        val newUrl = url.trim()
        val lists = all(ctx).map {
            if (it.id == id) Playlist(id, name.trim().ifEmpty { suggestName(newUrl) }, newUrl) else it
        }
        write(ctx, lists)
        // Se o link mudou, a cópia salva é outra lista: descarta.
        cacheFile(ctx, id).delete()
    }

    fun remove(ctx: Context, id: String) {
        val wasActive = active(ctx)?.id == id
        write(ctx, all(ctx).filter { it.id != id })
        cacheFile(ctx, id).delete()
        if (wasActive) {
            all(ctx).firstOrNull()?.let { setActive(ctx, it.id) }
                ?: prefs(ctx).edit().remove(KEY_ACTIVE).apply()
        }
    }

    fun cacheFile(ctx: Context, id: String): File {
        val dir = File(ctx.applicationContext.cacheDir, "lists")
        dir.mkdirs()
        return File(dir, "$id.m3u")
    }

    /** Versões antigas guardavam um único link; vira a primeira lista. */
    fun migrateLegacy(ctx: Context) {
        val p = prefs(ctx)
        val legacy = p.getString(KEY_LEGACY_URL, null)
        if (!legacy.isNullOrBlank() && all(ctx).isEmpty()) add(ctx, legacy, "Minha lista")
        if (legacy != null) p.edit().remove(KEY_LEGACY_URL).apply()
    }

    fun suggestName(url: String): String {
        return try {
            val u = URL(url.trim())
            val file = u.path.substringAfterLast('/').substringBeforeLast('.')
            val host = u.host.removePrefix("www.")
            if (file.isNotBlank() && file != "index") "$host · $file" else host
        } catch (e: Exception) {
            "Minha lista"
        }
    }
}

/** Baixa e lê as listas, guardando uma cópia para abrir rápido (e offline) da próxima vez. */
object PlaylistRepo {
    private const val STALE_MS = 12L * 60 * 60 * 1000

    fun cached(ctx: Context, p: Playlist): List<Channel>? {
        val f = PlaylistStore.cacheFile(ctx, p.id)
        if (!f.exists()) return null
        return try {
            f.inputStream().use { M3uParser.parse(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun isStale(ctx: Context, p: Playlist): Boolean {
        val f = PlaylistStore.cacheFile(ctx, p.id)
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > STALE_MS
    }

    @Throws(IOException::class)
    fun download(ctx: Context, p: Playlist): List<Channel> {
        val f = PlaylistStore.cacheFile(ctx, p.id)
        fetch(p.url, f)
        return f.inputStream().use { M3uParser.parse(it) }
    }

    /** Baixa o arquivo seguindo redirecionamentos (inclusive http -> https). */
    @Throws(IOException::class)
    private fun fetch(startUrl: String, dest: File) {
        var current = startUrl
        for (attempt in 0 until 6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", USER_AGENT)
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
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            return
        }
        throw IOException("Muitos redirecionamentos")
    }
}
