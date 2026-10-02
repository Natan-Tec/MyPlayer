package com.meuplayer.tv

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String
)

const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

/** Guarda a lista atual e o canal escolhido para o player poder trocar de canal. */
object PlayerState {
    var channels: List<Channel> = emptyList()
    var index: Int = 0
}
