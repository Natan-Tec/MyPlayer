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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var overlay: View
    private lateinit var title: TextView
    private lateinit var info: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    private var index = 0
    private var triedHls = false

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

        playerView.setOnClickListener { showOverlay() }
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
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                loading.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                val ch = PlayerState.channels[index]
                // Alguns links não têm ".m3u8" na URL: tenta uma vez como HLS.
                if (!triedHls && !ch.url.contains(".m3u8", ignoreCase = true)) {
                    triedHls = true
                    player?.apply {
                        setMediaItem(
                            MediaItem.Builder()
                                .setUri(ch.url)
                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                .build()
                        )
                        prepare()
                        playWhenReady = true
                    }
                    return
                }
                loading.visibility = View.GONE
                info.text = "Não foi possível reproduzir este canal (${error.errorCodeName})"
                showOverlay(persist = true)
            }
        })

        playerView.player = p
        player = p
        play()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(hideOverlay)
        playerView.player = null
        player?.release()
        player = null
    }

    private fun play() {
        val ch = PlayerState.channels[index]
        PlayerState.index = index
        triedHls = false
        title.text = ch.name
        info.text = ""
        player?.apply {
            setMediaItem(MediaItem.fromUri(ch.url))
            prepare()
            playWhenReady = true
        }
        showOverlay()
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
                showOverlay()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
