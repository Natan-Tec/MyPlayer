package com.meuplayer.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Tela inicial: TV ao vivo, adicionar lista e configurações. */
class HomeActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var btnLive: View
    private lateinit var btnAdd: View
    private lateinit var btnSettings: View
    private var firstFocusDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        immersive()
        bindBackdrop(R.id.backdrop)

        // Na tela inicial a barra de cima só mostra o logo e a hora.
        findViewById<View>(R.id.navHome).visibility = View.GONE
        findViewById<View>(R.id.navSettings).visibility = View.GONE

        btnLive = findViewById(R.id.btnLive)
        btnAdd = findViewById(R.id.btnAdd)
        btnSettings = findViewById(R.id.btnSettings)

        setupTile(btnLive, R.drawable.ic_live_tv, "TV AO VIVO") { openLive() }
        setupTile(btnAdd, R.drawable.ic_add, "ADICIONAR LISTA") {
            ListDialogs.add(this) { startActivity(Intent(this, ChannelsActivity::class.java)) }
        }
        setupTile(btnSettings, R.drawable.ic_settings, "CONFIGURAÇÕES") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!firstFocusDone) {
            firstFocusDone = true
            btnLive.requestFocus()
        }
        showUpdateBadge()
        // Confere se saiu versão nova (no máximo a cada 12 horas), sem atrapalhar a tela.
        executor.execute {
            Updater.checkQuietly(applicationContext)
            runOnUiThread { if (!isFinishing) showUpdateBadge() }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun setupTile(tile: View, icon: Int, label: String, onClick: () -> Unit) {
        tile.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
        tile.findViewById<TextView>(R.id.tileLabel).text = label
        FocusFx.attach(tile, tile.findViewById(R.id.glow))
        tile.setOnClickListener { onClick() }
    }

    private fun showUpdateBadge() {
        val badge = btnSettings.findViewById<View>(R.id.tileBadge)
        badge.visibility = if (Updater.knownNewerVersion(this) != null) View.VISIBLE else View.GONE
    }

    private fun openLive() {
        if (PlaylistStore.all(this).isEmpty()) {
            toast("Primeiro adicione uma lista (link .m3u).")
            ListDialogs.add(this) { startActivity(Intent(this, ChannelsActivity::class.java)) }
        } else {
            startActivity(Intent(this, ChannelsActivity::class.java))
        }
    }
}
