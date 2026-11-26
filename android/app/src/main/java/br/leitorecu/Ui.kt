package br.leitorecu

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Identidade visual: o painel de instrumentos do carro.
 * As cores só aparecem com o significado das luzes do painel (ISO 2575):
 * âmbar = atenção/a confirmar, vermelho = perigo, verde = funcionando. O laranja do ponteiro marca a ação
 * principal e o conta-giros. Números em Roboto Condensed com algarismos tabulares (não "pulam" ao mudar).
 */
object Ui {
    val FACE = Color.parseColor("#151B21")      // fundo (face do painel, escuro para não ofuscar à noite)
    val SURFACE = Color.parseColor("#1C242C")   // placas e botões
    val LINE = Color.parseColor("#2A343E")      // divisórias finas
    val TEXT = Color.parseColor("#E8EDF2")      // texto "retroiluminado"
    val MUTED = Color.parseColor("#8C99A6")     // rótulos e unidades
    val NEEDLE = Color.parseColor("#FF7A1A")    // ponteiro: ação principal e conta-giros
    val AMBER = Color.parseColor("#F2B33D")     // atenção / a confirmar
    val RED = Color.parseColor("#E5483B")       // perigo / faixa vermelha
    val GREEN = Color.parseColor("#4CC38A")     // funcionando / conectado
    val AMBER_BG = Color.parseColor("#33291A")  // fundo de aviso

    /** Números: condensada, negrito, algarismos de largura fixa. */
    val NUM: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val NUM_REG: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val BODY: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val BODY_MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density
    fun dpi(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun num(tv: TextView, bold: Boolean = true) {
        tv.typeface = if (bold) NUM else NUM_REG
        tv.fontFeatureSettings = "tnum"
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    /** Placa: superfície lisa com cantos discretos (sem sombra). */
    fun panel(ctx: Context, fill: Int = SURFACE) = shape(fill, dp(ctx, 6f))

    private fun ripple(ctx: Context, content: Drawable, onDark: Boolean = true) =
        RippleDrawable(ColorStateList.valueOf(if (onDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#33000000")),
            content, shape(Color.WHITE, dp(ctx, 6f)))

    enum class Kind { PRIMARY, GHOST, QUIET }

    /** Aplica o estilo a um botão já criado (mantém tamanhos/ações de quem chamou). */
    fun style(b: Button, kind: Kind = Kind.GHOST) {
        val ctx = b.context
        b.isAllCaps = false
        b.stateListAnimator = null       // sem a "elevação" padrão do Material
        b.typeface = BODY_MED
        b.letterSpacing = 0f
        when (kind) {
            Kind.PRIMARY -> {
                b.background = ripple(ctx, shape(NEEDLE, dp(ctx, 6f)), onDark = false)
                b.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(Color.parseColor("#7A4A2A"), Color.parseColor("#1A1208"))))
            }
            Kind.GHOST -> {
                b.background = ripple(ctx, shape(SURFACE, dp(ctx, 6f), LINE, dpi(ctx, 1)))
                b.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(Color.parseColor("#56616C"), TEXT)))
            }
            Kind.QUIET -> {
                b.background = ripple(ctx, shape(Color.TRANSPARENT, dp(ctx, 6f)))
                b.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(Color.parseColor("#56616C"), MUTED)))
            }
        }
    }

    /** Aba: texto; a selecionada fica clara com um traço laranja embaixo (como o ponteiro). */
    fun tab(tv: TextView, selected: Boolean) {
        val ctx = tv.context
        tv.setTextColor(if (selected) TEXT else MUTED)
        tv.typeface = if (selected) BODY_MED else BODY
        val bar = shape(if (selected) NEEDLE else LINE, 0f)
        val layer = LayerDrawable(arrayOf(bar))
        val h = if (selected) dpi(ctx, 3) else dpi(ctx, 1)
        layer.setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        layer.setLayerHeight(0, h)
        tv.background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22FFFFFF")), layer, null)
    }

    /** Cor da "luz de status" pelo texto da mensagem. */
    fun statusColor(s: String): Int {
        val t = s.lowercase()
        return when {
            listOf("falha", "perdida", "negada", "não tem", "desligado –", "erro").any { it in t } -> RED
            listOf("procurando", "aguardando", "reconectando", "conectando", "lendo", "iniciando", "tentando", "inicializando")
                .any { it in t } -> AMBER
            t.startsWith("conectado") || t.startsWith("reconectado") -> GREEN
            else -> MUTED
        }
    }
}

/**
 * Conta-giros em barra: escala de 0 a 7.000 rpm com marcas de 1.000 em 1.000 e faixa vermelha a partir de 6.000.
 * O preenchimento é o "ponteiro" laranja; passa a vermelho dentro da faixa.
 */
class TachView(ctx: Context) : View(ctx) {
    var rpm: Double? = null
        set(v) { field = v; invalidate() }

    private val maxRpm = 7000.0
    private val red = 6000.0
    private val d = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LINE }
    private val redZone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5A2420") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.NUM_REG; textSize = 11f * ctx.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER; color = Ui.MUTED
        fontFeatureSettings = "tnum"
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (34 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val barTop = 2 * d; val barH = 10 * d
        val r = RectF(0f, barTop, w, barTop + barH)
        c.drawRoundRect(r, 3 * d, 3 * d, track)
        val xr = (w * red / maxRpm).toFloat()
        c.drawRoundRect(RectF(xr, barTop, w, barTop + barH), 3 * d, 3 * d, redZone)
        rpm?.let { v ->
            val x = (w * (v.coerceIn(0.0, maxRpm) / maxRpm)).toFloat()
            fill.color = if (v >= red) Ui.RED else Ui.NEEDLE
            c.drawRoundRect(RectF(0f, barTop, x.coerceAtLeast(3 * d), barTop + barH), 3 * d, 3 * d, fill)
        }
        // marcas a cada 1.000 rpm; números 1..7 (x 1000)
        for (k in 1..7) {
            val x = (w * k * 1000 / maxRpm).toFloat().coerceAtMost(w - 1 * d)
            tick.color = if (k * 1000 >= red) Ui.RED else Ui.MUTED
            c.drawLine(x, barTop + barH + 2 * d, x, barTop + barH + 6 * d, tick)
            if (k < 7) {
                label.color = if (k * 1000 >= red) Ui.RED else Ui.MUTED
                c.drawText("$k", x, barTop + barH + 18 * d, label)
            }
        }
        label.color = Ui.MUTED
        label.textAlign = Paint.Align.RIGHT
        c.drawText("× 1000", w, barTop + barH + 18 * d, label)
        label.textAlign = Paint.Align.CENTER
    }
}
