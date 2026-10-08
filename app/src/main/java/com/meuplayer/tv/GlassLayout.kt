package com.meuplayer.tv

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Painel com efeito de vidro fosco.
 *
 * Em vez de borrar a tela em tempo real (pesado para a TV), desenha o pedaço correspondente
 * de uma versão do fundo que já vem borrada, por cima uma camada branca translúcida e uma
 * borda fina clara. O resultado visual é o mesmo, a um custo quase zero.
 */
class GlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Raio dos cantos, em dp. */
    var cornerDp: Float = 12f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val borderRect = RectF()
    private val matrix = Matrix()
    private val location = IntArray(2)
    private var shader: BitmapShader? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val look = LookStore.current
        val density = resources.displayMetrics.density
        val radius = cornerDp * density

        val bmp = Backdrop.blur(context)
        var sh = shader
        if (sh == null) {
            sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader = sh
            fillPaint.shader = sh
        }

        // Onde este painel está na tela, para recortar o pedaço certo do fundo.
        getLocationOnScreen(location)
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sHeight = dm.heightPixels.toFloat()
        val scale = max(sw / bmp.width, sHeight / bmp.height)
        val dx = (sw - bmp.width * scale) / 2f
        val dy = (sHeight - bmp.height * scale) / 2f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx - location[0], dy - location[1])
        sh.setLocalMatrix(matrix)

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        tintPaint.color = Color.argb((look.glass / 100f * 0.5f * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, tintPaint)

        val stroke = 1.2f * density
        borderPaint.strokeWidth = stroke
        borderPaint.color = Color.argb((look.border / 100f * 0.8f * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
        borderRect.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f)
        canvas.drawRoundRect(borderRect, radius, radius, borderPaint)
    }
}
