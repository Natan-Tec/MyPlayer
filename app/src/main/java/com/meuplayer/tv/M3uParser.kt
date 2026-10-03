package com.meuplayer.tv

import java.io.InputStream

object M3uParser {
    private val groupRegex = Regex("group-title=\"([^\"]*)\"")
    private val logoRegex = Regex("tvg-logo=\"([^\"]*)\"")

    /** Lê o arquivo linha a linha, sem carregar tudo na memória de uma vez. */
    fun parse(input: InputStream): List<Channel> {
        val result = ArrayList<Channel>()
        var name: String? = null
        var group = ""
        var logo = ""

        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.removePrefix("﻿").trim()
                if (line.isEmpty()) continue

                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    group = groupRegex.find(line)?.groupValues?.get(1)?.trim().orEmpty()
                    logo = logoRegex.find(line)?.groupValues?.get(1)?.trim().orEmpty()
                    val comma = line.lastIndexOf(',')
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                } else if (line.startsWith("#")) {
                    continue
                } else {
                    val finalName = name?.takeIf { it.isNotEmpty() } ?: line
                    result.add(
                        Channel(
                            name = finalName,
                            url = line,
                            group = GroupNames.translate(group),
                            logo = logo
                        )
                    )
                    name = null
                    group = ""
                    logo = ""
                }
            }
        }
        return result
    }
}
