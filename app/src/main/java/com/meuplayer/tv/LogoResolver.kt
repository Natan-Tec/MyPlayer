package com.meuplayer.tv

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * Descobre o logo de um canal pelo nome, usando a base pública do iptv-org.
 *
 * Baixa a lista uma vez (e renova a cada 30 dias), guarda só um índice compacto
 * (nome simplificado -> URL do logo) e depois a busca é só uma consulta em memória.
 */
class LogoResolver(context: Context) {

    private class Index(val map: Map<String, String>) {
        val memo = HashMap<String, String>()
    }

    private val indexFile = File(context.filesDir, "logo_index.tsv")
    private val rawFile = File(context.cacheDir, "logos.json")

    @Volatile
    private var index: Index? = null

    /** Chamar em thread de fundo. Retorna true se há um índice pronto para uso. */
    fun load(): Boolean {
        return try {
            val stale = !indexFile.exists() ||
                System.currentTimeMillis() - indexFile.lastModified() > MAX_AGE_MS
            if (stale) {
                try {
                    buildIndex()
                } catch (e: Exception) {
                    if (!indexFile.exists()) return false
                }
            }
            val map = readIndex()
            index = Index(map)
            map.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /** Retorna a URL do logo para o nome do canal, ou null se não achar. */
    fun find(name: String): String? {
        val idx = index ?: return null
        val cached = idx.memo[name]
        if (cached != null) return cached.ifEmpty { null }
        val result = lookup(idx.map, name)
        idx.memo[name] = result ?: ""
        return result
    }

    // ---------- Busca ----------

    private fun lookup(map: Map<String, String>, name: String): String? {
        val tokens = tokensOf(name)
        if (tokens.isEmpty()) return null
        for (n in tokens.size downTo 1) {
            val key = tokens.take(n).joinToString("")
            val partial = n < tokens.size
            if (partial && (key.length < 4 || key in GENERIC)) continue
            map[key]?.let { return it }
            map["tv$key"]?.let { return it }
        }
        return null
    }

    private fun tokensOf(name: String): List<String> {
        var s = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
        s = s.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ") // [HD], (BR)
        s = s.replace(Regex("^\\s*[a-z]{2,3}\\s*[|:]\\s*"), "") // "BR | ", "PT: "
        return s.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in NOISE }
    }

    private fun simplify(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .filter { it.isLetterOrDigit() }

    // ---------- Índice ----------

    private fun readIndex(): Map<String, String> {
        val map = HashMap<String, String>()
        indexFile.bufferedReader().useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
        return map
    }

    private fun buildIndex() {
        val conn = URL(LOGOS_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        if (conn.responseCode != 200) {
            val code = conn.responseCode
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        conn.inputStream.use { input ->
            rawFile.outputStream().use { output -> input.copyTo(output) }
        }
        conn.disconnect()

        val bestRank = HashMap<String, Int>()
        val bestScore = HashMap<String, Int>()
        val bestUrl = HashMap<String, String>()

        JsonReader(rawFile.bufferedReader()).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var channel = ""
                var format = ""
                var url = ""
                var inUse = false
                var hasFeed = false

                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "channel" -> channel = reader.stringOrNull().orEmpty()
                        "format" -> format = reader.stringOrNull().orEmpty()
                        "url" -> url = reader.stringOrNull().orEmpty()
                        "in_use" -> inUse = reader.boolOrFalse()
                        "feed" -> {
                            if (reader.peek() == JsonToken.NULL) {
                                reader.nextNull()
                            } else {
                                reader.skipValue()
                                hasFeed = true
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (channel.isEmpty() || url.isEmpty()) continue
                if (format.equals("SVG", ignoreCase = true)) continue
                val country = channel.substringAfterLast('.', "").lowercase()
                val rank = COUNTRY_ORDER.indexOf(country)
                if (rank < 0) continue

                val key = simplify(channel.substringBeforeLast('.'))
                if (key.isEmpty()) continue
                val score = (if (hasFeed) 0 else 2) + (if (inUse) 1 else 0)

                val prevRank = bestRank[key]
                if (prevRank == null ||
                    rank < prevRank ||
                    (rank == prevRank && score > (bestScore[key] ?: -1))
                ) {
                    bestRank[key] = rank
                    bestScore[key] = score
                    bestUrl[key] = url
                }
            }
            reader.endArray()
        }

        val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
        tmp.bufferedWriter().use { writer ->
            for ((key, url) in bestUrl) {
                writer.write(key)
                writer.write("\t")
                writer.write(url)
                writer.newLine()
            }
        }
        tmp.copyTo(indexFile, overwrite = true)
        tmp.delete()
        rawFile.delete()
    }

    private fun JsonReader.stringOrNull(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }

    private fun JsonReader.boolOrFalse(): Boolean =
        if (peek() == JsonToken.NULL) {
            nextNull()
            false
        } else {
            nextBoolean()
        }

    companion object {
        private const val LOGOS_URL = "https://iptv-org.github.io/api/logos.json"
        private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        // Preferência de país quando o mesmo canal existe em vários.
        private val COUNTRY_ORDER = listOf("br", "pt", "us", "ar", "mx", "es")

        // Termos que não fazem parte do nome do canal.
        private val NOISE = setOf(
            "hd", "fhd", "uhd", "sd", "4k", "8k", "hdtv", "h264", "h265", "hevc",
            "1080p", "720p", "1080", "720", "fps", "50fps", "60fps", "raw"
        )

        // Palavras genéricas demais para aceitar como busca parcial.
        private val GENERIC = setOf(
            "canal", "rede", "tv", "tele", "filmes", "series", "esportes", "sports",
            "news", "kids", "cine", "radio", "brasil", "noticias", "cinema"
        )
    }
}
