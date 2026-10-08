package com.meuplayer.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/** Seleção de canais: categorias à esquerda e grade de canais. */
class ChannelsActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val logoExecutor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var logos: LogoResolver
    private lateinit var adapter: ChannelAdapter
    private lateinit var catAdapter: CategoryAdapter
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var progress: View
    private lateinit var lastView: TextView
    private lateinit var searchText: TextView

    private var all: List<Channel> = emptyList()
    private var filtered: List<Channel> = emptyList()
    private var groups: List<String> = emptyList()
    private var counts: Map<String, Int> = emptyMap()
    private var currentGroup: String? = null // null = todas as categorias
    private var query: String = ""
    private var suggestion: Channel? = null
    private var focusUrl: String? = null
    private var firstFocusDone = false

    private val prefs by lazy { getSharedPreferences("m3u", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channels)
        immersive()
        bindBackdrop(R.id.backdrop)

        findViewById<View>(R.id.navHome).setOnClickListener { goHome() }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        status = findViewById(R.id.status)
        message = findViewById(R.id.message)
        progress = findViewById(R.id.progress)
        lastView = findViewById(R.id.lastChannel)
        searchText = findViewById(R.id.searchText)

        logos = LogoResolver(applicationContext)

        val columns = LookStore.current.columns.coerceIn(2, 5)
        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, columns)
        grid.itemAnimator = null
        adapter = ChannelAdapter(this, logos) { ch -> openPlayer(ch) }
        grid.adapter = adapter

        val cats = findViewById<RecyclerView>(R.id.categories)
        cats.layoutManager = LinearLayoutManager(this)
        cats.itemAnimator = null
        catAdapter = CategoryAdapter { key -> pickGroup(key) }
        cats.adapter = catAdapter

        findViewById<View>(R.id.searchBtn).setOnClickListener { askSearch() }
        lastView.setOnClickListener { suggestion?.let { openSuggested(it) } }

        currentGroup = prefs.getString("group", null)
        loadLogoIndex()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (all.isNotEmpty()) {
            updateSuggestion()
            adapter.refreshBadges()
            restoreFocus()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        logoExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun goHome() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    /** Prepara a busca automática de logos em segundo plano. */
    private fun loadLogoIndex() {
        logoExecutor.execute {
            if (logos.load()) ui.post { if (!isFinishing) adapter.refreshLogos() }
        }
    }

    // ---------- Carregar a lista ativa ----------

    private fun load() {
        val pl = PlaylistStore.active(this)
        if (pl == null) {
            showMessage("Nenhuma lista adicionada.\nVolte à tela inicial e escolha ADICIONAR LISTA.")
            return
        }
        showMessage(null)
        progress.visibility = View.VISIBLE
        status.text = pl.name

        val app = applicationContext
        executor.execute {
            val cached = PlaylistRepo.cached(app, pl)
            if (cached != null) ui.post { if (!isFinishing) showChannels(cached) }

            if (cached == null || PlaylistRepo.isStale(app, pl)) {
                try {
                    val fresh = PlaylistRepo.download(app, pl)
                    ui.post { if (!isFinishing) showChannels(fresh) }
                } catch (e: Exception) {
                    if (cached == null) {
                        val why = e.message ?: e.javaClass.simpleName
                        ui.post {
                            if (!isFinishing) {
                                showMessage("Não foi possível baixar a lista:\n$why\nConfira a internet e o link em Configurações.")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showChannels(channels: List<Channel>) {
        progress.visibility = View.GONE
        all = channels
        if (channels.isEmpty()) {
            showMessage("Nenhum canal encontrado nesta lista.\nConfira o link em Configurações.")
            return
        }
        showMessage(null)

        val collator = Collator.getInstance(Locale("pt", "BR"))
        val countMap = LinkedHashMap<String, Int>()
        for (ch in channels) {
            for (g in GroupNames.split(ch.group)) countMap[g] = (countMap[g] ?: 0) + 1
        }
        val names = countMap.keys.sortedWith { a, b -> collator.compare(a, b) }
        // "Sem categoria" sempre por último.
        groups = names.filter { it != GroupNames.NONE } + names.filter { it == GroupNames.NONE }
        counts = countMap
        if (currentGroup != null && currentGroup !in groups) currentGroup = null

        catAdapter.submit(
            listOf(CategoryItem(null, "Todas", channels.size)) +
                groups.map { CategoryItem(it, it, counts[it] ?: 0) },
            currentGroup
        )
        applyFilter()
        updateSuggestion()
        focusFirstTime()
    }

    // ---------- Filtro e busca ----------

    private fun applyFilter() {
        val q = query.trim().lowercase()
        filtered = all.filter { ch ->
            (currentGroup == null || GroupNames.split(ch.group).contains(currentGroup)) &&
                (q.isEmpty() || ch.name.lowercase().contains(q))
        }
        adapter.submit(filtered)
        grid.scrollToPosition(0)
        searchText.text = if (q.isEmpty()) "Buscar canal" else query.trim()
        searchText.setTextColor(
            ContextCompat.getColor(this, if (q.isEmpty()) R.color.hint else R.color.text)
        )
        status.text = when {
            q.isNotEmpty() -> "${filtered.size} canais • busca: ${query.trim()}"
            currentGroup != null -> "$currentGroup • ${filtered.size} canais"
            else -> "${filtered.size} canais"
        }
        if (filtered.isEmpty() && all.isNotEmpty()) {
            showMessage("Nenhum canal encontrado.")
        } else if (all.isNotEmpty()) {
            showMessage(null)
        }
    }

    private fun pickGroup(key: String?) {
        currentGroup = key
        prefs.edit().putString("group", key).apply()
        catAdapter.select(key)
        applyFilter()
    }

    private fun askSearch() {
        val d = GlassDialog(this, "Buscar canal:")
        val input = d.field(null, "Nome do canal", query)
        d.button("Cancelar")
        d.button("Limpar") {
            query = ""
            applyFilter()
        }
        d.button("Buscar", dismiss = false) {
            d.dismiss()
            query = input.text.toString()
            applyFilter()
            focusGrid()
        }
        d.show()
    }

    private fun showMessage(text: String?) {
        if (text == null) {
            message.visibility = View.GONE
        } else {
            progress.visibility = View.GONE
            message.text = text
            message.visibility = View.VISIBLE
        }
    }

    // ---------- Foco do controle remoto ----------

    private fun focusFirstTime() {
        if (firstFocusDone) return
        firstFocusDone = true
        if (suggestion != null) lastView.requestFocus() else focusGrid()
    }

    private fun focusGrid() {
        grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    /** Ao voltar do player, devolve o foco ao canal que estava selecionado. */
    private fun restoreFocus() {
        val url = focusUrl ?: return
        val pos = adapter.positionOf(url)
        if (pos < 0) return
        grid.scrollToPosition(pos)
        grid.post { grid.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    // ---------- Player ----------

    private fun openPlayer(ch: Channel) {
        val index = filtered.indexOfFirst { it.url == ch.url }
        if (index < 0) return
        focusUrl = ch.url
        PlayerState.channels = filtered
        PlayerState.index = index
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ---------- Continuar assistindo ----------

    /** Mostra o último canal assistido acima da grade, em qualquer categoria. */
    private fun updateSuggestion() {
        val url = prefs.getString(PREF_LAST_URL, null)
        val ch = if (url == null) null else all.firstOrNull { it.url == url }
        suggestion = ch
        if (ch == null) {
            lastView.visibility = View.GONE
        } else {
            lastView.text = "▶  Continuar assistindo: ${ch.displayName}"
            lastView.visibility = View.VISIBLE
        }
    }

    private fun openSuggested(ch: Channel) {
        focusUrl = null
        val inFiltered = filtered.indexOfFirst { it.url == ch.url }
        if (inFiltered >= 0) {
            PlayerState.channels = filtered
            PlayerState.index = inFiltered
        } else {
            // O canal não está na categoria aberta: o zapping passa por todos os canais.
            PlayerState.channels = all
            PlayerState.index = all.indexOfFirst { it.url == ch.url }.coerceAtLeast(0)
        }
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
