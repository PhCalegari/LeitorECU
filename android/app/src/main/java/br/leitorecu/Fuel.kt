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
 * Bico: Magneti Marelli IWP099 = Bosch 0280158168 (ref. Renault 8200025248/8200051963): 76,8 g/min de n-heptano a 3 bar
 * ≈ 112 cc/min de heptano ≈ 108 cc/min de gasolina C (mais densa: vazão em volume cai com √densidade).
 * Pressão: D4D sem retorno, 3,5 bar (±0,5) em relação à atmosfera; no bico a diferença é 3,5 + (P.atm − coletor),
 * ~4,1 bar na lenta. Vazão ∝ √(pressão). Tempo morto de bico tipo EV14 a ~14 V: ~0,5 ms.
 * Conferência: lenta 760 rpm, 4,0 ms, 350 mbar → ~0,67 L/h (típico real 0,6–0,8 L/h).
 * Com o corte de injeção de verdade, o consumo é 0. O fator do abastecimento ajusta o que sobrar.
 */
object Fuel {
    private const val FLOW_3BAR_CC_MIN = 108.0  // gasolina a 3 bar (ver acima)
    private const val RAIL_BAR = 3.5            // pressão da bomba/regulador (sem retorno)
    private const val PATM_BAR = 0.95           // pressão atmosférica de referência (Curitiba ~0,91; nível do mar 1,01)
    private const val DEAD_MS = 0.5             // tempo morto do bico
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
        // conta nova com a vazão/pressão/tempo morto reais do bico (uma vez): o fator provisório de 1,15 volta a 1,0
        // (a física nova já explica a diferença); quem calibrou no abastecimento mantém o seu fator.
        // A viagem recomeça do zero com a conta nova.
        if (!p.getBoolean("factor_v3", false)) {
            if (kotlin.math.abs(factor - 1.15) < 0.001) factor = 1.0
            tripL = 0.0; tripKm = 0.0
            p.edit().putFloat("factor", factor.toFloat()).putFloat("tripL", 0f).putFloat("tripKm", 0f)
                .putBoolean("factor_v2", true).putBoolean("factor_v3", true).apply()
        }
    }

    private fun sig(key: String, b: Map<Int, ByteArray>) =
        RenaultMap.SIGNALS.firstOrNull { it.key == key }?.read(b)

    /** Litros por hora agora (null se faltam dados). */
    fun lh(b: Map<Int, ByteArray>): Double? {
        val rpm = sig("rpm", b) ?: return null
        val inj = sig("inj", b) ?: return null
        if (rpm < 100) return 0.0
        if (RenaultMap.fuelCut(b)) return 0.0                // corte de injeção de verdade (injeção ~0 ms)
        val tEff = (inj - DEAD_MS).coerceAtLeast(0.0)
        // pressão no bico: 3,5 bar da bomba + o vácuo do coletor (sistema sem retorno)
        val mapBar = sig("map", b)?.takeIf { it in 150.0..1100.0 }?.div(1000.0) ?: PATM_BAR
        val dp = RAIL_BAR + (PATM_BAR - mapBar)
        val flow = FLOW_3BAR_CC_MIN * kotlin.math.sqrt(dp / 3.0)
        val pulsesMin = rpm / 2.0 * CYL
        val ccMin = pulsesMin * flow * tEff / 60000.0
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
