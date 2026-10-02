package com.meuplayer.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val logoExecutor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var logos: LogoResolver
    private lateinit var adapter: ChannelAdapter
    private lateinit var status: TextView
    private lateinit var btnGroup: Button

    private var all: List<Channel> = emptyList()
    private var filtered: List<Channel> = emptyList()
    private var groups: List<String> = emptyList()
    private var currentGroup: String? = null // null = todos os grupos
    private var query: String = ""

    private val prefs by lazy { getSharedPreferences("m3u", MODE_PRIVATE) }
    private val cacheFile by lazy { File(cacheDir, "playlist.m3u") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        btnGroup = findViewById(R.id.btnGroup)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        logos = LogoResolver(applicationContext)
        adapter = ChannelAdapter(logos) { position -> openPlayer(position) }
        list.adapter = adapter

        findViewById<Button>(R.id.btnUrl).setOnClickListener { askUrl() }
        btnGroup.setOnClickListener { pickGroup() }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { askSearch() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { download() }

        val url = prefs.getString("url", "").orEmpty()
        when {
            url.isBlank() -> {
                status.text = "Toque em \"Lista\" e cole o link do seu .m3u"
                askUrl()
            }
            cacheFile.exists() -> loadFromCache()
            else -> download()
        }

        loadLogoIndex()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        logoExecutor.shutdownNow()
    }

    /** Prepara a busca automática de logos em segundo plano. */
    private fun loadLogoIndex() {
        logoExecutor.execute {
            if (logos.load()) ui.post { adapter.refreshLogos() }
        }
    }

    // ---------- Carregar lista ----------

    private fun loadFromCache() {
        status.text = "Abrindo lista salva..."
        executor.execute {
            try {
                val channels = cacheFile.inputStream().use { M3uParser.parse(it) }
                ui.post { showChannels(channels) }
            } catch (e: Exception) {
                ui.post { download() }
            }
        }
    }

    private fun download() {
        val url = prefs.getString("url", "").orEmpty()
        if (url.isBlank()) {
            askUrl()
            return
        }
        status.text = "Baixando lista..."
        executor.execute {
            try {
                fetch(url, cacheFile)
                val channels = cacheFile.inputStream().use { M3uParser.parse(it) }
                ui.post { showChannels(channels) }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                ui.post { status.text = "Erro ao baixar: $msg" }
            }
        }
    }

    /** Baixa o arquivo seguindo redirecionamentos (inclusive http -> https). */
    private fun fetch(startUrl: String, dest: File) {
        var current = startUrl
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) throw IOException("Redirecionamento inválido")
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            if (code != 200) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            return
        }
        throw IOException("Muitos redirecionamentos")
    }

    private fun showChannels(channels: List<Channel>) {
        all = channels
        groups = channels.map { it.group }.distinct()
        currentGroup = prefs.getString("group", null)?.takeIf { it in groups }
        query = ""
        applyFilter()
    }

    // ---------- Filtro e busca ----------

    private fun applyFilter() {
        val q = query.trim().lowercase()
        filtered = all.filter { ch ->
            (currentGroup == null || ch.group == currentGroup) &&
                (q.isEmpty() || ch.name.lowercase().contains(q))
        }
        adapter.submit(filtered)
        btnGroup.text = "Grupo: ${currentGroup ?: "Todos"}"
        status.text = when {
            all.isEmpty() -> "Nenhum canal encontrado. Confira o link em \"Lista\"."
            q.isNotEmpty() -> "${filtered.size} canais • busca: ${query.trim()}"
            else -> "${filtered.size} canais"
        }
    }

    private fun pickGroup() {
        if (groups.isEmpty()) return
        val items = (listOf("Todos") + groups).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Grupo")
            .setItems(items) { _, which ->
                currentGroup = if (which == 0) null else groups[which - 1]
                prefs.edit().putString("group", currentGroup).apply()
                applyFilter()
            }
            .show()
    }

    private fun askUrl() {
        val input = EditText(this).apply {
            hint = "http://.../index.m3u"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(prefs.getString("url", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Link da lista (.m3u)")
            .setView(input)
            .setPositiveButton("Carregar") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    prefs.edit().putString("url", url).remove("group").apply()
                    cacheFile.delete()
                    download()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun askSearch() {
        val input = EditText(this).apply {
            hint = "Nome do canal"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(query)
        }
        AlertDialog.Builder(this)
            .setTitle("Buscar canal")
            .setView(input)
            .setPositiveButton("Buscar") { _, _ ->
                query = input.text.toString()
                applyFilter()
            }
            .setNeutralButton("Limpar") { _, _ ->
                query = ""
                applyFilter()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Player ----------

    private fun openPlayer(position: Int) {
        PlayerState.channels = filtered
        PlayerState.index = position
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
