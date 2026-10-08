package com.meuplayer.tv

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Ajustes visuais (para testar na TV e depois fixar os valores escolhidos). */
data class Look(
    val glow: Int = 70,      // intensidade do brilho do foco (0 a 100)
    val scale: Int = 8,      // quanto o item em foco cresce, em % (0 a 20)
    val anim: Int = 180,     // duração da animação, em ms
    val glass: Int = 30,     // opacidade do vidro (0 a 100)
    val border: Int = 40,    // força da borda clara do vidro (0 a 100)
    val columns: Int = 3     // colunas da grade de canais
)

object LookStore {
    @Volatile
    var current: Look = Look()
        private set

    private const val PREFS = "look"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Look()
        current = Look(
            glow = p.getInt("glow", d.glow),
            scale = p.getInt("scale", d.scale),
            anim = p.getInt("anim", d.anim),
            glass = p.getInt("glass", d.glass),
            border = p.getInt("border", d.border),
            columns = p.getInt("columns", d.columns)
        )
    }

    fun save(ctx: Context, look: Look) {
        current = look
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("glow", look.glow)
            .putInt("scale", look.scale)
            .putInt("anim", look.anim)
            .putInt("glass", look.glass)
            .putInt("border", look.border)
            .putInt("columns", look.columns)
            .apply()
    }

    fun reset(ctx: Context) = save(ctx, Look())

    fun summary(look: Look = current): String =
        "Brilho ${look.glow} · Tamanho ${look.scale} · Velocidade ${look.anim} · " +
            "Vidro ${look.glass} · Borda ${look.border} · Colunas ${look.columns}"
}

/** Efeito de foco do controle remoto: cresce um pouco e acende um brilho suave. */
object FocusFx {

    fun attach(view: View, glow: View? = null, boost: Float = 1f) {
        view.setOnFocusChangeListener { v, hasFocus -> apply(v, glow, hasFocus, boost) }
    }

    fun apply(view: View, glow: View?, hasFocus: Boolean, boost: Float = 1f) {
        val look = LookStore.current
        val target = if (hasFocus) 1f + look.scale / 100f * boost else 1f
        val duration = look.anim.toLong()
        view.animate().cancel()
        view.animate().scaleX(target).scaleY(target).setDuration(duration).start()
        view.translationZ = if (hasFocus) view.resources.displayMetrics.density * 8f else 0f
        if (glow != null) {
            glow.animate().cancel()
            glow.animate().alpha(if (hasFocus) look.glow / 100f else 0f).setDuration(duration).start()
        }
    }

    /** Usado ao reaproveitar itens de lista: volta ao estado normal sem animar. */
    fun reset(view: View, glow: View?) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationZ = 0f
        if (glow != null) {
            glow.animate().cancel()
            glow.alpha = 0f
        }
    }
}

/** Redesenha todos os vidros de uma tela (usado quando um ajuste de aparência muda). */
fun invalidateGlass(root: View) {
    if (root is GlassLayout) root.invalidate()
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) invalidateGlass(root.getChildAt(i))
    }
}
