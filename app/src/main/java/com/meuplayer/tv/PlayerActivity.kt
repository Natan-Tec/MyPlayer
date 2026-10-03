package com.meuplayer.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    /** Uma forma de tentar abrir o canal: endereço, identidade (User-Agent) e cabeçalhos. */
    private data class Attempt(
        val url: String,
        val userAgent: String,
        val headers: Map<String, String>,
        val forceHls: Boolean
    )

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var overlay: View
    private lateinit var title: TextView
    private lateinit var info: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val restartRunnable = Runnable { startAttempt() }
    private val stallRunnable = Runnable { onStalled() }

    private var index = 0
    private var attempts: List<Attempt> = emptyList()
    private var attemptIndex = 0
    private var netRetries = 0
    private var failed = false

    private val okClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (PlayerState.channels.isEmpty()) {
            finish()
            return
        }
        index = PlayerState.index.coerceIn(0, PlayerState.channels.size - 1)

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player)
        playerView.useController = false
        loading = findViewById(R.id.loading)
        overlay = findViewById(R.id.overlay)
        title = findViewById(R.id.title)
        info = findViewById(R.id.info)

        playerView.setOnClickListener {
            if (failed) play() else showOverlay()
        }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { change(-1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { change(1) }

        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (PlayerState.channels.isNotEmpty()) initPlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun initPlayer() {
        // Buffer um pouco maior: menos travadas em links instáveis.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 2_500, 5_000)
            .build()

        // Se o decodificador preferido falhar, tenta outro automaticamente.
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                loading.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    handler.removeCallbacks(stallRunnable)
                    netRetries = 0
                    failed = false
                    info.text = ""
                    showOverlay()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })

        playerView.player = p
        player = p
        play()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(stallRunnable)
        playerView.player = null
        player?.release()
        player = null
    }

    private fun play() {
        val ch = PlayerState.channels[index]
        PlayerState.index = index
        getSharedPreferences("m3u", MODE_PRIVATE).edit()
            .putString(PREF_LAST_URL, ch.url)
            .apply()
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(stallRunnable)
        attempts = buildAttempts(ch)
        attemptIndex = 0
        netRetries = 0
        failed = false
        title.text = ch.name
        info.text = ""
        showOverlay()
        startAttempt()
    }

    /** Monta a lista de formas de abrir o canal, da mais provável para a menos provável. */
    private fun buildAttempts(ch: Channel): List<Attempt> {
        val list = ArrayList<Attempt>()
        fun add(a: Attempt) { if (a !in list) list.add(a) }

        val playlistUa = ch.headers["User-Agent"]
        val baseHeaders = ch.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        val primaryUa = playlistUa ?: USER_AGENT
        val hasM3u8 = ch.url.contains(".m3u8", ignoreCase = true)
        val referer = originOf(ch.url)?.let { mapOf("Referer" to it) }.orEmpty()
        val withReferer = referer + baseHeaders // o Referer da lista, se houver, tem prioridade

        // 1) Do jeito que a lista pede (ou como o VLC faria).
        add(Attempt(ch.url, primaryUa, baseHeaders, false))
        // 2) Mesmo endereço, forçando HLS (links sem ".m3u8" na URL).
        if (!hasM3u8) add(Attempt(ch.url, primaryUa, baseHeaders, true))
        // 3) Outras identidades, com Referer do próprio servidor.
        for (ua in ALT_USER_AGENTS) {
            add(Attempt(ch.url, ua, withReferer, false))
            if (!hasM3u8 && ua == ALT_USER_AGENTS.first()) add(Attempt(ch.url, ua, withReferer, true))
        }
        // 4) Trocar http <-> https.
        swapScheme(ch.url)?.let { alt ->
            add(Attempt(alt, primaryUa, baseHeaders, false))
            if (!hasM3u8) add(Attempt(alt, primaryUa, baseHeaders, true))
        }
        return list
    }

    private fun startAttempt() {
        val p = player ?: return
        val a = attempts.getOrNull(attemptIndex) ?: return

        val dataSource = OkHttpDataSource.Factory(okClient)
            .setUserAgent(a.userAgent)
            .setDefaultRequestProperties(a.headers)

        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )

        val item = MediaItem.Builder()
            .setUri(a.url)
            .apply { if (a.forceHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()

        p.setMediaSource(DefaultMediaSourceFactory(dataSource, extractors).createMediaSource(item))
        p.prepare()
        p.playWhenReady = true

        // Se conectar mas nunca chegar vídeo, desiste desta tentativa.
        handler.removeCallbacks(stallRunnable)
        handler.postDelayed(stallRunnable, STALL_TIMEOUT_MS)
    }

    private fun handleError(error: PlaybackException) {
        handler.removeCallbacks(stallRunnable)
        val p = player ?: return

        // Janela ao vivo ficou para trás: basta voltar ao ponto ao vivo.
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }

        val status = httpStatus(error)

        // Rede instável: repete a mesma tentativa algumas vezes antes de trocar de método.
        val networkGlitch =
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (networkGlitch && netRetries < MAX_NET_RETRIES) {
            netRetries++
            scheduleRestart(1_500)
            return
        }

        // Erros de acesso/leitura/formato: trocar de método pode resolver. Erros de decodificação não.
        val retryable = status != null || error.errorCode in 2000..3999
        if (retryable && attemptIndex + 1 < attempts.size) {
            advance(if (status == 429 || status == 509) 1_500 else 0)
            return
        }

        showFailure(describeError(error, status))
    }

    private fun onStalled() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_READY) return
        if (attemptIndex + 1 < attempts.size) {
            advance(0)
        } else {
            showFailure("O canal não respondeu a tempo")
        }
    }

    private fun advance(delayMs: Long) {
        attemptIndex++
        netRetries = 0
        scheduleRestart(delayMs)
    }

    private fun scheduleRestart(delayMs: Long) {
        loading.visibility = View.VISIBLE
        info.text = "Tentando outro método (${attemptIndex + 1}/${attempts.size})…"
        showOverlay(persist = true)
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun showFailure(message: String) {
        failed = true
        loading.visibility = View.GONE
        info.text = "$message\nPressione OK para tentar de novo ou troque de canal."
        showOverlay(persist = true)
    }

    private fun httpStatus(t: Throwable?): Int? {
        var c = t
        while (c != null) {
            if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode
            c = c.cause
        }
        return null
    }

    private fun describeError(error: PlaybackException, status: Int?): String = when {
        status == 401 || status == 403 -> "Servidor recusou o acesso (HTTP $status)"
        status == 404 || status == 410 -> "Este link não existe mais (HTTP $status)"
        status == 429 || status == 509 -> "Limite de conexões do servidor atingido (HTTP $status)"
        status != null && status >= 500 -> "Servidor do canal com problema (HTTP $status)"
        status != null -> "Servidor respondeu com erro (HTTP $status)"
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Não foi possível conectar ao servidor do canal"
        else -> "Não foi possível reproduzir este canal (${error.errorCodeName})"
    }

    private fun originOf(url: String): String? = try {
        val u = URI(url)
        if (u.scheme == null || u.host == null) null
        else buildString {
            append(u.scheme).append("://").append(u.host)
            if (u.port != -1) append(':').append(u.port)
            append('/')
        }
    } catch (e: Exception) {
        null
    }

    private fun swapScheme(url: String): String? = when {
        url.startsWith("https://", ignoreCase = true) -> "http://" + url.substring(8)
        url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring(7)
        else -> null
    }

    private fun change(delta: Int) {
        val size = PlayerState.channels.size
        index = (index + delta + size) % size
        play()
    }

    private fun showOverlay(persist: Boolean = false) {
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        if (!persist) handler.postDelayed(hideOverlay, 4000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.repeatCount == 0) change(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.repeatCount == 0) change(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (failed) play() else showOverlay()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val MAX_NET_RETRIES = 2
        const val STALL_TIMEOUT_MS = 25_000L
        val ALT_USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36",
            "IPTVSmartersPro",
            "Lavf/60.16.100"
        )
    }
}
