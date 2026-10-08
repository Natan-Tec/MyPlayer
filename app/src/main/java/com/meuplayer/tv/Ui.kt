package com.meuplayer.tv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

fun Context.dpi(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.toast(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

/** Esconde as barras do sistema (tela cheia). */
fun Activity.immersive() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}

/** Coloca o fundo azul das ondas (um único bitmap leve compartilhado entre as telas). */
fun Activity.bindBackdrop(imageViewId: Int) {
    findViewById<ImageView>(imageViewId).setImageBitmap(Backdrop.sharp(this))
}

object Fonts {
    fun regular(ctx: Context): Typeface? = ResourcesCompat.getFont(ctx, R.font.montserrat_regular)
    fun semi(ctx: Context): Typeface? = ResourcesCompat.getFont(ctx, R.font.montserrat_semibold)
    fun bold(ctx: Context): Typeface? = ResourcesCompat.getFont(ctx, R.font.montserrat_bold)
}

/**
 * O fundo nítido e a versão desfocada (usada no efeito de vidro).
 * Ficam em RGB_565 e são decodificados uma única vez: pouco peso para TVs com 1 GB de RAM.
 */
object Backdrop {
    @Volatile
    private var sharpBmp: Bitmap? = null

    @Volatile
    private var blurBmp: Bitmap? = null

    fun sharp(ctx: Context): Bitmap =
        sharpBmp ?: synchronized(this) {
            sharpBmp ?: decode(ctx, R.drawable.bg_main).also { sharpBmp = it }
        }

    fun blur(ctx: Context): Bitmap =
        blurBmp ?: synchronized(this) {
            blurBmp ?: decode(ctx, R.drawable.bg_blur).also { blurBmp = it }
        }

    private fun decode(ctx: Context, resId: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = false
        }
        return BitmapFactory.decodeResource(ctx.applicationContext.resources, resId, options)
            ?: Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565)
    }
}
