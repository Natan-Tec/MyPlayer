package com.meuplayer.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity

/** Abertura do app: fundo azul e logo. Passa sozinha para a tela inicial. */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private val goHome = Runnable { next() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        immersive()
        bindBackdrop(R.id.backdrop)
        PlaylistStore.migrateLegacy(this)
        handler.postDelayed(goHome, SPLASH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goHome)
        super.onDestroy()
    }

    private fun next() {
        if (done) return
        done = true
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    // Qualquer toque ou tecla pula a abertura.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        next()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) next()
        return true
    }

    private companion object {
        const val SPLASH_MS = 1800L
    }
}
