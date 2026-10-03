package com.meuplayer.tv

import java.io.InputStream
import java.net.URLDecoder

object M3uParser {
    private val groupRegex = Regex("group-title=\"([^\"]*)\"")
    private val logoRegex = Regex("tvg-logo=\"([^\"]*)\"")
    private val jsonPairRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** Lê o arquivo linha a linha, sem carregar tudo na memória de uma vez. */
    fun parse(input: InputStream): List<Channel> {
        val result = ArrayList<Channel>()
        var name: String? = null
        var group = ""
        var logo = ""
        var headers = LinkedHashMap<String, String>()

        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.removePrefix("﻿").trim()
                if (line.isEmpty()) continue

                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    group = groupRegex.find(line)?.groupValues?.get(1)?.trim().orEmpty()
                    logo = logoRegex.find(line)?.groupValues?.get(1)?.trim().orEmpty()
                    val comma = line.lastIndexOf(',')
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                    parseVlcOpt(line.substring(11), headers)
                } else if (line.startsWith("#EXTHTTP:", ignoreCase = true)) {
                    parseJsonHeaders(line.substring(9), headers)
                } else if (line.startsWith("#")) {
                    continue
                } else {
                    val (cleanUrl, pipeHeaders) = splitPipeHeaders(line)
                    headers.putAll(pipeHeaders)
                    val finalName = name?.takeIf { it.isNotEmpty() } ?: cleanUrl
                    result.add(
                        Channel(
                            name = finalName,
                            url = cleanUrl,
                            group = GroupNames.translate(group),
                            logo = logo,
                            headers = headers
                        )
                    )
                    name = null
                    group = ""
                    logo = ""
                    headers = LinkedHashMap()
                }
            }
        }
        return result
    }

    /** "#EXTVLCOPT:http-user-agent=X" e "http-referrer=Y" (também "http-referer", "http-origin"). */
    private fun parseVlcOpt(opt: String, out: MutableMap<String, String>) {
        val eq = opt.indexOf('=')
        if (eq <= 0) return
        val key = opt.substring(0, eq).trim().lowercase()
        val value = opt.substring(eq + 1).trim().trim('"')
        if (value.isEmpty()) return
        when (key) {
            "http-user-agent" -> out["User-Agent"] = value
            "http-referrer", "http-referer" -> out["Referer"] = value
            "http-origin" -> out["Origin"] = value
            "http-cookie" -> out["Cookie"] = value
        }
    }

    /** "#EXTHTTP:{"cookie":"a=b","User-Agent":"X"}" */
    private fun parseJsonHeaders(json: String, out: MutableMap<String, String>) {
        for (m in jsonPairRegex.findAll(json)) {
            val key = m.groupValues[1].trim()
            val value = m.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
            if (key.isNotEmpty() && value.isNotEmpty()) out[normalizeHeader(key)] = value
        }
    }

    /** Estilo Kodi: "http://host/stream.m3u8|User-Agent=X&Referer=Y". */
    private fun splitPipeHeaders(line: String): Pair<String, Map<String, String>> {
        val bar = line.indexOf('|')
        if (bar < 0 || !line.startsWith("http", ignoreCase = true)) return line to emptyMap()
        val url = line.substring(0, bar).trim()
        val map = LinkedHashMap<String, String>()
        for (part in line.substring(bar + 1).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = decode(part.substring(0, eq).trim())
            val value = decode(part.substring(eq + 1).trim())
            if (key.isNotEmpty() && value.isNotEmpty()) map[normalizeHeader(key)] = value
        }
        return url to map
    }

    private fun decode(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    private fun normalizeHeader(key: String): String = when (key.lowercase()) {
        "user-agent", "useragent", "http-user-agent" -> "User-Agent"
        "referer", "referrer", "http-referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        "authorization" -> "Authorization"
        else -> key
    }
}
