package com.meuplayer.tv

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat

/** Janela de diálogo no mesmo estilo de vidro das telas. */
class GlassDialog(private val activity: Activity, title: String) {

    private val dialog = AppCompatDialog(activity, R.style.GlassDialog)
    private val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_glass, null)
    private val messageView: TextView = view.findViewById(R.id.dMessage)
    private val content: LinearLayout = view.findViewById(R.id.dContent)
    private val buttons: LinearLayout = view.findViewById(R.id.dButtons)
    private var firstFocus: View? = null

    init {
        view.findViewById<TextView>(R.id.dTitle).text = title
        dialog.setContentView(view)
    }

    fun message(text: String): GlassDialog {
        messageView.text = text
        messageView.visibility = View.VISIBLE
        return this
    }

    /** Campo de texto. [label] aparece acima dele (opcional). */
    fun field(label: String?, hint: String, initial: String = "", uri: Boolean = false): EditText {
        if (label != null) {
            content.addView(
                text(label, bold = true, size = 11f, color = ContextCompat.getColor(activity, R.color.text_dim)).apply {
                    setPadding(0, activity.dpi(6), 0, activity.dpi(4))
                }
            )
        }
        val et = EditText(activity).apply {
            setHint(hint)
            setText(initial)
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(ContextCompat.getColor(activity, R.color.hint))
            textSize = 14f
            typeface = Fonts.regular(activity)
            background = ContextCompat.getDrawable(activity, R.drawable.input_bg)
            setPadding(activity.dpi(12), activity.dpi(10), activity.dpi(12), activity.dpi(10))
            inputType = if (uri) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelection(text.length)
        }
        content.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (firstFocus == null) firstFocus = et
        return et
    }

    /** Uma opção em lista (para menus de ações). */
    fun option(label: String, sub: String? = null, onClick: () -> Unit): GlassDialog {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.dialog_btn_bg)
            isFocusable = true
            isClickable = true
            setPadding(activity.dpi(14), activity.dpi(10), activity.dpi(14), activity.dpi(10))
        }
        box.addView(text(label, bold = true, size = 13f))
        if (!sub.isNullOrBlank()) {
            box.addView(text(sub, bold = false, size = 11f, color = ContextCompat.getColor(activity, R.color.text_dim)))
        }
        box.setOnClickListener {
            dismiss()
            onClick()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = activity.dpi(6)
        content.addView(box, lp)
        if (firstFocus == null) firstFocus = box
        return this
    }

    /** Botão na linha de baixo. Por padrão fecha a janela depois de tocar. */
    fun button(label: String, dismiss: Boolean = true, onClick: (() -> Unit)? = null): GlassDialog {
        val b = text(label, bold = true, size = 13f).apply {
            background = ContextCompat.getDrawable(activity, R.drawable.dialog_btn_bg)
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setPadding(activity.dpi(18), activity.dpi(10), activity.dpi(18), activity.dpi(10))
            setOnClickListener {
                if (dismiss) dismiss()
                onClick?.invoke()
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = activity.dpi(8)
        buttons.addView(b, lp)
        if (firstFocus == null) firstFocus = b
        return this
    }

    fun show(): GlassDialog {
        if (activity.isFinishing) return this
        dialog.setOnShowListener { firstFocus?.requestFocus() }
        dialog.show()
        dialog.window?.apply {
            setLayout(activity.dpi(460), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return this
    }

    fun dismiss() {
        try {
            dialog.dismiss()
        } catch (e: Exception) {
            // A tela já foi fechada.
        }
    }

    val isShowing: Boolean get() = dialog.isShowing

    private fun text(value: String, bold: Boolean, size: Float, color: Int = Color.WHITE): TextView =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) Fonts.bold(activity) else Fonts.regular(activity)
            includeFontPadding = false
        }
}

/** Janelas usadas em mais de uma tela: adicionar e editar listas. */
object ListDialogs {

    fun isValidUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun clipboardUrl(activity: Activity): String = try {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString()?.trim().orEmpty()
        if (isValidUrl(t)) t else ""
    } catch (e: Exception) {
        ""
    }

    /** Primeiro pergunta de onde vem a lista: link (URL) ou arquivo do aparelho. */
    fun add(activity: Activity, onPickFile: () -> Unit, onDone: (Playlist) -> Unit) {
        GlassDialog(activity, "Adicionar Lista:")
            .option("URL", "Colar o link da lista (http:// ou https://)") { addUrl(activity, onDone) }
            .option("Local", "Escolher um arquivo .m3u guardado neste aparelho") { onPickFile() }
            .button("Cancelar")
            .show()
    }

    private fun addUrl(activity: Activity, onDone: (Playlist) -> Unit) {
        val d = GlassDialog(activity, "Adicionar Lista:")
        val link = d.field(null, "Digite o link aqui", clipboardUrl(activity), uri = true)
        d.button("Cancelar")
        d.button("Salvar", dismiss = false) {
            val url = link.text.toString().trim()
            if (!isValidUrl(url)) {
                activity.toast("Digite um link que comece com http:// ou https://")
            } else {
                d.dismiss()
                val p = PlaylistStore.add(activity, url)
                activity.toast("Lista adicionada: ${p.name}")
                onDone(p)
            }
        }
        d.show()
    }

    fun edit(activity: Activity, p: Playlist, onDone: () -> Unit) {
        val d = GlassDialog(activity, "Editar lista:")
        val name = d.field("Nome", "Nome da lista", p.name)
        // Lista de arquivo local não tem link para editar.
        val link = if (p.isLocal) null else d.field("Link", "http://.../lista.m3u", p.url, uri = true)
        d.button("Cancelar")
        d.button("Salvar", dismiss = false) {
            val url = link?.text?.toString()?.trim() ?: p.url
            if (!p.isLocal && !isValidUrl(url)) {
                activity.toast("Digite um link que comece com http:// ou https://")
            } else {
                d.dismiss()
                PlaylistStore.update(activity, p.id, name.text.toString(), url)
                onDone()
            }
        }
        d.show()
    }
}
