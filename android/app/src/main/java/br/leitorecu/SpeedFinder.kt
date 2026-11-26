package br.leitorecu

import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.sqrt

/** Mapeamento da velocidade dentro da ECU (descoberto automaticamente). */
object SpeedMap {
    @Volatile var lid = 0; @Volatile var start = 0; @Volatile var len = 0
    @Volatile var scale = 1.0; @Volatile var offset = 0.0
    val known get() = len > 0

    fun read(b: Map<Int, ByteArray>): Double? {
        if (!known) return null
        val d = b[lid] ?: return null
        if (d.size < start + len) return null
        var raw = 0
        for (i in 0 until len) raw = (raw shl 8) or (d[start + i].toInt() and 0xFF)
        return (raw * scale + offset).coerceAtLeast(0.0)
    }

    fun load(p: SharedPreferences) {
        val s = p.getString("speed_map", null) ?: return
        val f = s.split(":")
        if (f.size == 5) {
            lid = f[0].toInt(); start = f[1].toInt(); len = f[2].toInt()
            scale = f[3].toDouble(); offset = f[4].toDouble()
        }
    }

    fun save(p: SharedPreferences) = p.edit().putString("speed_map", "$lid:$start:$len:$scale:$offset").apply()
    fun clear(p: SharedPreferences) { len = 0; p.edit().remove("speed_map").apply() }
    fun describe() = if (!known) "não encontrada" else
        "21%02X byte %d%s × %.4f %+.1f".format(lid, start, if (len == 2) "–${start + 1}" else "", scale, offset)
}

/**
 * Procura, entre todos os bytes (e pares de bytes) dos blocos 21A0..21A4, o que acompanha a velocidade
 * do GPS: regressão linear byte × velocidade; aceita correlação ≥ 0,98 com velocidades variadas.
 */
class SpeedFinder {
    private class Acc { var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0 }
    private val acc = HashMap<Triple<Int, Int, Int>, Acc>()   // (lid, byte, tamanho)
    private var vMin = 999.0
    private var vMax = 0.0
    var samples = 0; private set

    fun reset() { acc.clear(); samples = 0; vMin = 999.0; vMax = 0.0 }

    fun add(v: Double, blocks: Map<Int, ByteArray>) {
        if (v < 3 || v > 200) return
        samples++; vMin = minOf(vMin, v); vMax = maxOf(vMax, v)
        for ((lid, d) in blocks) for (i in d.indices) {
            put(Triple(lid, i, 1), (d[i].toInt() and 0xFF).toDouble(), v)
            if (i + 1 < d.size) put(Triple(lid, i, 2), (((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)).toDouble(), v)
        }
    }

    private fun put(k: Triple<Int, Int, Int>, x: Double, y: Double) {
        val a = acc.getOrPut(k) { Acc() }
        a.n++; a.sx += x; a.sy += y; a.sxx += x * x; a.syy += y * y; a.sxy += x * y
    }

    data class Result(val lid: Int, val start: Int, val len: Int, val r: Double, val scale: Double, val offset: Double)

    /** Melhor candidato (r ≥ 0,95), ou null se ainda não há dados suficientes / nenhum confiável. */
    fun best(minR: Double = 0.95): Result? {
        if (samples < 20 || vMax - vMin < 20) return null
        var best: Result? = null
        for ((k, a) in acc) {
            if (a.n < 15) continue
            val vx = a.n * a.sxx - a.sx * a.sx
            val vy = a.n * a.syy - a.sy * a.sy
            if (vx <= 0 || vy <= 0) continue
            val r = (a.n * a.sxy - a.sx * a.sy) / sqrt(vx * vy)
            if (r < minR) continue
            val slope = (a.n * a.sxy - a.sx * a.sy) / vx
            val off = (a.sy - slope * a.sx) / a.n
            if (abs(off) > 8) continue                 // velocidade 0 deve dar ~0 no byte
            // empate: prefere 8 bits e escala "redonda" (1, 0,5, 0,1, 0,01...)
            val cand = Result(k.first, k.second, k.third, r, slope, off)
            if (best == null || r > best.r + 0.002 || (abs(r - best.r) <= 0.002 && k.third < best.len)) best = cand
        }
        return best
    }

    fun progress(): String = when {
        samples == 0 -> "Procurando a velocidade na central: dirija com GPS ligado e a central conectada."
        samples < 20 || vMax - vMin < 20 ->
            "Procurando a velocidade na central… $samples amostras (precisa variar ~20 km/h ou mais)."
        else -> best()?.let { "Encontrada: 21%02X byte %d (r = %.3f)".format(it.lid, it.start, it.r) }
            ?: best(0.0)?.let {
                "Ainda procurando ($samples amostras). Melhor até agora: 21%02X byte %d, r = %.2f (precisa ≥ 0,95)."
                    .format(it.lid, it.start, it.r)
            } ?: "Nenhum byte acompanha o GPS ainda ($samples amostras)."
    }
}
