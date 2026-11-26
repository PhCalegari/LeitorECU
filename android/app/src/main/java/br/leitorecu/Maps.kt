package br.leitorecu

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.util.Locale

/**
 * Mapa OBSERVADO (só leitura): média de um valor lido da central (avanço ou tempo de injeção) em cada
 * faixa de rotação × pressão do coletor, preenchido enquanto o carro roda. Mostra como a central está
 * trabalhando, no formato de tabela dos programas de injeção. O app NÃO grava nada na central.
 */
class MapGrid(val key: String, val title: String, val unit: String, val dec: Int, private val signal: String) {
    // pontos da tabela como nos editores de calibração: mais finos na baixa rotação (lenta, saída, retomada)
    val rpmEdges = listOf(500, 650, 750, 900, 1150, 1350, 1600, 1850, 2050, 2300, 2700, 3200, 3650, 4050, 4550, 5450, 6400)
    val mapEdges = (2..10).map { it * 100 }               // colunas: pressão do coletor 200 … 1000 mbar
    val rows = rpmEdges.size
    val cols = mapEdges.size
    private val sum = DoubleArray(rows * cols)
    private val cnt = IntArray(rows * cols)
    var curRow = -1; private set
    var curCol = -1; private set

    private fun sig(k: String, b: Map<Int, ByteArray>) = RenaultMap.SIGNALS.firstOrNull { it.key == k }?.read(b)

    /** Ponto da tabela mais próximo (como a central, que trabalha nos pontos e interpola entre eles). */
    private fun idx(v: Double, edges: List<Int>): Int {
        if (v < edges.first() - (edges[1] - edges[0]) / 2.0) return -1
        return edges.indices.minByOrNull { kotlin.math.abs(edges[it] - v) } ?: -1
    }

    /** Rotação e pressão de agora (para os marcadores vermelhos nos cabeçalhos). */
    var liveRpm: Double? = null; private set
    var liveMap: Double? = null; private set

    fun feed(b: Map<Int, ByteArray>) {
        val rpm = sig("rpm", b); val map = sig("map", b); val v = sig(signal, b)
        curRow = -1; curCol = -1
        liveRpm = rpm?.takeIf { it >= 300 }; liveMap = map?.takeIf { it <= 1100 }
        if (rpm == null || map == null || v == null) return
        if (rpm < 500 || map > 1100) return
        if (sig("st_inj", b) == 0.0) return                // corte de injeção: não representa o mapa
        val r = idx(rpm, rpmEdges); val c = idx(map, mapEdges)
        if (r < 0 || c < 0) return
        curRow = r; curCol = c
        val i = r * cols + c
        sum[i] += v; cnt[i]++
    }

    fun value(r: Int, c: Int): Double? = (r * cols + c).let { if (cnt[it] >= 3) sum[it] / cnt[it] else null }
    fun filled() = cnt.count { it >= 3 }

    /**
     * Valor estimado para uma célula ainda não lida: interpolação linear entre as células LIDAS mais próximas
     * na mesma linha (dos dois lados) ou, se não houver, na mesma coluna. Fora delas (sem vizinhos dos dois
     * lados) não inventa nada: devolve null.
     */
    fun estimate(r: Int, c: Int): Double? {
        value(r, c)?.let { return it }
        fun lin(i: Int, n: Int, get: (Int) -> Double?, pos: (Int) -> Double, at: Double): Double? {
            var a = i - 1; while (a >= 0 && get(a) == null) a--
            var b = i + 1; while (b < n && get(b) == null) b++
            if (a < 0 || b >= n) return null
            val va = get(a)!!; val vb = get(b)!!
            return va + (vb - va) * (at - pos(a)) / (pos(b) - pos(a))
        }
        return lin(c, cols, { value(r, it) }, { mapEdges[it].toDouble() }, mapEdges[c].toDouble())
            ?: lin(r, rows, { value(it, c) }, { rpmEdges[it].toDouble() }, rpmEdges[r].toDouble())
    }

    /** Posição fracionária (em células) de um valor entre os pontos da tabela, para desenhar o marcador. */
    fun frac(v: Double, edges: List<Int>): Double {
        if (v <= edges.first()) return 0.0
        if (v >= edges.last()) return (edges.size - 1).toDouble()
        val i = edges.indexOfLast { v >= it }
        return i + (v - edges[i]) / (edges[i + 1] - edges[i])
    }

    fun clear() { sum.fill(0.0); cnt.fill(0) }

    fun save(p: SharedPreferences) {
        val s = (0 until rows * cols).filter { cnt[it] > 0 }.joinToString(";") { "$it:${sum[it]}:${cnt[it]}" }
        p.edit().putString("map2_$key", s).apply()
    }

    fun load(p: SharedPreferences) {
        clear()
        (p.getString("map2_$key", "") ?: "").split(";").forEach { e ->
            val q = e.split(":"); if (q.size != 3) return@forEach
            val i = q[0].toIntOrNull() ?: return@forEach
            if (i in sum.indices) { sum[i] = q[1].toDoubleOrNull() ?: 0.0; cnt[i] = q[2].toIntOrNull() ?: 0 }
        }
    }
}

/** Os mapas do app: um gravado (carro de verdade) e um só na memória para o Demo não sujar o real. */
object Maps {
    private var prefs: SharedPreferences? = null
    private var lastSave = 0L
    val real = listOf(
        MapGrid("avanco", "Mapa de ponto", "°", 2, "avanco"),
        MapGrid("inj", "Mapa de injeção", "ms", 2, "inj"),
    )
    val demo = listOf(
        MapGrid("avanco", "Mapa de ponto", "°", 2, "avanco"),
        MapGrid("inj", "Mapa de injeção", "ms", 2, "inj"),
    )
    var usingDemo = false

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("maps", 0)
        real.forEach { it.load(prefs!!) }
    }

    fun active() = if (usingDemo) demo else real

    fun feed(b: Map<Int, ByteArray>, isDemo: Boolean) {
        usingDemo = isDemo
        val list = if (isDemo) demo else real
        list.forEach { it.feed(b) }
        val now = System.currentTimeMillis()
        if (!isDemo && now - lastSave > 30_000) { lastSave = now; prefs?.let { p -> real.forEach { it.save(p) } } }
    }

    fun clear() { active().forEach { it.clear() }; if (!usingDemo) prefs?.let { p -> real.forEach { it.save(p) } } }
}

/**
 * Tabela no formato dos editores de calibração (planilha): cabeçalho duplo ("Col 1…n" e a pressão de cada coluna),
 * coluna de rotação em cinza à esquerda, grade de linhas finas, números alinhados à direita com 2 casas.
 * Cores suaves (verde-água baixo → salmão/vermelho alto). Marcadores vermelhos nos cabeçalhos mostram a rotação e a
 * pressão de agora, e a célula atual fica vermelha, como a célula selecionada do editor.
 * Células em itálico e mais claras = estimadas entre células lidas (não foram lidas diretamente).
 */
class MapView(ctx: Context) : View(ctx) {
    var grid: MapGrid? = null
        set(v) { field = v; requestLayout(); invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private val sd = ctx.resources.displayMetrics.scaledDensity
    private val cellH = 26 * d
    private val headH = 24 * d
    private val fill = Paint()
    private val gridP = Paint().apply { strokeWidth = 1f }
    private val mark = Paint().apply { strokeWidth = 2.5f * d; color = Color.parseColor("#E53935") }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * sd; textAlign = Paint.Align.RIGHT; fontFeatureSettings = "tnum"
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * sd; textAlign = Paint.Align.RIGHT; fontFeatureSettings = "tnum"
    }
    private val br = Locale("pt", "BR")
    private val upright = Typeface.create("sans-serif", Typeface.NORMAL)
    private val italic = Typeface.create("sans-serif", Typeface.ITALIC)

    override fun onMeasure(w: Int, h: Int) {
        val rows = grid?.rows ?: 12
        setMeasuredDimension(MeasureSpec.getSize(w), (2 * headH + rows * cellH + 2).toInt())
    }

    override fun onDraw(c: Canvas) {
        val g = grid ?: return
        val night = Ui.night
        val headBg = if (night) Color.parseColor("#2A2F35") else Color.parseColor("#E9EAEC")
        val emptyBg = if (night) Color.parseColor("#14181C") else Color.WHITE
        val lineC = if (night) Color.parseColor("#3A4048") else Color.parseColor("#9DA3A9")
        val headTxt = if (night) Color.parseColor("#D6DBE0") else Color.parseColor("#1A1D21")
        c.drawColor(emptyBg)

        val w = width.toFloat()
        val labelW = 46 * d
        val cw = (w - labelW) / g.cols
        val top0 = 2 * headH
        // escala de cor pelos valores lidos (como o editor colore pela tabela inteira)
        val vals = (0 until g.rows).flatMap { r -> (0 until g.cols).mapNotNull { g.value(r, it) } }
        val mn = vals.minOrNull() ?: 0.0
        val mx = vals.maxOrNull() ?: 1.0

        // ---- cabeçalhos (fundo cinza) ----
        fill.color = headBg
        c.drawRect(0f, 0f, w, top0, fill)
        c.drawRect(0f, top0, labelW, top0 + g.rows * cellH, fill)
        head.color = headTxt
        head.typeface = upright
        head.textAlign = Paint.Align.CENTER
        c.drawText("Col", labelW / 2, headH * 0.7f, head)
        c.drawText("mbar", labelW / 2, headH + headH * 0.7f, head)
        for (ci in 0 until g.cols) {
            val cx = labelW + cw * ci + cw / 2
            c.drawText("${ci + 1}", cx, headH * 0.7f, head)
            c.drawText("${g.mapEdges[ci]}", cx, headH + headH * 0.7f, head)
        }
        head.textAlign = Paint.Align.RIGHT
        for (r in 0 until g.rows) {
            c.drawText("${g.rpmEdges[r]}", labelW - 5 * d, top0 + r * cellH + cellH * 0.7f, head)
        }

        // ---- células ----
        for (r in 0 until g.rows) {
            val top = top0 + r * cellH
            for (ci in 0 until g.cols) {
                val left = labelW + cw * ci
                val read = g.value(r, ci)
                val v = read ?: g.estimate(r, ci)
                val isCur = r == g.curRow && ci == g.curCol
                if (v != null) {
                    val t = if (mx - mn < 1e-9) 0.5 else (v - mn) / (mx - mn)
                    var col = Ui.mapHeat(t)
                    if (read == null) col = blend(col, emptyBg, 0.55f)     // estimada: cor mais clara
                    fill.color = if (isCur) Color.parseColor("#E53935") else col
                    c.drawRect(left, top, left + cw, top + cellH, fill)
                    txt.typeface = if (read == null) italic else upright
                    txt.color = when {
                        isCur -> Color.WHITE
                        read == null -> Color.parseColor("#5A6168")
                        else -> Color.parseColor("#1A1D21")
                    }
                    c.drawText(String.format(br, "%.${g.dec}f", v), left + cw - 4 * d, top + cellH * 0.7f, txt)
                } else if (isCur) {
                    fill.color = Color.parseColor("#E53935")
                    c.drawRect(left, top, left + cw, top + cellH, fill)
                }
            }
        }

        // ---- grade de linhas finas ----
        gridP.color = lineC
        val bottom = top0 + g.rows * cellH
        for (i in 0..g.cols) { val x = labelW + cw * i; c.drawLine(x, 0f, x, bottom, gridP) }
        c.drawLine(0f, 0f, 0f, bottom, gridP); c.drawLine(w - 1, 0f, w - 1, bottom, gridP)
        for (i in 0..g.rows + 2) {
            val y = if (i <= 2) headH * i else top0 + (i - 2) * cellH
            c.drawLine(0f, y.coerceAtMost(bottom - 1), w, y.coerceAtMost(bottom - 1), gridP)
        }

        // ---- marcadores vermelhos de onde o motor está agora (como no editor) ----
        g.liveMap?.let { m ->
            val x = labelW + cw * (g.frac(m, g.mapEdges).toFloat() + 0.5f)
            c.drawLine(x, 2 * d, x, headH - 2 * d, mark)
        }
        g.liveRpm?.let { rpm ->
            val y = top0 + cellH * (g.frac(rpm, g.rpmEdges).toFloat() + 0.5f)
            c.drawLine(2 * d, y, labelW - 2 * d, y, mark)
        }
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }
}
