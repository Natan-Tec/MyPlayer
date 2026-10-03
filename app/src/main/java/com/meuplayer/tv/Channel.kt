package com.meuplayer.tv

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String
)

const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

/** Chave (no SharedPreferences "m3u") do endereço do último canal assistido. */
const val PREF_LAST_URL = "last_url"

/** Guarda a lista atual e o canal escolhido para o player poder trocar de canal. */
object PlayerState {
    var channels: List<Channel> = emptyList()
    var index: Int = 0
}
