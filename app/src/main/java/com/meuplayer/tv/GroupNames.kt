package com.meuplayer.tv

/**
 * Categorias em português.
 *
 * As listas do iptv-org trazem categorias em inglês e um canal pode ter mais de uma,
 * separadas por ";" (ex.: "Entertainment;News"). Aqui traduzimos cada parte.
 */
object GroupNames {
    const val NONE = "Sem categoria"
    const val SEP = ";"

    private val map = mapOf(
        "animation" to "Animação",
        "auto" to "Automotivo",
        "business" to "Negócios",
        "classic" to "Clássicos",
        "comedy" to "Comédia",
        "cooking" to "Culinária",
        "culture" to "Cultura",
        "documentary" to "Documentários",
        "education" to "Educação",
        "entertainment" to "Entretenimento",
        "family" to "Família",
        "general" to "Geral",
        "interactive" to "Interativo",
        "kids" to "Infantil",
        "legislative" to "Legislativo",
        "lifestyle" to "Estilo de vida",
        "movies" to "Filmes",
        "music" to "Música",
        "news" to "Notícias",
        "outdoor" to "Ao ar livre",
        "public" to "Públicos",
        "relax" to "Relax",
        "religious" to "Religião",
        "science" to "Ciência",
        "series" to "Séries",
        "shop" to "Compras",
        "sports" to "Esportes",
        "travel" to "Viagens",
        "weather" to "Clima",
        "undefined" to NONE,
        "sem grupo" to NONE
    )

    /** Recebe o group-title original e devolve as categorias traduzidas, unidas por ";". */
    fun translate(raw: String): String {
        val parts = raw.split(SEP)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { map[it.lowercase()] ?: it }
            .distinct()
            .toMutableList()
        if (parts.size > 1) parts.remove(NONE)
        return if (parts.isEmpty()) NONE else parts.joinToString(SEP)
    }

    fun split(group: String): List<String> = group.split(SEP)

    /** Texto para mostrar na tela. */
    fun display(group: String): String = group.replace(SEP, " • ")
}
