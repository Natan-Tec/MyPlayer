package com.meuplayer.tv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Configurações: listas, armazenamento, atualização e ajustes de aparência (para teste). */
class SettingsActivity : AppCompatActivity() {

    private class Row(val box: LinearLayout, val title: TextView, val sub: TextView, val value: TextView)

    private class Slider(
        val title: String,
        val min: Int,
        val max: Int,
        val step: Int,
        val get: (Look) -> Int,
        val set: (Look, Int) -> Look
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var content: LinearLayout
    private var storageSub: TextView? = null
    private var updateSub: TextView? = null
    private var summaryView: TextView? = null
    private var busyUpdating = false

    private val sliders = listOf(
        Slider("Brilho do foco", 0, 100, 10, { it.glow }, { l, v -> l.copy(glow = v) }),
        Slider("Tamanho do foco (%)", 0, 20, 2, { it.scale }, { l, v -> l.copy(scale = v) }),
        Slider("Velocidade da animação (ms)", 80, 400, 20, { it.anim }, { l, v -> l.copy(anim = v) }),
        Slider("Transparência do vidro", 0, 100, 10, { it.glass }, { l, v -> l.copy(glass = v) }),
        Slider("Borda do vidro", 0, 100, 10, { it.border }, { l, v -> l.copy(border = v) }),
        Slider("Colunas da grade de canais", 2, 5, 1, { it.columns }, { l, v -> l.copy(columns = v) })
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        immersive()
        bindBackdrop(R.id.backdrop)

        findViewById<View>(R.id.navHome).setOnClickListener {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
        findViewById<View>(R.id.navSettings).visibility = View.GONE

        content = findViewById(R.id.content)
        render()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---------- Montagem da tela ----------

    private fun render() {
        content.removeAllViews()
        storageSub = null
        updateSub = null
        summaryView = null

        addText("Configurações", 20f, bold = true)

        section("LISTAS")
        val lists = PlaylistStore.all(this)
        val active = PlaylistStore.active(this)
        if (lists.isEmpty()) {
            addNote("Nenhuma lista adicionada. Use ADICIONAR LISTA na tela inicial.")
        } else {
            for (p in lists) {
                val isActive = p.id == active?.id
                row(p.name, (if (isActive) "Lista ativa  •  " else "") + p.where) { listActions(p, isActive) }
            }
        }

        section("ARMAZENAMENTO")
        storageSub = row("Uso do app", "Calculando…").sub
        row("Limpar cache", "Apaga as capas baixadas, as listas salvas e os arquivos de atualização") { clearCache() }
        refreshStorage()

        section("REPRODUÇÃO")
        val prefs = getSharedPreferences("m3u", MODE_PRIVATE)
        fun sslLabel() = if (prefs.getBoolean(PREF_INSECURE_SSL, false)) "Ligado" else "Desligado"
        lateinit var sslRow: Row
        sslRow = row(
            "Aceitar certificados inválidos",
            "Como última tentativa, o canal abre mesmo com certificado HTTPS vencido ou inválido. Menos seguro: ligue só se canais https falham.",
            sslLabel()
        ) {
            prefs.edit().putBoolean(PREF_INSECURE_SSL, !prefs.getBoolean(PREF_INSECURE_SSL, false)).apply()
            sslRow.value.text = sslLabel()
        }

        section("ATUALIZAÇÃO")
        row("Versão instalada", "M3UFlow ${Updater.installedVersion(this)}")
        updateSub = row("Verificar atualizações", "Procura uma versão nova no GitHub") { checkUpdate() }.sub

        section("APARÊNCIA (TESTE)")
        addNote("Use ◀ ▶ no controle para mudar o valor. O efeito aparece na hora.")
        for (s in sliders) addSlider(s)
        summaryView = addNote(LookStore.summary())
        row("Restaurar padrão", "Volta todos os ajustes de aparência ao valor original") {
            LookStore.reset(this)
            render()
        }

        content.post {
            // O foco começa no primeiro item que pode ser selecionado.
            (0 until content.childCount).map { content.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
        }
    }

    // ---------- Peças da tela ----------

    private fun addText(text: String, size: Float, bold: Boolean, color: Int = Color.WHITE): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = if (bold) Fonts.bold(context) else Fonts.regular(context)
            includeFontPadding = false
        }
        content.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return tv
    }

    private fun section(title: String) {
        val tv = addText(title, 10f, bold = true, color = ContextCompat.getColor(this, R.color.text_dim))
        tv.letterSpacing = 0.12f
        (tv.layoutParams as LinearLayout.LayoutParams).topMargin = dpi(22)
    }

    private fun addNote(text: String): TextView {
        val tv = addText(text, 11f, bold = false, color = ContextCompat.getColor(this, R.color.text_dim))
        tv.setPadding(dpi(14), dpi(6), dpi(14), dpi(2))
        return tv
    }

    private fun row(title: String, sub: String? = null, value: String? = null, onClick: (() -> Unit)? = null): Row {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpi(14), dpi(10), dpi(14), dpi(10))
            background = ContextCompat.getDrawable(context, R.drawable.category_bg)
            isFocusable = onClick != null
            isClickable = onClick != null
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleView = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Fonts.bold(context)
            includeFontPadding = false
        }
        val subView = TextView(this).apply {
            text = sub.orEmpty()
            textSize = 10.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            typeface = Fonts.regular(context)
            includeFontPadding = false
            setPadding(0, dpi(3), 0, 0)
            visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        val valueView = TextView(this).apply {
            text = value.orEmpty()
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Fonts.bold(context)
            includeFontPadding = false
            visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        texts.addView(titleView)
        texts.addView(subView)
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(valueView)

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dpi(4)
        content.addView(box, lp)

        if (onClick != null) {
            box.setOnClickListener { onClick() }
            FocusFx.attach(box, null, 0.3f)
        }
        return Row(box, titleView, subView, valueView)
    }

    // ---------- Listas ----------

    private fun listActions(p: Playlist, isActive: Boolean) {
        val d = GlassDialog(this, p.name).message(p.where)
        if (!isActive) {
            d.option("Usar esta lista", "É a lista que abre em TV AO VIVO") {
                PlaylistStore.setActive(this, p.id)
                toast("Lista ativa: ${p.name}")
                render()
            }
        }
        d.option("Atualizar agora", if (p.isLocal) "Lê o arquivo guardado de novo" else "Baixa a lista de novo") { refreshList(p) }
        d.option("Editar", if (p.isLocal) "Mudar o nome" else "Mudar o nome ou o link") { ListDialogs.edit(this, p) { render() } }
        d.option("Apagar", "Remove a lista do app") { confirmDelete(p) }
        d.button("Fechar")
        d.show()
    }

    private fun confirmDelete(p: Playlist) {
        GlassDialog(this, "Apagar lista?")
            .message("\"${p.name}\" será removida do app. Isso não apaga nada na internet.")
            .button("Cancelar")
            .button("Apagar") {
                PlaylistStore.remove(this, p.id)
                render()
            }
            .show()
    }

    private fun refreshList(p: Playlist) {
        toast("Atualizando a lista…")
        val app = applicationContext
        executor.execute {
            try {
                val channels = PlaylistRepo.download(app, p)
                ui.post {
                    if (!isFinishing) {
                        toast("Lista atualizada: ${channels.size} canais")
                        refreshStorage()
                    }
                }
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                ui.post { if (!isFinishing) toast("Não foi possível atualizar: $why") }
            }
        }
    }

    // ---------- Armazenamento ----------

    private fun refreshStorage() {
        val app = applicationContext
        executor.execute {
            val lists = size(File(app.cacheDir, "lists"))
            val covers = size(File(app.cacheDir, "logos")) + size(File(app.filesDir, "logo_index.tsv"))
            val updates = size(File(app.cacheDir, "updates"))
            val total = size(app.cacheDir) + size(app.filesDir)
            val text = "Total ${fmt(total)}  •  Listas ${fmt(lists)}  •  Capas ${fmt(covers)}  •  Atualizações ${fmt(updates)}"
            ui.post { if (!isFinishing) storageSub?.text = text }
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun clearCache() {
        val app = applicationContext
        executor.execute {
            try {
                app.imageLoader.diskCache?.clear()
                app.imageLoader.memoryCache?.clear()
            } catch (e: Exception) {
                // Se a limpeza das capas falhar, o resto continua.
            }
            File(app.cacheDir, "lists").deleteRecursively()
            File(app.cacheDir, "updates").deleteRecursively()
            File(app.cacheDir, "logos.json").delete()
            ui.post {
                if (!isFinishing) {
                    toast("Cache limpo")
                    refreshStorage()
                }
            }
        }
    }

    private fun size(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { size(it) } ?: 0L
    }

    private fun fmt(bytes: Long): String {
        val loc = Locale("pt", "BR")
        return when {
            bytes >= 1024L * 1024 -> String.format(loc, "%.1f MB", bytes / 1048576.0)
            bytes >= 1024L -> String.format(loc, "%d KB", bytes / 1024)
            else -> "0 KB"
        }
    }

    // ---------- Atualização do app ----------

    private fun checkUpdate() {
        if (busyUpdating) return
        updateSub?.text = "Verificando…"
        executor.execute {
            try {
                val rel = Updater.fetchLatest()
                ui.post { if (!isFinishing) onRelease(rel) }
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                ui.post { if (!isFinishing) updateSub?.text = "Não foi possível verificar ($why). Confira a internet." }
            }
        }
    }

    private fun onRelease(rel: ReleaseInfo?) {
        val installed = Updater.installedVersion(this)
        if (rel == null || !Updater.isNewer(rel.version, installed)) {
            updateSub?.text = "Você está na versão mais recente ($installed)."
            return
        }
        updateSub?.text = "Nova versão disponível: ${rel.version}"
        val mb = if (rel.size > 0) String.format(Locale("pt", "BR"), " (%.1f MB)", rel.size / 1048576.0) else ""
        val notes = rel.notes.trim().take(400)
        val d = GlassDialog(this, "Nova versão ${rel.version}")
            .message("Você está na $installed.$mb" + if (notes.isNotEmpty()) "\n\n$notes" else "")
        d.button("Agora não")
        d.button("Atualizar", dismiss = true) { startUpdate(rel) }
        d.show()
    }

    private fun startUpdate(rel: ReleaseInfo) {
        if (busyUpdating) return
        if (rel.apkUrl == null) {
            updateSub?.text = "Esta versão não tem arquivo para instalar."
            return
        }
        busyUpdating = true
        updateSub?.text = "Baixando… 0%"
        val app = applicationContext
        executor.execute {
            try {
                val apk = Updater.download(app, rel) { percent ->
                    ui.post { if (!isFinishing) updateSub?.text = "Baixando… $percent%" }
                }
                ui.post {
                    busyUpdating = false
                    if (isFinishing) return@post
                    when (Updater.install(this, apk)) {
                        InstallResult.STARTED -> updateSub?.text = "Confirme a instalação na tela do Android."
                        InstallResult.NEEDS_PERMISSION -> {
                            updateSub?.text = "Permita que o M3UFlow instale apps e depois toque em Verificar atualizações de novo."
                            toast("Ative \"Permitir desta fonte\" para o M3UFlow.")
                        }
                        InstallResult.FAILED -> updateSub?.text = "Não foi possível abrir o instalador."
                    }
                }
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                ui.post {
                    busyUpdating = false
                    if (!isFinishing) updateSub?.text = "Falha ao baixar: $why"
                }
            }
        }
    }

    // ---------- Aparência (teste) ----------

    private fun addSlider(s: Slider) {
        val r = row(s.title, null, valueText(s, LookStore.current))
        r.box.isFocusable = true
        r.box.isClickable = true
        FocusFx.attach(r.box, null, 0.3f)

        fun setLevel(v: Int) {
            val next = s.set(LookStore.current, v.coerceIn(s.min, s.max))
            LookStore.save(this, next)
            r.value.text = valueText(s, next)
            summaryView?.text = LookStore.summary(next)
            invalidateGlass(findViewById(android.R.id.content))
        }

        r.box.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                val dir = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                setLevel(s.get(LookStore.current) + dir * s.step)
                true
            } else {
                false
            }
        }
        // Toque ou OK: aumenta o valor e, ao chegar no máximo, volta ao mínimo.
        r.box.setOnClickListener {
            val cur = s.get(LookStore.current)
            setLevel(if (cur + s.step > s.max) s.min else cur + s.step)
        }
    }

    private fun valueText(s: Slider, look: Look): String = "◀  ${s.get(look)}  ▶"
}
