package com.meuplayer.tv

import android.content.Context
import android.content.SharedPreferences

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String,
    /** Cabeçalhos HTTP pedidos pela própria lista (User-Agent, Referer, Origin, Cookie...). */
    val headers: Map<String, String> = emptyMap()
) {
    /** Nome sem a marca de resolução, ex.: "Canal (1080p)" vira "Canal". */
    val displayName: String by lazy {
        name.replace(RES_TAG, "").replace(Regex("\\s{2,}"), " ").trim().ifEmpty { name }
    }

    /** Resolução (altura) indicada no próprio nome do canal, se houver. */
    val nameResolution: Int? by lazy {
        val m = RES_TAG.find(name) ?: return@lazy null
        val raw = m.groupValues[1].trim()
        if (raw.equals("4K", ignoreCase = true)) 2160 else raw.filter { it.isDigit() }.toIntOrNull()
    }

    private companion object {
        // Só aceita marcas como "(1080p)", "[720p]", "(576i)" ou "(4K)"; "(2024)" não conta.
        val RES_TAG = Regex("\\s*[(\\[]\\s*(\\d{3,4}\\s*[pPiI]|4K)\\s*[)\\]]", RegexOption.IGNORE_CASE)
    }
}

const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

/** Chave (no SharedPreferences "m3u") do endereço do último canal assistido. */
const val PREF_LAST_URL = "last_url"

/** Chave (no SharedPreferences "m3u") da opção "Aceitar certificados inválidos" (desligada por padrão). */
const val PREF_INSECURE_SSL = "insecure_ssl"

/** Guarda a lista atual e o canal escolhido para o player poder trocar de canal. */
object PlayerState {
    var channels: List<Channel> = emptyList()
    var index: Int = 0
}

/** Resolução real dos canais, aprendida quando você assiste (mostrada no selo dos cartões). */
object ResolutionStore {
    private var prefs: SharedPreferences? = null

    private fun p(ctx: Context): SharedPreferences =
        prefs ?: ctx.applicationContext.getSharedPreferences("resolutions", Context.MODE_PRIVATE)
            .also { prefs = it }

    private fun key(url: String): String = Integer.toHexString(url.hashCode())

    fun get(ctx: Context, url: String): Int? =
        p(ctx).getInt(key(url), 0).takeIf { it > 0 }

    fun put(ctx: Context, url: String, height: Int) {
        if (height <= 0) return
        val k = key(url)
        if (p(ctx).getInt(k, 0) != height) p(ctx).edit().putInt(k, height).apply()
    }

    /** Altura do vídeo -> rótulo do selo. */
    fun label(height: Int): String = when {
        height >= 2000 -> "4K"
        height >= 1300 -> "1440p"
        height >= 1000 -> "1080p"
        height >= 680 -> "720p"
        height >= 520 -> "576p"
        height >= 420 -> "480p"
        height >= 300 -> "360p"
        else -> "240p"
    }

    /** Rótulo do selo de um canal: resolução real, ou a que o nome indica; nulo se não souber. */
    fun badgeFor(ctx: Context, ch: Channel): String? =
        (get(ctx, ch.url) ?: ch.nameResolution)?.let { label(it) }
}
