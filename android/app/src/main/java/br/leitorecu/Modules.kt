package br.leitorecu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale

/** Um "módulo" do Painel: um valor que pode ser mostrado em card e no gráfico. */
class Module(
    val key: String,
    val name: String,
    val unit: String,
    val dec: Int,
    val provisional: Boolean,
    val raw: Boolean,
    /** Se definido, mostra texto no lugar do número (ex.: estado da sonda). */
    val text: ((Double) -> String)? = null,
    val read: (Map<Int, ByteArray>) -> Double?,
)

/**
 * Acompanha a sonda lambda dianteira nos últimos ~12 s (ignorando o corte de injeção, em que ela
 * cai naturalmente para ~0,05–0,1 V). Com o motor quente em malha fechada ela deve OSCILAR entre
 * ~0,1 V (pobre) e ~0,9 V (rica).
 */
object LambdaWatch {
    private val h = ArrayDeque<Pair<Long, Double>>()
    private fun sig(k: String, b: Map<Int, ByteArray>) = RenaultMap.SIGNALS.firstOrNull { it.key == k }?.read(b)

    fun feed(b: Map<Int, ByteArray>) {
        val now = System.currentTimeMillis()
        val v = sig("lambda", b) ?: return
        val rpm = sig("rpm", b) ?: return
        if (rpm < 500) { h.clear(); return }
        if (sig("st_inj", b) == 0.0) return          // corte de injeção: leitura baixa é normal
        if (v > 1.2) return                          // sonda fria / sem sinal
        h.addLast(now to v)
        while (h.isNotEmpty() && now - h.first().first > 12_000) h.removeFirst()
    }

    /** 2 = oscilando OK, 1 = rica, 0 = pobre, -1 = parada, 3 = motor frio (malha aberta). */
    fun state(b: Map<Int, ByteArray>): Double? {
        val water = sig("agua", b)
        if (water != null && water < 65) return 3.0
        if (h.size < 5) return null
        val vs = h.map { it.second }
        var cross = 0
        for (i in 1 until vs.size) if ((vs[i - 1] < 0.45) != (vs[i] < 0.45)) cross++
        val avg = vs.average()
        val span = vs.max() - vs.min()
        return when {
            span > 0.4 && cross >= 2 -> 2.0            // oscilando: malha fechada funcionando
            avg < 0.3 -> 0.0                           // pobre (ex.: pouco combustível / entrada de ar)
            avg > 0.65 -> 1.0                          // rica (ex.: aceleração forte, enriquecimento)
            span < 0.15 -> -1.0                        // realmente parada no meio: sonda suspeita
            else -> 2.0                                // em transição, sem sinal de problema
        }
    }

    // ---- correção da riqueza (PR035, 128 = neutro) nos últimos 30 s, só com injeção ativa e motor quente ----
    private val corr = ArrayDeque<Pair<Long, Double>>()

    fun feedMix(b: Map<Int, ByteArray>) {
        val now = System.currentTimeMillis()
        val c = sig("corr", b) ?: return
        val rpm = sig("rpm", b) ?: return
        val water = sig("agua", b)
        if (rpm < 500 || (water != null && water < 65)) { corr.clear(); return }
        if (sig("st_inj", b) == 0.0 || c == 128.0) return   // corte de injeção / malha aberta (fica em 128)
        corr.addLast(now to c)
        while (corr.isNotEmpty() && now - corr.first().first > 30_000) corr.removeFirst()
    }

    /** Correção média em % (positivo = central adicionando combustível, motor tendendo a pobre). */
    fun mix(b: Map<Int, ByteArray>): Double? {
        val water = sig("agua", b)
        if (water != null && water < 65) return MIX_COLD
        if (corr.size < 3) return null
        return (corr.map { it.second }.average() - 128) * 100 / 128
    }

    const val MIX_COLD = 999.0

    fun mixLabel(p: Double): String {
        if (p == MIX_COLD) return "Motor frio"
        val s = String.format(java.util.Locale("pt", "BR"), "%+.0f %%", p)
        return when {
            p > 20 -> "Pobre ⚠ ($s)"
            p > 10 -> "Tend. pobre ($s)"
            p < -20 -> "Rica ⚠ ($s)"
            p < -10 -> "Tend. rica ($s)"
            else -> "OK ($s)"
        }
    }

    fun label(v: Double) = when (v.toInt()) {
        2 -> "Oscilando ✓"
        1 -> "Rica"
        0 -> "Pobre"
        3 -> "Motor frio"
        else -> "Parada ⚠"
    }
}

object Modules {
    /** Valores já identificados (mapa Renault SIM 32 Vdiag 04). */
    val known: List<Module> = RenaultMap.SIGNALS.map { s ->
        Module(s.key, s.name, s.unit, s.dec, s.provisional, false) { b -> s.read(b) }
    } + listOf(
        // velocidade lida da central – posição descoberta automaticamente comparando com o GPS (aba Pista)
        Module("vel_ecu", "Velocidade (central)", "km/h", 0, true, false) { b -> SpeedMap.read(b) },
        // consumo (estimado pelo tempo de injeção; fica "provisório" até calibrar no abastecimento)
        Module("cons_lh", "Consumo", "L/h", 2, true, false) { b -> Fuel.lh(b) },
        Module("cons_kml", "Consumo instantâneo", "km/L (GPS)", 1, true, false) { b -> Fuel.kml(b) },
        Module("avg5_kml", "Consumo médio (5 min)", "km/L", 1, true, false) { _ -> Fuel.avg5Kml() },
        Module("avg5_lh", "Consumo médio (5 min)", "L/h", 2, true, false) { _ -> Fuel.avg5Lh() },
        Module("trip_kml", "Média da viagem", "km/L", 1, true, false) { _ -> Fuel.avgKml() },
        // estado da sonda dianteira nos últimos ~10 s: 2 = oscilando OK, 1 = rica, 0 = pobre, -1 = parada
        // mistura: média da correção da riqueza (PR035) em 30 s; + = central adicionando combustível
        Module("mistura", "Mistura", "correção média 30 s (+ = central adicionando combustível)", 0, false, false,
            text = { LambdaWatch.mixLabel(it) }) { b -> LambdaWatch.mix(b) },
        Module("lambda_st", "Sonda lambda (estado)", "últimos 12 s, motor quente", 0, false, false,
            text = { LambdaWatch.label(it) }) { b -> LambdaWatch.state(b) },
        Module("trip_l", "Combustível gasto", "L (viagem)", 2, true, false) { _ -> Fuel.tripL },
        Module("trip_km", "Distância", "km (viagem, GPS)", 1, true, false) { _ -> Fuel.tripKm },
    )

    /** Bytes já usados por algum valor identificado. */
    val usedBytes: Set<Pair<Int, Int>> = RenaultMap.SIGNALS.flatMap { s ->
        (0 until s.len).map { s.lid to s.start + it }
    }.toSet()

    /** Byte ainda sem significado conhecido, mostrado em valor bruto (0-255). */
    fun raw(lid: Int, i: Int) = Module(
        "raw:%02X:%d".format(lid, i), "21%02X byte %d".format(lid, i), "bruto (0-255)", 0, true, true
    ) { b -> b[lid]?.let { if (i < it.size) (it[i].toInt() and 0xFF).toDouble() else null } }

    private val RAW = Regex("raw:([0-9A-F]{2}):(\\d+)")

    fun byKey(k: String): Module? =
        known.firstOrNull { it.key == k } ?: RAW.matchEntire(k)?.let {
            raw(it.groupValues[1].toInt(16), it.groupValues[2].toInt())
        }
}

/** Configuração do Painel: quais cards, em que ordem e se são grandes. Salva nas preferências. */
data class CardCfg(val key: String, var big: Boolean)

object DashConfig {
    private const val PREF = "dash_v1"

    fun default(): MutableList<CardCfg> =
        Modules.known.filter {
            it.key !in setOf("pedal1", "pedal2", "st_inj", "st_lenta", "st_pe", "pedal_aprend", "trip_l", "trip_km",
                "lambda2", "knock", "avg5_lh")
        }
            .map { CardCfg(it.key, it.key == "rpm") }.toMutableList()

    fun load(ctx: Context): MutableList<CardCfg> {
        val s = ctx.getSharedPreferences("cfg", 0).getString(PREF, null) ?: return default()
        val list = s.split(",").mapNotNull { e ->
            val p = e.split(":big=")
            if (p.size == 2 && Modules.byKey(p[0]) != null) CardCfg(p[0], p[1] == "1") else null
        }
        return if (list.isEmpty()) default() else list.toMutableList()
    }

    fun save(ctx: Context, list: List<CardCfg>) {
        val s = list.joinToString(",") { "${it.key}:big=${if (it.big) 1 else 0}" }
        ctx.getSharedPreferences("cfg", 0).edit().putString(PREF, s).apply()
    }
}

/** Gráfico simples de até 2 séries, cada uma com sua própria escala (últimos N segundos). */
class GraphView(ctx: Context) : View(ctx) {
    class Series(val name: String, val unit: String, val dec: Int, val points: List<Pair<Long, Double>>, val color: Int)

    var series: List<Series> = emptyList()
        set(v) { field = v; invalidate() }
    var windowSec = 60

    private val br = Locale("pt", "BR")
    private val d = ctx.resources.displayMetrics.density
    private val sd = ctx.resources.displayMetrics.scaledDensity
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val grid = Paint().apply { color = Color.parseColor("#2C3138"); strokeWidth = 1f * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * sd }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.parseColor("#1E2228"))
        val w = width.toFloat()
        val h = height.toFloat()
        val top = 48f * d
        val bottom = h - 12f * d
        for (k in 0..4) {
            val y = top + (bottom - top) * k / 4
            c.drawLine(0f, y, w, y, grid)
        }
        if (series.isEmpty()) {
            txt.color = Color.parseColor("#9AA4AE")
            c.drawText("Escolha um valor para o gráfico.", 8f * d, h / 2, txt)
            return
        }
        val now = System.currentTimeMillis()
        val t0 = now - windowSec * 1000L
        series.forEachIndexed { idx, s ->
            val pts = s.points.filter { it.first >= t0 }
            line.color = s.color
            txt.color = s.color
            val last = pts.lastOrNull()?.second
            val label = "${s.name}: " + (last?.let { String.format(br, "%.${s.dec}f", it) } ?: "--") + " ${s.unit}"
            if (pts.size < 2) {
                c.drawText(label, 8f * d, (18f + idx * 18f) * d, txt); return@forEachIndexed
            }
            var mn = pts.minOf { it.second }
            var mx = pts.maxOf { it.second }
            if (mx - mn < 1e-6) { mn -= 1; mx += 1 }
            val pad = (mx - mn) * 0.08
            mn -= pad; mx += pad
            val path = Path()
            pts.forEachIndexed { i, (t, v) ->
                val x = w * (t - t0) / (windowSec * 1000f)
                val y = (bottom - (bottom - top) * ((v - mn) / (mx - mn))).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, line)
            val range = " [" + String.format(br, "%.${s.dec}f", mn + pad) + " – " +
                    String.format(br, "%.${s.dec}f", mx - pad) + "]"
            c.drawText(label + range, 8f * d, (18f + idx * 18f) * d, txt)
        }
    }
}
