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
 * Identidade visual: linguagem dos programas de injeção (TuneECU e afins): tudo reto, blocos chapados,
 * destaque azul e valores coloridos pela escala de calor das tabelas de mapa. Âmbar = a confirmar,
 * vermelho = perigo, verde = funcionando. Números em Roboto Condensed com algarismos tabulares.
 * Dois modos: diurno (branco) e noturno (preto, para não ofuscar à noite).
 */
object Ui {
    private fun c(s: String) = Color.parseColor(s)

    /** true = modo noturno (preto). Trocar com [setNight] e reconstruir as telas. */
    var night = false; private set

    var FACE = 0; private set        // fundo
    var BAR = 0; private set         // barra superior (título + menu)
    var SURFACE = 0; private set     // células sem cor e placas
    var LINE = 0; private set        // divisórias finas
    var TEXT = 0; private set
    var MUTED = 0; private set
    var NEEDLE = 0; private set      // destaque: ação principal, aba aberta (azul)
    var AMBER = 0; private set       // atenção / a confirmar
    var RED = 0; private set         // perigo / faixa vermelha
    var GREEN = 0; private set       // funcionando / conectado
    var AMBER_BG = 0; private set    // fundo de aviso
    // tons dos botões e detalhes
    var BTN = 0; private set; var BTN_OFF = 0; private set; var TXT_OFF = 0; private set
    var RIPPLE = 0; private set; var REDZONE = 0; private set

    fun setNight(on: Boolean) {
        night = on
        if (on) {
            FACE = c("#000000"); BAR = c("#2A2F35"); SURFACE = c("#14181C"); LINE = c("#252C34")
            TEXT = c("#F1F4F7"); MUTED = c("#8893A0"); NEEDLE = c("#2F7DF6")
            AMBER = c("#F2B33D"); RED = c("#E5483B"); GREEN = c("#3CCB7F"); AMBER_BG = c("#2E2615")
            BTN = c("#262D35"); BTN_OFF = c("#1E242B"); TXT_OFF = c("#56616C")
            RIPPLE = c("#33FFFFFF"); REDZONE = c("#5A2420")
        } else {
            FACE = c("#FFFFFF"); BAR = c("#F1F3F5"); SURFACE = c("#EEF1F4"); LINE = c("#D5DBE1")
            TEXT = c("#14181C"); MUTED = c("#5E6A76"); NEEDLE = c("#1F6FEB")
            AMBER = c("#B8740A"); RED = c("#D2362B"); GREEN = c("#17944F"); AMBER_BG = c("#FFF1D6")
            BTN = c("#E3E8ED"); BTN_OFF = c("#E9EDF1"); TXT_OFF = c("#A3ACB5")
            RIPPLE = c("#26000000"); REDZONE = c("#F5C6C0")
        }
    }

    init { setNight(false) }

    // escala de calor das tabelas: azul → ciano → verde → amarelo → vermelho
    private val HEAT = intArrayOf(
        Color.parseColor("#3D5AFE"), Color.parseColor("#00B8E6"), Color.parseColor("#2BD46A"),
        Color.parseColor("#F5E13A"), Color.parseColor("#F0402C"),
    )

    /** Cor da escala de calor para t de 0 (baixo) a 1 (alto). */
    fun heat(t: Double): Int {
        val x = t.coerceIn(0.0, 1.0) * (HEAT.size - 1)
        val i = x.toInt().coerceAtMost(HEAT.size - 2)
        val f = (x - i).toFloat()
        val a = HEAT[i]; val b = HEAT[i + 1]
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * f).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    // cores suaves das tabelas de calibração (verde-água baixo → verde → amarelo → salmão → vermelho alto)
    private val MAPHEAT = intArrayOf(
        Color.parseColor("#7FE3D4"), Color.parseColor("#A9E28C"), Color.parseColor("#DCD983"),
        Color.parseColor("#F0B08C"), Color.parseColor("#EE8080"),
    )

    /** Cor de célula de mapa (tons suaves, texto escuro), t de 0 (baixo) a 1 (alto). */
    fun mapHeat(t: Double): Int {
        val x = t.coerceIn(0.0, 1.0) * (MAPHEAT.size - 1)
        val i = x.toInt().coerceAtMost(MAPHEAT.size - 2)
        val f = (x - i).toFloat()
        val a = MAPHEAT[i]; val b = MAPHEAT[i + 1]
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * f).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    /** Faixa típica de cada valor (para colorir pela escala de calor). Sem faixa = branco. */
    val RANGES: Map<String, Pair<Double, Double>> = mapOf(
        "rpm" to (600.0 to 6000.0), "map" to (250.0 to 1000.0), "agua" to (20.0 to 105.0),
        "ar" to (0.0 to 70.0), "pedal" to (14.0 to 100.0), "pedal1" to (14.0 to 100.0), "pedal2" to (14.0 to 100.0),
        "avanco" to (-5.0 to 40.0), "inj" to (1.0 to 12.0), "lambda" to (0.05 to 0.9), "lambda2" to (0.05 to 0.9),
        "borb" to (10.0 to 90.0), "rpm_alvo" to (700.0 to 1100.0), "cons_lh" to (0.3 to 10.0),
    )

    fun heatFor(key: String, v: Double): Int? = RANGES[key]?.let { (a, b) -> heat((v - a) / (b - a)) }

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
    fun panel(ctx: Context, fill: Int = SURFACE) = shape(fill, 0f)   // reto: nada de cantos arredondados

    private fun ripple(ctx: Context, content: Drawable, onDark: Boolean = true) =
        RippleDrawable(ColorStateList.valueOf(RIPPLE), content, shape(Color.WHITE, 0f))

    enum class Kind { PRIMARY, GHOST, QUIET }

    /** Aplica o estilo a um botão já criado (mantém tamanhos/ações de quem chamou). */
    fun style(b: Button, kind: Kind = Kind.GHOST) {
        val ctx = b.context
        b.isAllCaps = true          // botões de ferramenta, como os menus dos programas de injeção
        b.stateListAnimator = null       // sem a "elevação" padrão do Material
        b.typeface = BODY_MED
        b.letterSpacing = 0.03f
        when (kind) {
            Kind.PRIMARY -> {
                // desativado não pode parecer clicável: vira contorno apagado
                val states = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(-android.R.attr.state_enabled), shape(BTN_OFF, 0f))
                    addState(intArrayOf(), shape(NEEDLE, 0f))
                }
                b.background = ripple(ctx, states, onDark = false)
                b.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(TXT_OFF, Color.WHITE)))
            }
            Kind.GHOST -> {
                b.background = ripple(ctx, shape(BTN, 0f))   // chapado, sem borda
                b.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(TXT_OFF, TEXT)))
            }
            Kind.QUIET -> {
                b.background = ripple(ctx, shape(Color.TRANSPARENT, 0f))
                b.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(TXT_OFF, MUTED)))
            }
        }
    }

    /** Aba do menu (na barra superior): a aberta fica clara com um traço azul embaixo. */
    fun tab(tv: TextView, selected: Boolean) {
        val ctx = tv.context
        tv.setTextColor(if (selected) TEXT else MUTED)
        tv.typeface = BODY_MED
        val bar = shape(if (selected) NEEDLE else Color.TRANSPARENT, 0f)
        val layer = LayerDrawable(arrayOf(bar))
        layer.setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        layer.setLayerHeight(0, dpi(ctx, 3))
        tv.background = RippleDrawable(ColorStateList.valueOf(RIPPLE), layer,
            shape(Color.WHITE, 0f))
    }

    /** Fundo da fileira de abas: uma linha fina contínua embaixo de todas. */
    fun tabRowBackground(ctx: Context): Drawable {
        val layer = LayerDrawable(arrayOf(shape(LINE, 0f)))
        layer.setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        layer.setLayerHeight(0, maxOf(1, dpi(ctx, 1)))
        return layer
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
 * Indicador de mistura com LEDs, como os medidores de sonda de banda estreita: 0 a 1 V da sonda dianteira.
 * Esquerda (abaixo de 0,45 V) = POBRE em azul; direita = RICA em vermelho; o centro (0,45 V) é o ideal.
 * Acende o LED da leitura atual e os vizinhos mais fracos; no corte de injeção ([cut]) fica tudo apagado.
 */
class MixBarView(ctx: Context, private val n: Int = 21) : View(ctx) {
    var volts: Double? = null
        set(v) { field = v; invalidate() }
    var cut = false
        set(v) { field = v; invalidate() }
    /** O bloco está pintado (escala de calor): rótulos em preto, como o resto do texto do bloco. */
    var onColor = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = ctx.resources.displayMetrics.density
    private val plate = Paint().apply { color = Color.parseColor("#15181C") }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.BODY_MED; textSize = 11f * ctx.resources.displayMetrics.scaledDensity; letterSpacing = 0.05f
    }
    private val lean = Color.parseColor("#2F6BFF")
    private val ideal = Color.parseColor("#27C26C")
    private val rich = Color.parseColor("#F0402C")

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (46 * d).toInt())

    private fun ledColor(i: Int): Int {
        val mid = n / 2
        return when {
            i == mid -> ideal
            i < mid -> lean
            else -> rich
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        // módulo de LED: placa escura atrás dos LEDs (fica legível sobre qualquer cor de fundo)
        val pad = 3 * d
        val top = 2 * d + pad; val h = 16 * d
        c.drawRect(0f, top - pad, w, top + h + pad, plate)
        val gap = (if (n <= 15) 2 else 3) * d
        val lw = (w - 2 * pad - gap * (n - 1)) / n
        // LED aceso = posição da tensão (0,0 a 1,0 V em 21 passos de 0,05 V)
        val lit = volts?.let { ((it.coerceIn(0.0, 1.0)) * (n - 1) + 0.5).toInt() }   // n LEDs de 0 a 1 V
        for (i in 0 until n) {
            val x = pad + i * (lw + gap)
            val dist = if (lit == null || cut) 99 else kotlin.math.abs(i - lit)
            val col = ledColor(i)
            p.color = col
            p.alpha = when (dist) { 0 -> 255; 1 -> 120; else -> 38 }
            c.drawRect(x, top, x + lw, top + h, p)
        }
        p.alpha = 255
        val y = top + h + pad + 15 * d
        val dark = Color.argb(200, 0, 0, 0)
        label.textAlign = Paint.Align.LEFT; label.color = if (onColor) dark else lean
        c.drawText("POBRE", 0f, y, label)
        label.textAlign = Paint.Align.CENTER; label.color = if (onColor) dark else Ui.MUTED
        c.drawText("0,45 V", w / 2, y, label)
        label.textAlign = Paint.Align.RIGHT; label.color = if (onColor) dark else rich
        c.drawText("RICA", w, y, label)
    }
}

/**
 * Conta-giros em barra: escala de 0 a 7.000 rpm com marcas de 1.000 em 1.000 e faixa vermelha a partir de 6.000.
 * O preenchimento segue a escala de calor (azul em baixa, vermelho no alto), como as tabelas de mapa.
 */
class TachView(ctx: Context) : View(ctx) {
    var rpm: Double? = null
        set(v) { field = v; invalidate() }

    private val maxRpm = 7000.0
    private val red = 6000.0
    private val d = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LINE }
    private val redZone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.REDZONE }
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
        c.drawRoundRect(r, 0f, 0f, track)
        val xr = (w * red / maxRpm).toFloat()
        c.drawRoundRect(RectF(xr, barTop, w, barTop + barH), 0f, 0f, redZone)
        rpm?.let { v ->
            val x = (w * (v.coerceIn(0.0, maxRpm) / maxRpm)).toFloat()
            // gradiente fixo na largura toda: a cor da ponta mostra onde está a rotação
            fill.shader = android.graphics.LinearGradient(0f, 0f, w, 0f,
                intArrayOf(Ui.heat(0.0), Ui.heat(0.25), Ui.heat(0.5), Ui.heat(0.75), Ui.heat(1.0)), null,
                android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, barTop, x.coerceAtLeast(3 * d), barTop + barH), 0f, 0f, fill)
            fill.shader = null
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
