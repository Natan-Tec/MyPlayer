package com.meuplayer.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Tela inicial: TV ao vivo, adicionar lista e configurações. */
class HomeActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var btnLive: View
    private lateinit var btnAdd: View
    private lateinit var btnSettings: View
    private var firstFocusDone = false

    // Escolher o arquivo .m3u no aparelho (seletor de arquivos do Android).
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLocal(uri)
    }

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
        setupTile(btnAdd, R.drawable.ic_add, "ADICIONAR LISTA") { addList() }
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
            toast("Primeiro adicione uma lista (link ou arquivo .m3u).")
            addList()
        } else {
            startActivity(Intent(this, ChannelsActivity::class.java))
        }
    }

    private fun addList() {
        ListDialogs.add(this, onPickFile = { launchPicker() }) {
            startActivity(Intent(this, ChannelsActivity::class.java))
        }
    }

    private fun launchPicker() {
        try {
            // Os tipos de .m3u variam de aparelho para aparelho, então aceita qualquer arquivo.
            pickFile.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            toast("Este aparelho não tem um seletor de arquivos.")
        }
    }

    private fun importLocal(uri: Uri) {
        val app = applicationContext
        toast("Lendo o arquivo…")
        executor.execute {
            try {
                val name = displayName(uri)
                val p = PlaylistStore.addLocal(app, uri, name)
                runOnUiThread {
                    if (!isFinishing) {
                        toast("Lista adicionada: ${p.name}")
                        startActivity(Intent(this, ChannelsActivity::class.java))
                    }
                }
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                runOnUiThread { if (!isFinishing) toast("Não foi possível usar o arquivo: $why") }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
            // Sem nome: usa o final do endereço.
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "lista.m3u"
    }
}
