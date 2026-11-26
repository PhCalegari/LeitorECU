package br.leitorecu

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private fun sig(k: String, b: Map<Int, ByteArray>) = RenaultMap.SIGNALS.firstOrNull { it.key == k }?.read(b)
private fun median(l: List<Double>): Double? {
    if (l.isEmpty()) return null
    val s = l.sorted(); val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
}
private val BR = Locale("pt", "BR")
private fun f(v: Double, dec: Int) = String.format(BR, "%.${dec}f", v)

/**
 * NÍVEL 1 – o app aprende comparando a central com coisas que ele sabe:
 *  - eventos marcados pelo usuário (ligou o ar, ventoinha, farol...): procura o bit/byte que mudou junto;
 *  - ventoinha: temperatura da água quando ela liga (deveria ser ~99 °C) → confere a escala;
 *  - partida a frio (carro parado há horas): água e ar devem marcar a mesma temperatura (ambiente);
 *  - bytes "lentos": constantes durante a viagem, mas mudando de um dia para outro (adaptações da central).
 */
object Learner {
    val LABELS = listOf("Ar-condicionado", "Ventoinha", "Farol", "Freio", "Vidro térmico", "Embreagem")
    private const val FAN = "Ventoinha"

    private var p: SharedPreferences? = null
    private val buf = ArrayDeque<Pair<Long, Map<Int, ByteArray>>>()   // últimos ~20 s

    // ---------- eventos ----------
    /** Resultado de um evento: para cada rótulo, quantas vezes cada candidato mudou junto. */
    class Cand(val key: String, val hits: Int, val events: Int) {
        val lid get() = key.substring(0, 2).toInt(16)
        val byte get() = key.split(":")[1].toInt()
        val bit get() = key.split(":")[2].let { if (it.startsWith("b")) it.substring(1).toInt() else -1 }
        val isBit get() = bit >= 0
        val confirmed get() = hits >= 2 && hits >= events * 0.75
        fun describe() = "21%02X byte %d".format(lid, byte) + (if (isBit) " bit $bit" else " (valor)") + " – $hits/$events vezes"
    }

    /** [after] = o usuário marcou DEPOIS que aconteceu (ventoinha): compara com o que havia antes do toque. */
    private data class Pending(val label: String, val t: Long, val after: Boolean) {
        val readyAt get() = t + if (after) 4500 else 6000
    }
    private val pending = ArrayList<Pending>()
    var lastMsg = ""; private set

    /** Bytes de valores analógicos já conhecidos (não entram como candidatos de evento). */
    private val analog: Set<Pair<Int, Int>> = RenaultMap.SIGNALS.filter { !it.key.startsWith("st_") }
        .flatMap { s -> (0 until s.len).map { s.lid to s.start + it } }.toSet()

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("learn", 0)
        analogSaved.clear()
        p?.getString("analog", "")?.split(",")?.filter { it.isNotBlank() }?.let { analogSaved.addAll(it) }
    }

    /** Ventoinha: o usuário não controla quando muda, então marca na hora (sem contagem). */
    fun isAfterEvent(label: String) = label == FAN

    /** A ação aconteceu no instante [t] (fim da contagem) ou pouco antes (eventos marcados depois). */
    fun mark(label: String, t: Long) {
        val after = isAfterEvent(label)
        pending.add(Pending(label, t, after))
        if (label == FAN) {
            // guarda a água só se ela está no pico dos últimos 3 min (= ventoinha LIGANDO, não desligando)
            val w = waterHist.lastOrNull()?.second
            if (w != null && w >= waterHist.maxOf { it.second } - 0.7 && w > 85) addFanTemp(w, false)
        }
        lastMsg = "Analisando “$label”… aguarde ${if (after) 5 else 6} s sem mexer em mais nada."
    }

    fun events(label: String) = p?.getInt("ev_$label", 0) ?: 0

    /** Valores diferentes já vistos em cada byte (até 9) – separa bits de estado de valores analógicos. */
    private val distinct = HashMap<Pair<Int, Int>, HashSet<Int>>()
    /** Bytes já vistos como analógicos (salvo: vale logo ao abrir o app, antes de juntar valores de novo). */
    private val analogSaved = HashSet<String>()
    private fun analogLike(lid: Int, i: Int) =
        (distinct[lid to i]?.size ?: 0) > 8 || "%02X:%d".format(lid, i) in analogSaved

    private fun noteValue(lid: Int, i: Int, v: Int) {
        val s = distinct.getOrPut(lid to i) { HashSet() }
        if (s.size > 8) return
        s.add(v)
        if (s.size > 8 && analogSaved.add("%02X:%d".format(lid, i)))
            p?.edit()?.putString("analog", analogSaved.joinToString(","))?.apply()
    }

    fun cands(label: String): List<Cand> {
        val n = events(label)
        val s = p?.getString("cand_$label", "") ?: ""
        return s.split(",").mapNotNull { e ->
            val i = e.lastIndexOf('=')
            if (i <= 0) null else Cand(e.substring(0, i), e.substring(i + 1).toIntOrNull() ?: 0, n)
        }.filter { !analogLike(it.lid, it.byte) }   // descobriu depois que o byte é analógico: descarta
            .sortedWith(compareByDescending<Cand> { it.hits }.thenBy { if (it.isBit) 0 else 1 })
    }

    /** Bit confirmado para o rótulo (para mostrar no Painel), ou null. */
    fun learnedBit(label: String) = cands(label).firstOrNull { it.isBit && it.confirmed }

    fun resetEvents() {
        val e = p?.edit() ?: return
        LABELS.forEach { e.remove("ev_$it"); e.remove("cand_$it") }
        e.apply()
    }

    private fun window(t0: Long, t1: Long) = buf.filter { it.first in t0..t1 }.map { it.second }

    private fun evaluate(ev: Pending) {
        // marcado depois (ventoinha): mudou ~1-3 s antes do toque → antes = 7..3 s antes, depois = do toque em diante
        val before = if (ev.after) window(ev.t - 7000, ev.t - 3000) else window(ev.t - 3500, ev.t + 300)
        val after = if (ev.after) window(ev.t, ev.t + 4500) else window(ev.t + 2000, ev.t + 6000)
        if (before.size < 2 || after.size < 2) {
            lastMsg = "“${ev.label}”: poucos dados (a central estava lendo devagar). Tente de novo."
            return
        }
        val found = ArrayList<String>()
        val lids = before.last().keys intersect after.last().keys
        for (lid in lids) {
            val n = minOf(before.minOf { it[lid]?.size ?: 0 }, after.minOf { it[lid]?.size ?: 0 })
            for (i in 0 until n) {
                val bv = before.mapNotNull { it[lid]?.get(i)?.toInt()?.and(0xFF) }
                val av = after.mapNotNull { it[lid]?.get(i)?.toInt()?.and(0xFF) }
                // byte com muitos valores diferentes = valor analógico ainda sem nome (ex.: 21A2 byte 19 tem 24
                // valores numa captura): os bits dele mudam sempre que a rotação/carga muda → não é bit de estado
                if (analogLike(lid, i)) continue
                // bits: estáveis antes e depois, e diferentes entre os dois
                if ((lid to i) !in analog) {
                    for (bit in 0..7) {
                        val bb = bv.map { (it shr bit) and 1 }.toSet()
                        val ab = av.map { (it shr bit) and 1 }.toSet()
                        if (bb.size == 1 && ab.size == 1 && bb != ab) found.add("%02X:%d:b%d".format(lid, i, bit))
                    }
                }
                // byte inteiro: degrau claro (estável dos dois lados, diferença ≥ 4)
                if ((lid to i) !in analog && bv.max() - bv.min() <= 2 && av.max() - av.min() <= 2 &&
                    abs(av.average() - bv.average()) >= 4) found.add("%02X:%d:B".format(lid, i))
            }
        }
        val n = events(ev.label) + 1
        val old = cands(ev.label).associate { it.key to it.hits }.toMutableMap()
        found.forEach { old[it] = (old[it] ?: 0) + 1 }
        // guarda só os 25 mais frequentes
        val keep = old.entries.sortedByDescending { it.value }.take(25)
        p?.edit()?.putInt("ev_${ev.label}", n)
            ?.putString("cand_${ev.label}", keep.joinToString(",") { "${it.key}=${it.value}" })?.apply()
        val top = cands(ev.label).firstOrNull()
        lastMsg = when {
            found.isEmpty() -> "“${ev.label}” (${n}ª vez): nada mudou junto. Pode ser que a central não receba essa informação."
            top != null && top.confirmed -> "“${ev.label}”: aprendido! ${top.describe()}."
            else -> "“${ev.label}” (${n}ª vez): ${found.size} candidato(s). Repita mais ${maxOf(1, 2 - (top?.hits ?: 0))} vez(es) para confirmar."
        }
    }

    // ---------- ventoinha ----------
    fun fanTemps(manual: Boolean): List<Double> =
        (p?.getString(if (manual) "fan_m" else "fan_a", "") ?: "").split(",").mapNotNull { it.toDoubleOrNull() }

    private fun addFanTemp(t: Double, auto: Boolean) {
        val k = if (auto) "fan_a" else "fan_m"
        val l = (fanTemps(!auto) + t).takeLast(15)
        p?.edit()?.putString(k, l.joinToString(",") { f(it, 1).replace(',', '.') })?.apply()
    }

    private var fanBitLast: Int? = null
    private var lastAutoFan = 0L
    /** Água dos últimos 3 min: a ventoinha LIGA no pico; quando desliga, a água já caiu alguns graus. */
    private val waterHist = ArrayDeque<Pair<Long, Double>>()
    private fun atPeak(w: Double) = waterHist.isNotEmpty() && w >= waterHist.maxOf { it.second } - 0.7

    private fun watchFan(b: Map<Int, ByteArray>, now: Long) {
        val water = sig("agua", b) ?: return
        waterHist.addLast(now to water)
        while (waterHist.isNotEmpty() && now - waterHist.first().first > 180_000) waterHist.removeFirst()
        // 1) já sabe o bit da ventoinha: registra a água em cada vez que ela LIGA (mudança no pico de temperatura)
        learnedBit(FAN)?.let { c ->
            val d = b[c.lid] ?: return@let
            if (c.byte >= d.size) return@let
            val v = (d[c.byte].toInt() shr c.bit) and 1
            val prev = fanBitLast
            fanBitLast = v
            if (prev != null && prev != v && water > 85 && atPeak(water) && now - lastAutoFan > 60_000) {
                lastAutoFan = now
                addFanTemp(water, true)
            }
            return
        }
        // 2) sem o bit: queda brusca da bateria em marcha lenta com o motor bem quente (estimativa)
        if (water < 92 || buf.size < 8) return
        val rpm = sig("rpm", b) ?: return
        if (rpm !in 600.0..1200.0) return
        val recent = buf.toList().takeLast(8).mapNotNull { sig("bat", it.second) }
        if (recent.size < 8) return
        val a = recent.take(4).average(); val c = recent.takeLast(3).average()
        if (a - c >= 0.15 && atPeak(water) && now - lastAutoFan > 120_000) { lastAutoFan = now; addFanTemp(water, true) }
    }

    fun fanReport(): String {
        val m = fanTemps(true); val a = fanTemps(false)
        val all = m + a
        if (all.isEmpty()) return "Ainda sem registro. Deixe o carro em lenta até a ventoinha ligar e toque em “Ventoinha” " +
                "assim que ouvir ela ligar (2 a 3 vezes, sem contagem). A ventoinha do D4D liga a 99 °C."
        val med = median(all)!!
        val verdict = when {
            abs(med - 99) <= 3 -> "✓ escala da temperatura da água confere (esperado 99 °C)."
            med < 99 -> "⚠ marca ${f(99 - med, 0)} °C a menos que o esperado: a escala da água pode estar errada."
            else -> "⚠ marca ${f(med - 99, 0)} °C a mais que o esperado: a escala da água pode estar errada."
        }
        return "Ventoinha ligou com a água em ${f(med, 0)} °C (${all.size} vez(es)" +
                (if (a.isNotEmpty() && m.isEmpty()) ", detecção automática" else "") + "). $verdict"
    }

    // ---------- partida a frio ----------
    /** Primeira leitura depois de 3 h ou mais sem dados (mesmo com o app aberto a noite toda). */
    private fun coldStart(b: Map<Int, ByteArray>, now: Long) {
        val pr = p ?: return
        val last = pr.getLong("last_seen", 0L)
        if (last > 0 && now - last > 3 * 3_600_000L) {
            val water = sig("agua", b); val air = sig("ar", b)
            if (water != null && air != null) {
                val l = (coldList() + "${f(water, 1)}/${f(air, 1)}/${(now - last) / 3_600_000}").takeLast(10)
                pr.edit().putString("cold", l.joinToString(";")).putLong("last_seen", now).apply()
                return
            }
        }
        if (now - last > 30_000) pr.edit().putLong("last_seen", now).apply()
    }

    private fun coldList() = (p?.getString("cold", "") ?: "").split(";").filter { it.isNotBlank() }

    fun coldReport(): String {
        val l = coldList()
        if (l.isEmpty()) return "Ainda sem registro. Na próxima vez que o carro ficar parado 3 h ou mais (ex.: de manhã), " +
                "abra o app e conecte ANTES de ligar o motor."
        val sb = StringBuilder()
        val diffs = ArrayList<Double>()
        for (e in l.takeLast(4)) {
            val q = e.split("/"); if (q.size < 3) continue
            val w = q[0].replace(',', '.').toDouble(); val a = q[1].replace(',', '.').toDouble()
            diffs.add(w - a)
            sb.append("• água ${f(w, 0)} °C, ar ${f(a, 0)} °C (parado ${q[2]} h)\n")
        }
        val d = median(diffs) ?: 0.0
        sb.append(if (abs(d) <= 5) "✓ água e ar batem entre si. Confira se parece com a temperatura do dia."
        else "⚠ diferença de ${f(d, 0)} °C entre água e ar com o motor frio: uma das duas escalas pode estar errada.")
        return sb.toString()
    }

    // ---------- bytes lentos (adaptações) ----------
    private val sMin = HashMap<Pair<Int, Int>, Int>()
    private val sMax = HashMap<Pair<Int, Int>, Int>()
    private val sLast = HashMap<Pair<Int, Int>, Int>()
    private var sCount = 0
    private var lastSlowSave = 0L
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun slowBytes(b: Map<Int, ByteArray>, now: Long) {
        val water = sig("agua", b) ?: return
        val rpm = sig("rpm", b) ?: return
        if (water < 75 || rpm < 500) return
        sCount++
        for ((lid, d) in b) for (i in d.indices) {
            val k = lid to i; val v = d[i].toInt() and 0xFF
            sMin[k] = minOf(sMin[k] ?: v, v); sMax[k] = maxOf(sMax[k] ?: v, v); sLast[k] = v
        }
        if (sCount >= 100 && now - lastSlowSave > 60_000) { lastSlowSave = now; saveSlow(now) }
    }

    private fun saveSlow(now: Long) {
        val pr = p ?: return
        val today = day.format(Date(now))
        val old = parseDay(pr.getString("slow_$today", "") ?: "").toMutableMap()
        for ((k, v) in sLast) {
            val constant = sMax[k] == sMin[k]   // exatamente fixo na viagem
            val key = "%02X:%d".format(k.first, k.second)
            if (!constant) { old[key] = -1; continue }
            val prev = old[key]
            old[key] = if (prev == null || prev == v) v else -1   // mudou no mesmo dia: não é "lento"
        }
        val days = (pr.getString("slow_days", "") ?: "").split(",").filter { it.isNotBlank() }.toMutableList()
        if (today !in days) days.add(today)
        val e = pr.edit()
        while (days.size > 30) e.remove("slow_" + days.removeAt(0))
        e.putString("slow_days", days.joinToString(","))
            .putString("slow_$today", old.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()
    }

    private fun parseDay(s: String) = s.split(",").mapNotNull { e ->
        val i = e.indexOf('='); if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toIntOrNull() ?: -1)
    }.toMap()

    fun slowReport(): String {
        val pr = p ?: return ""
        val days = (pr.getString("slow_days", "") ?: "").split(",").filter { it.isNotBlank() }
        if (days.size < 2) return "Aprendendo (${days.size}/2 dias com o motor quente). Os bytes que ficam fixos durante a " +
                "viagem mas mudam de um dia para outro costumam ser adaptações da central (ex.: correção aprendida da mistura)."
        val maps = days.map { it to parseDay(pr.getString("slow_$it", "") ?: "") }
        val keys = maps.flatMap { it.second.keys }.toSet()
        val lines = ArrayList<String>()
        for (k in keys.sortedWith(compareBy({ it.substring(0, 2) }, { it.substringAfter(":").toInt() }))) {
            val seq = maps.mapNotNull { (d, m) -> m[k]?.takeIf { it >= 0 }?.let { d.substring(8) + "/" + d.substring(5, 7) to it } }
            if (seq.size < 2 || seq.map { it.second }.toSet().size < 2) continue
            val lid = k.substring(0, 2).toInt(16); val i = k.substringAfter(":").toInt()
            val known = RenaultMap.SIGNALS.firstOrNull { s -> s.lid == lid && i in s.start until s.start + s.len }
            val name = known?.let { " (${it.name})" } ?: ""
            lines.add("• 21%02X byte %d$name: ".format(lid, i) + seq.takeLast(5).joinToString(" → ") { "${it.second} (${it.first})" })
        }
        return if (lines.isEmpty()) "Nenhum byte lento mudou entre os ${days.size} dias registrados ainda."
        else "Mudaram entre dias (${days.size} dias registrados):\n" + lines.take(12).joinToString("\n")
    }

    fun resetAll() {
        // mantém a lista de bytes analógicos (não é "aprendizado" do usuário, é como a central se comporta)
        p?.edit()?.clear()?.putString("analog", analogSaved.joinToString(","))?.apply()
        sMin.clear(); sMax.clear(); sLast.clear(); sCount = 0; lastMsg = ""
    }

    // ---------- entrada ----------
    fun feed(b: Map<Int, ByteArray>, now: Long) {
        buf.addLast(now to b.mapValues { it.value.copyOf() })
        for ((lid, d) in b) for (i in d.indices) noteValue(lid, i, d[i].toInt() and 0xFF)
        while (buf.isNotEmpty() && now - buf.first().first > 20_000) buf.removeFirst()
        val ready = pending.filter { now >= it.readyAt }
        pending.removeAll(ready)
        ready.forEach { evaluate(it) }
        watchFan(b, now)
        coldStart(b, now)
        slowBytes(b, now)
    }
}

/**
 * NÍVEL 2 – aprende o "normal" do SEU carro (mediana de cada viagem) e avisa quando algo foge do normal.
 * Precisa de pelo menos 3 viagens para começar a comparar.
 */
object Health {
    class Metric(
        val key: String, val name: String, val unit: String, val dec: Int,
        val tol: Double, val relative: Boolean, val minN: Int,
        val high: String, val low: String,
    )

    val METRICS = listOf(
        Metric("idle_rpm", "Marcha lenta (motor quente)", "rpm", 0, 100.0, false, 20,
            "lenta mais alta que o normal: ar-condicionado ligado, entrada de ar falso ou borboleta suja",
            "lenta mais baixa que o normal: borboleta suja, vela/bobina ou motor de passo"),
        Metric("idle_inj", "Injeção na lenta (quente)", "ms", 2, 0.15, true, 20,
            "injetando mais na lenta que o normal: carga extra (ar), falha de vela/bobina ou entrada de ar falso",
            "injetando menos na lenta que o normal: confira a pressão de combustível e a sonda"),
        Metric("mix", "Correção da mistura", "%", 0, 8.0, false, 20,
            "mistura mais pobre que o normal: entrada de ar falso, filtro/bomba de combustível ou bico sujo",
            "mistura mais rica que o normal: bico pingando, filtro de ar sujo ou sonda lambda"),
        Metric("run_temp", "Temperatura em uso", "°C", 0, 6.0, false, 60,
            "motor mais quente que o normal: nível da água, ventoinha ou válvula termostática",
            "motor mais frio que o normal: válvula termostática presa aberta"),
        Metric("bat_run", "Bateria com motor ligado", "V", 1, 0.4, false, 20,
            "tensão de carga mais alta que o normal: regulador do alternador",
            "tensão de carga mais baixa que o normal: alternador, correia ou cabos"),
        Metric("crank_min", "Bateria na partida (mínima)", "V", 1, 0.6, false, 1,
            "",
            "bateria caindo mais que o normal na partida: bateria fraca ou terminais"),
        Metric("warmup", "Tempo para esquentar (40→80 °C)", "min", 1, 0.5, true, 1,
            "demorando mais que o normal para esquentar: válvula termostática presa aberta",
            ""),
    )

    private var p: SharedPreferences? = null
    private val cur = HashMap<String, ArrayList<Double>>()
    private val lastAdd = HashMap<String, Long>()
    private var lastFeed = 0L
    private var lastSave = 0L

    // partida / aquecimento
    private var crankMin: Double? = null
    private var crankAt = 0L
    private var running = false
    private var runningSince = 0L
    private var warmT0 = 0L

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("health", 0)
        closeSession()   // a viagem anterior (salva em "cur") vai para o histórico
    }

    private fun history(k: String) =
        (p?.getString("h_$k", "") ?: "").split(",").mapNotNull { it.toDoubleOrNull() }

    fun baseline(m: Metric): Double? = history(m.key).let { if (it.size >= 3) median(it) else null }
    fun trips(m: Metric) = history(m.key).size

    fun current(m: Metric): Double? = cur[m.key]?.let { if (it.size >= m.minN) median(it) else null }

    private fun add(k: String, v: Double, now: Long, everyMs: Long = 1000) {
        if (now - (lastAdd[k] ?: 0L) < everyMs) return
        lastAdd[k] = now
        val l = cur.getOrPut(k) { ArrayList() }
        l.add(v)
        if (l.size > 3000) l.removeAt(0)
    }

    private fun closeSession() {
        val pr = p ?: return
        val saved = pr.getString("cur", "") ?: ""
        if (saved.isNotBlank()) {
            val e = pr.edit()
            saved.split(";").forEach { kv ->
                val q = kv.split("="); if (q.size != 2) return@forEach
                val v = q[1].toDoubleOrNull() ?: return@forEach
                val h = (history(q[0]) + v).takeLast(20)
                e.putString("h_${q[0]}", h.joinToString(",") { it.toString() })
            }
            e.remove("cur").apply()
        }
        cur.clear(); lastAdd.clear(); crankMin = null; running = false; warmT0 = 0L
    }

    private fun saveCur() {
        val s = METRICS.mapNotNull { m -> current(m)?.let { "${m.key}=$it" } }.joinToString(";")
        p?.edit()?.putString("cur", s)?.apply()
    }

    fun feed(b: Map<Int, ByteArray>, now: Long) {
        if (lastFeed > 0 && now - lastFeed > 5 * 60_000) { saveCur(); closeSession() }   // nova viagem
        lastFeed = now
        val rpm = sig("rpm", b) ?: return
        val water = sig("agua", b)
        val bat = sig("bat", b)
        val inj = sig("inj", b)
        val injOn = sig("st_inj", b) == 1.0
        val idleReg = sig("st_lenta", b) == 1.0
        val corr = sig("corr", b)

        // partida: menor tensão durante a partida (rotação de arranque) e início do aquecimento
        if (rpm in 30.0..450.0 && bat != null) { crankMin = minOf(crankMin ?: bat, bat); crankAt = now }
        if (!running && rpm > 600) {
            running = true
            runningSince = now
            crankMin?.let { if (now - crankAt < 10_000 && cur["crank_min"].isNullOrEmpty()) add("crank_min", it, now, 0) }
            if (water != null && water < 40) warmT0 = now
        }
        if (rpm < 200) running = false
        if (warmT0 > 0 && water != null && water >= 80) {
            add("warmup", (now - warmT0) / 60_000.0, now, 0); warmT0 = 0L
        }

        // ≥ 70 °C: nas capturas reais a malha fechada e a lenta de 750 rpm já estão ativas nessa faixa
        if (water != null && water > 70 && injOn) {
            if (idleReg && rpm < 1300) {
                add("idle_rpm", rpm, now)
                inj?.let { add("idle_inj", it, now) }
            }
            if (corr != null && corr != 128.0) add("mix", (corr - 128) * 100 / 128, now)
        }
        // só depois de 10 min rodando: no começo o motor ainda está esquentando (evita alarme falso de "mais frio")
        if (water != null && water > 75 && rpm > 1500 && running && now - runningSince > 10 * 60_000)
            add("run_temp", water, now)
        if (bat != null && rpm > 1500) add("bat_run", bat, now)

        if (now - lastSave > 60_000) { lastSave = now; saveCur() }
    }

    /** Diferença da viagem atual para o normal, ou null se ainda não dá para comparar / está normal. */
    fun alert(m: Metric): String? {
        val base = baseline(m) ?: return null
        val now = current(m) ?: return null
        val d = now - base
        val lim = if (m.relative) abs(base) * m.tol else m.tol
        if (abs(d) <= lim) return null
        val msg = if (d > 0) m.high else m.low
        return msg.ifBlank { null }
    }

    fun alerts(): List<String> = METRICS.mapNotNull { m -> alert(m)?.let { "⚠ ${it.replaceFirstChar { c -> c.uppercase() }}" } }

    fun line(m: Metric): String {
        val c = current(m); val base = baseline(m); val n = trips(m)
        val cs = c?.let { f(it, m.dec) + " " + m.unit } ?: "--"
        val bs = if (base != null) "normal ${f(base, m.dec)} ${m.unit} ($n viagens)" else "aprendendo ($n/3 viagens)"
        val mark = when {
            base == null || c == null -> ""
            alert(m) != null -> "  ⚠"
            else -> "  ✓"
        }
        return "${m.name}: agora $cs • $bs$mark"
    }

    fun reset() {
        p?.edit()?.clear()?.apply()
        cur.clear(); lastAdd.clear()
    }
}

/** Aba "Aprender": marcar eventos, ver o que o app aprendeu e a saúde do motor. */
class LearnPage(
    private val act: Activity,
    private val connected: () -> Boolean,
    private val addToDash: (String) -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val dens = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()
    private val muted = Color.parseColor("#9AA4AE")
    private val warn = Color.parseColor("#FFB300")
    private val green = Color.parseColor("#7CDB6A")
    private var tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (_: Exception) { null }

    private fun tv(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(act).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun btn(s: String, f: () -> Unit) = Button(act).apply {
        text = s; isAllCaps = false; setOnClickListener { f() }
        minWidth = 0; minimumWidth = 0; minHeight = dp(42); minimumHeight = dp(42)
        setPadding(dp(4), 0, dp(4), 0); textSize = 13f; maxLines = 2
    }

    private fun card() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Color.parseColor("#1E2228")); cornerRadius = dp(10).toFloat() }
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun LinearLayout.addCard(c: View) =
        addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) })

    val view: View
    private val countTv: TextView
    private val msgTv: TextView
    private val learnedBox: LinearLayout
    private val fanTv: TextView
    private val coldTv: TextView
    private val slowTv: TextView
    private val healthTv: TextView
    private val alertTv: TextView
    private var counting = false
    private var lastRefresh = 0L

    init {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        view = ScrollView(act).apply { addView(col) }

        // ---- saúde ----
        val h = card()
        h.addView(tv("Saúde do motor (o normal do SEU carro)", 16f, Color.WHITE, true))
        alertTv = tv("", 15f, warn, true)
        h.addView(alertTv)
        healthTv = tv("", 13f)
        h.addView(healthTv)
        h.addView(tv("O app guarda a mediana de cada viagem e, depois de 3 viagens, avisa quando algo foge do normal. " +
                "Zere depois de um conserto (ex.: troca de vela ou termostática).", 12f, muted))
        h.addView(btn("Zerar o normal") { confirm("Apagar o normal aprendido?") { Health.reset(); refresh(true) } })
        col.addCard(h)

        // ---- eventos ----
        val e = card()
        e.addView(tv("Ensinar o app", 16f, Color.WHITE, true))
        e.addView(tv("Com o motor ligado e o carro parado, toque no item, espere a contagem e, no “AGORA”, " +
                "ligue OU desligue aquilo. O app procura o que mudou na central no mesmo instante. " +
                "Repita 2–3 vezes (ligando e desligando) para confirmar. " +
                "Ventoinha é diferente: em lenta, toque assim que ouvir ela ligar ou desligar (sem contagem).", 12f, muted))
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        Learner.LABELS.chunked(2).forEach { pair ->
            val r = LinearLayout(act)
            pair.forEach { l -> r.addView(btn(l) { startCount(l) }, LinearLayout.LayoutParams(0, -2, 1f)) }
            if (pair.size == 1) r.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(r)
        }
        e.addView(grid)
        countTv = tv("", 22f, green, true).apply { gravity = Gravity.CENTER }
        e.addView(countTv)
        msgTv = tv("", 14f, warn)
        e.addView(msgTv)
        col.addCard(e)

        // ---- aprendido ----
        val l = card()
        l.addView(tv("O que o app já aprendeu", 16f, Color.WHITE, true))
        learnedBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        l.addView(learnedBox)
        col.addCard(l)

        val fc = card()
        fc.addView(tv("Ventoinha × temperatura da água", 15f, Color.WHITE, true))
        fanTv = tv("", 13f); fc.addView(fanTv)
        col.addCard(fc)

        val cc = card()
        cc.addView(tv("Partida a frio (água × ar)", 15f, Color.WHITE, true))
        coldTv = tv("", 13f); cc.addView(coldTv)
        col.addCard(cc)

        val sc = card()
        sc.addView(tv("Adaptações (bytes que mudam entre dias)", 15f, Color.WHITE, true))
        slowTv = tv("", 13f).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        sc.addView(slowTv)
        col.addCard(sc)

        col.addCard(card().apply {
            addView(tv("Relação das marchas: aprendida e salva automaticamente na aba Pista.", 13f, muted))
            addView(btn("Apagar eventos aprendidos") {
                confirm("Apagar eventos, ventoinha, partida a frio e adaptações?") { Learner.resetAll(); refresh(true) }
            })
        })
        refresh(true)
    }

    private fun confirm(q: String, f: () -> Unit) {
        android.app.AlertDialog.Builder(act).setMessage(q)
            .setPositiveButton("Apagar") { _, _ -> f() }.setNegativeButton("Cancelar", null).show()
    }

    private fun startCount(label: String) {
        if (counting) return
        if (!connected()) { msgTv.text = "Conecte na central primeiro (e deixe o motor ligado)."; return }
        counting = true
        if (Learner.isAfterEvent(label)) {
            // ventoinha: não dá para provocar na hora certa → marca agora, olhando o que mudou logo antes
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            Learner.mark(label, System.currentTimeMillis())
            countTv.setTextColor(green); countTv.text = "Marcado: $label mudou agora."
            msgTv.text = Learner.lastMsg
            ui.postDelayed({ countTv.text = ""; counting = false; refresh(true) }, 5000)
            return
        }
        var n = 3
        val tick = object : Runnable {
            override fun run() {
                if (n > 0) {
                    countTv.setTextColor(warn); countTv.text = "Prepare-se: $label em $n…"
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    n--; ui.postDelayed(this, 1000)
                } else {
                    countTv.setTextColor(green); countTv.text = "AGORA: ligue/desligue $label!"
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                    Learner.mark(label, System.currentTimeMillis())
                    msgTv.text = Learner.lastMsg
                    ui.postDelayed({ countTv.text = ""; counting = false; refresh(true) }, 6500)
                }
            }
        }
        ui.post(tick)
    }

    /** Chamado a cada leitura (limita a 1 atualização por segundo). */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 1000) return
        lastRefresh = now
        if (!counting && Learner.lastMsg.isNotEmpty()) msgTv.text = Learner.lastMsg

        val al = Health.alerts()
        alertTv.text = if (al.isEmpty()) "" else al.joinToString("\n")
        alertTv.visibility = if (al.isEmpty()) View.GONE else View.VISIBLE
        healthTv.text = Health.METRICS.joinToString("\n") { Health.line(it) }

        // só recria a lista quando o conteúdo muda (recriar a cada segundo podia "engolir" o toque no botão)
        val sig = Learner.LABELS.joinToString("|") { l -> "${Learner.events(l)}:" + Learner.cands(l).take(3).joinToString { it.key + it.hits } }
        if (sig != learnedSig) {
            learnedSig = sig
            rebuildLearned()
        }

        fanTv.text = Learner.fanReport()
        coldTv.text = Learner.coldReport()
        slowTv.text = Learner.slowReport()
    }

    private var learnedSig: String? = null

    private fun rebuildLearned() {
        learnedBox.removeAllViews()
        var any = false
        for (label in Learner.LABELS) {
            val ev = Learner.events(label)
            if (ev == 0) continue
            any = true
            val cs = Learner.cands(label).take(3)
            val best = cs.firstOrNull()
            val head = when {
                best == null -> "$label: nada mudou na central em $ev vez(es) (talvez ela não receba essa informação)."
                best.confirmed -> "$label ✓ ${best.describe()}"
                else -> "$label: ainda incerto ($ev vez(es)). Candidatos:\n" + cs.joinToString("\n") { "   " + it.describe() }
            }
            learnedBox.addView(tv(head, 13f, if (best?.confirmed == true) green else Color.WHITE))
            val bit = Learner.learnedBit(label)
            if (bit != null) {
                val key = "bit:%02X:%d:%d:%s".format(bit.lid, bit.byte, bit.bit, label)
                learnedBox.addView(btn("+ Mostrar “$label” no Painel") { addToDash(key) },
                    LinearLayout.LayoutParams(-2, -2))
            }
        }
        if (!any) learnedBox.addView(tv("Nada ainda. Use “Ensinar o app” acima.", 13f, muted))
    }
}
