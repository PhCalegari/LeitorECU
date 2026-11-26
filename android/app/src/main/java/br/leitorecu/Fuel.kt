package br.leitorecu

import android.content.Context
import android.content.SharedPreferences

/** Última velocidade do GPS (preenchida pela aba Pista). */
object Gps {
    @Volatile var kmh: Double? = null
    @Volatile var at = 0L

    fun speed(): Double? = if (System.currentTimeMillis() - at < 3000) kmh else null
}

/**
 * Consumo calculado pelo tempo de injeção da ECU:
 *   pulsos/min = rotação/2 × 4 injetores (injeção sequencial: 1 pulso por cilindro a cada 2 voltas)
 *   cc/min     = pulsos/min × vazão do injetor (cc/min) × (tempo de injeção − tempo morto) / 60000
 * Com o corte de injeção ativo (bit "injeção ativa" = 0), o consumo é 0.
 * A vazão real do injetor do D4D não é publicada: começa num valor típico e é corrigida
 * por um fator calibrado no abastecimento (litros reais ÷ litros calculados).
 */
object Fuel {
    private const val INJ_FLOW_CC_MIN = 110.0   // vazão típica de injetor de motor 1.0 16V (3 bar)
    private const val DEAD_MS = 0.8             // tempo morto típico do injetor
    private const val CYL = 4

    private var prefs: SharedPreferences? = null
    var factor = 1.0; private set
    var tripL = 0.0; private set
    var tripKm = 0.0; private set
    private var lastTick = 0L
    private var lastSave = 0L

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("fuel", 0)
        prefs = p
        factor = p.getFloat("factor", 1f).toDouble()
        tripL = p.getFloat("tripL", 0f).toDouble()
        tripKm = p.getFloat("tripKm", 0f).toDouble()
    }

    private fun sig(key: String, b: Map<Int, ByteArray>) =
        RenaultMap.SIGNALS.firstOrNull { it.key == key }?.read(b)

    /** Litros por hora agora (null se faltam dados). */
    fun lh(b: Map<Int, ByteArray>): Double? {
        val rpm = sig("rpm", b) ?: return null
        val inj = sig("inj", b) ?: return null
        if (rpm < 100) return 0.0
        if (sig("st_inj", b) == 0.0) return 0.0            // corte de injeção na desaceleração
        val tEff = (inj - DEAD_MS).coerceAtLeast(0.0)
        val pulsesMin = rpm / 2.0 * CYL
        val ccMin = pulsesMin * INJ_FLOW_CC_MIN * tEff / 60000.0
        return ccMin * 60.0 / 1000.0 * factor
    }

    /** km/L instantâneo (precisa do GPS ligado e o carro andando). */
    fun kml(b: Map<Int, ByteArray>): Double? {
        val v = Gps.speed() ?: SpeedMap.read(b) ?: return null
        val l = lh(b) ?: return null
        if (v < 5) return null
        if (l < 0.02) return 99.9                            // corte de injeção: consumo ~zero
        return (v / l).coerceAtMost(99.9)
    }

    fun avgKml(): Double? = if (tripL > 0.01 && tripKm > 0.1) tripKm / tripL else null

    // média móvel dos últimos 5 minutos: (hora, litros, km) de cada ciclo
    private val window = ArrayDeque<Triple<Long, Double, Double>>()
    private const val WINDOW_MS = 5 * 60 * 1000L

    /** km/L médio dos últimos 5 min (precisa de pelo menos ~0,3 km rodados na janela). */
    fun avg5Kml(): Double? {
        val l = window.sumOf { it.second }
        val km = window.sumOf { it.third }
        return if (km > 0.3 && l > 0.005) (km / l).coerceAtMost(99.9) else null
    }

    /** L/h médio dos últimos 5 min. */
    fun avg5Lh(): Double? {
        if (window.size < 2) return null
        val h = (window.last().first - window.first().first) / 3_600_000.0
        return if (h > 0) window.sumOf { it.second } / h else null
    }

    /** Acumula litros e km a cada ciclo de leitura. */
    fun tick(b: Map<Int, ByteArray>) {
        val now = System.currentTimeMillis()
        val dtH = if (lastTick == 0L) 0.0 else ((now - lastTick) / 3_600_000.0).coerceAtMost(10.0 / 3600)
        lastTick = now
        val dL = (lh(b) ?: 0.0) * dtH
        val dKm = ((Gps.speed() ?: SpeedMap.read(b)) ?: 0.0) * dtH
        tripL += dL; tripKm += dKm
        window.addLast(Triple(now, dL, dKm))
        while (window.isNotEmpty() && now - window.first().first > WINDOW_MS) window.removeFirst()
        if (now - lastSave > 10_000) save()
    }

    fun resetTrip() { tripL = 0.0; tripKm = 0.0; save() }

    /** Corrige o fator com o abastecimento: litros que entraram ÷ litros que o app calculou. */
    fun calibrate(realLiters: Double): Boolean {
        if (tripL < 1.0 || realLiters <= 0) return false
        factor *= realLiters / tripL
        tripL = realLiters
        save(); return true
    }

    fun resetFactor() { factor = 1.0; save() }

    private fun save() {
        lastSave = System.currentTimeMillis()
        prefs?.edit()?.putFloat("factor", factor.toFloat())?.putFloat("tripL", tripL.toFloat())
            ?.putFloat("tripKm", tripKm.toFloat())?.apply()
    }
}
