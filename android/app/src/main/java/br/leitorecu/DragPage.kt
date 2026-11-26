package br.leitorecu

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Aba "Pista": velocidade pelo GPS, marcha (GPS ÷ rotação da ECU, com calibração automática),
 * cronômetro 0–100, gráfico de aceleração por marcha e gravação da sessão em CSV.
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class DragPage(
    private val act: Activity,
    private val ecuNow: () -> Map<String, Double>,
    private val blocksNow: () -> Map<Int, ByteArray>,
) : LocationListener {

    // descoberta automática da velocidade dentro da ECU
    private val finder = SpeedFinder()
    private lateinit var speedFindTv: TextView

    private enum class St { OFF, WAIT_STOP, ARMED, RUNNING, DONE }

    private val br = Locale("pt", "BR")
    private val ui = Handler(Looper.getMainLooper())
    private val lm = act.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val prefs = act.getSharedPreferences("drag", 0)

    // ---- 0–100 ----
    private var st = St.OFF
    private var prevT = 0.0      // ms (base elapsedRealtime)
    private var prevV = 0.0      // km/h
    private var tStart = 0.0
    private val splits = linkedMapOf<Int, Double>()
    private var maxRpm = 0.0
    private val fixTimes = ArrayDeque<Long>()

    // ---- marcha ----
    private val gears = Gears(prefs)
    private val ratioHist = ArrayDeque<Pair<Double, Double>>()   // (t, km/h por 1000 rpm)
    private var gearNow = 0

    // ---- aceleração ----
    private var accel = 0.0       // m/s², suavizada
    private var pedalFilter = true
    private val curve = GearCurve()

    // ---- gravação ----
    private var recording = false
    private val rec = StringBuilder()
    private var recKeys: List<String> = emptyList()
    private var recRows = 0

    // ---- views ----
    private val speedTv: TextView
    private val gearTv: TextView
    private val gearInfo: TextView
    private val clockTv: TextView
    private val statusTv: TextView
    private val splitTvs: Map<Int, TextView>
    private val gpsTv: TextView
    private val histTv: TextView
    private val btnGps: Button
    private val btnRec: Button
    private val btnFilter: Button
    private val graph: GearGraphView
    val view: View

    private fun tv(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(act).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private val dens = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()

    private fun btn(s: String, f: () -> Unit) = Button(act).apply {
        text = s; isAllCaps = false; setOnClickListener { f() }
        minWidth = 0; minimumWidth = 0; minHeight = dp(42); minimumHeight = dp(42)
        setPadding(dp(6), 0, dp(6), 0); textSize = 13f
        maxLines = 2
    }

    /** Número grande que encolhe para caber (Android 8+). */
    private fun big(tv: TextView, maxSp: Int): LinearLayout.LayoutParams {
        tv.maxLines = 1
        if (Build.VERSION.SDK_INT >= 26)
            tv.setAutoSizeTextTypeUniformWithConfiguration(14, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        return LinearLayout.LayoutParams(-1, dp((maxSp * 1.3f).toInt()))
    }

    private fun row(vararg b: View) = LinearLayout(act).apply {
        b.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    init {
        val muted = Color.parseColor("#9AA4AE")
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8) }
        btnGps = btn("Ativar GPS") { toggleGps() }
        btnRec = btn("● Gravar sessão") { toggleRec() }
        col.addView(row(btnGps, btnRec))
        statusTv = tv("Toque em “Ativar GPS”. Conecte também na ECU para ter rotação e marcha.", 16f,
            Color.parseColor("#FFB300"), true)
        col.addView(statusTv)

        // velocidade + marcha lado a lado
        val bigSp = if (act.resources.configuration.screenWidthDp < 400) 56 else 64
        speedTv = tv("--", bigSp.toFloat(), Color.WHITE, true)
        gearTv = tv("–", bigSp.toFloat(), Color.parseColor("#7CDB6A"), true)
        val sp = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; addView(speedTv, big(speedTv, bigSp)); addView(tv("km/h (GPS)", 13f, muted))
        }
        val gr = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; addView(gearTv, big(gearTv, bigSp)); addView(tv("marcha", 13f, muted))
        }
        col.addView(row(sp, gr))
        gearInfo = tv("", 12f, muted)
        col.addView(gearInfo)
        speedFindTv = tv("", 12f, Color.parseColor("#FFB300"))
        col.addView(speedFindTv)
        col.addView(row(btn("Procurar velocidade de novo") {
            SpeedMap.clear(prefsCfg); finder.reset(); showSpeedFind()
        }))

        // 0–100
        col.addView(tv("Cronômetro 0–100", 14f, muted).apply { setPadding(0, 16, 0, 0) })
        clockTv = tv("0,00 s", 40f, Color.parseColor("#3FA9F5"), true)
        col.addView(clockTv, big(clockTv, 40))
        // parciais em 3 colunas (cabe em tela estreita)
        splitTvs = TARGETS.associateWith { tv("--", 20f, Color.WHITE, true) }
        col.addView(row(*TARGETS.map { t ->
            LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("0–$t", 12f, muted)); addView(splitTvs.getValue(t))
            }
        }.toTypedArray()))
        col.addView(row(btn("Nova medição 0–100") { rearm() }))
        gpsTv = tv("", 12f, muted)
        col.addView(gpsTv)

        // gráfico por marcha
        col.addView(tv("Aceleração por marcha", 14f, muted).apply { setPadding(0, 16, 0, 0) })
        graph = GearGraphView(act, curve)
        col.addView(graph, LinearLayout.LayoutParams(-1, (act.resources.displayMetrics.density * 280).toInt()))
        btnFilter = btn("Filtro: só pé embaixo") { pedalFilter = !pedalFilter; updateFilterBtn() }
        col.addView(row(btnFilter, btn("Limpar gráfico") { curve.clear(); graph.invalidate() }))
        col.addView(row(btn("Recalibrar marchas") { gears.reset(); gearNow = 0; showGearInfo() }))

        // histórico 0–100
        col.addView(tv("Histórico 0–100", 14f, muted).apply { setPadding(0, 16, 0, 4) })
        histTv = tv("", 14f).apply { gravity = Gravity.START }
        col.addView(histTv)
        col.addView(tv(
            "⚠ Use apenas em local fechado e seguro. Deixe o celular preso no suporte e não mexa nele dirigindo.\n" +
                    "Marcha: o app aprende sozinho as relações do câmbio – rode uma vez passando por todas as marchas. " +
                    "A precisão depende do GPS (mais leituras/s = melhor).",
            12f, muted).apply { setPadding(0, 20, 0, 0); gravity = Gravity.START })
        view = ScrollView(act).apply { addView(col) }
        showHistory(); showGearInfo(); updateFilterBtn(); showSpeedFind()
    }

    private val prefsCfg get() = act.getSharedPreferences("cfg", 0)

    private fun showSpeedFind() {
        speedFindTv.text = if (SpeedMap.known) "Velocidade da central: ${SpeedMap.describe()} ✓"
        else finder.progress()
    }

    /** Compara a velocidade do GPS com todos os bytes da ECU até achar o byte da velocidade. */
    private fun feedFinder(v: Double, accuracy: Float) {
        if (SpeedMap.known || accuracy > 20f) return
        val b = blocksNow()
        if (b.isEmpty()) return
        finder.add(v, b)
        if (finder.samples % 5 == 0) {
            finder.best()?.let { r ->
                SpeedMap.lid = r.lid; SpeedMap.start = r.start; SpeedMap.len = r.len
                SpeedMap.scale = r.scale; SpeedMap.offset = r.offset
                SpeedMap.save(prefsCfg)
                beep(true)
                statusTv.text = "Velocidade encontrada na central!"
            }
            showSpeedFind()
        }
    }

    private fun updateFilterBtn() {
        btnFilter.text = if (pedalFilter) "Filtro: só pé embaixo (>60 %)" else "Filtro: todas as acelerações"
    }

    // ================= GPS =================
    private fun toggleGps() = if (st == St.OFF) startGps() else stopGps()

    fun startGps() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.any { act.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            act.requestPermissions(perms, REQ); return
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            statusTv.text = "Ative a Localização (GPS) do celular."; return
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, Looper.getMainLooper())
        st = St.WAIT_STOP
        btnGps.text = "Desligar GPS"
        statusTv.text = "Aguardando sinal do GPS..."
    }

    fun stopGps() {
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        st = St.OFF
        btnGps.text = "Ativar GPS"
        statusTv.text = "GPS desligado."
        ui.removeCallbacks(tick)
        if (recording) toggleRec()
    }

    fun onPermission(granted: Boolean) {
        if (granted) startGps() else statusTv.text = "Sem permissão de localização não dá para medir."
    }

    override fun onLocationChanged(l: Location) {
        if (!l.hasSpeed()) return
        val t = l.elapsedRealtimeNanos / 1_000_000.0
        val v = l.speed * 3.6
        Gps.kmh = v; Gps.at = System.currentTimeMillis()      // usado pelo consumo (km/L)
        val ecu = ecuNow()
        val rpm = ecu["rpm"]
        // pista 1 do pedal (21A2, lido a cada ciclo no modo rápido); mesma escala do PR126
        val pedal = ecu["pedal1"] ?: ecu["pedal"]

        val now = SystemClock.elapsedRealtime()
        fixTimes.addLast(now)
        while (fixTimes.isNotEmpty() && now - fixTimes.first() > 3000) fixTimes.removeFirst()
        gpsTv.text = String.format(br, "GPS: %.1f leituras/s • precisão ±%.0f m", fixTimes.size / 3.0, l.accuracy)
        speedTv.text = String.format(br, "%.0f", v)

        // aceleração (m/s²) pela variação da velocidade do GPS
        val dt = (t - prevT) / 1000.0
        if (prevT > 0 && dt in 0.05..3.0) {
            val a = ((v - prevV) / 3.6) / dt
            accel = if (abs(accel) < 1e-9) a else accel * 0.4 + a * 0.6
        }

        feedFinder(v, l.accuracy)
        // marcha: usa a velocidade da central quando ela já foi descoberta (mais rápida que o GPS)
        updateGear(t, ecu["vel_ecu"] ?: v, rpm)
        if (gearNow > 0 && rpm != null && v > 5 && accel > 0.05 && (!pedalFilter || (pedal ?: 0.0) >= 60.0)) {
            curve.add(gearNow, rpm, accel)
            graph.invalidate()
        }
        if (recording) record(t, v, rpm, ecu)
        run0to100(t, v, rpm)
        prevT = t; prevV = v
    }

    // ================= marcha =================
    private fun updateGear(t: Double, v: Double, rpm: Double?) {
        if (rpm == null || rpm < 900 || v < 8) { gearNow = 0; gearTv.text = if (v < 3) "N/P" else "–"; return }
        val r = v / (rpm / 1000.0)                    // km/h por 1000 rpm
        ratioHist.addLast(t to r)
        while (ratioHist.isNotEmpty() && t - ratioHist.first().first > 2000) ratioHist.removeFirst()
        // relação estável por ~1,5 s (variação < 4 %) => marcha engatada, sem embreagem
        val rs = ratioHist.map { it.second }
        val stable = ratioHist.size >= 2 && t - ratioHist.first().first >= 1200 &&
                (rs.max() - rs.min()) / rs.average() < 0.04
        if (stable) gears.learn(rs.average())
        gearNow = gears.match(r)
        gearTv.text = if (gearNow > 0) "${gearNow}ª" else "?"
        showGearInfo(r)
    }

    private fun showGearInfo(r: Double? = null) {
        val c = gears.centers
        gearInfo.text = (if (c.isEmpty()) "Marchas: calibrando – dirija passando por todas as marchas."
        else "Marchas aprendidas (km/h a cada 1000 rpm): " +
                c.mapIndexed { i, x -> "${i + 1}ª ${String.format(br, "%.1f", x)}" }.joinToString("  ")) +
                (r?.let { String.format(br, "\nAgora: %.1f km/h /1000 rpm", it) } ?: "")
    }

    // ================= 0–100 =================
    private fun rearm() {
        if (st == St.OFF) { startGps(); return }
        st = St.WAIT_STOP
        splits.clear(); maxRpm = 0.0
        ui.removeCallbacks(tick)
        clockTv.text = "0,00 s"; showSplits()
        statusTv.text = "Pare o carro para armar."
    }

    private fun interp(t0: Double, v0: Double, t1: Double, v1: Double, target: Double): Double =
        if (v1 == v0) t1 else t0 + (target - v0) / (v1 - v0) * (t1 - t0)

    private val tick = object : Runnable {
        override fun run() {
            if (st != St.RUNNING) return
            clockTv.text = String.format(br, "%.2f s", (SystemClock.elapsedRealtime() - tStart) / 1000.0)
            ecuNow()["rpm"]?.let { maxRpm = max(maxRpm, it) }
            ui.postDelayed(this, 50)
        }
    }

    private fun run0to100(t: Double, v: Double, rpm: Double?) {
        when (st) {
            St.WAIT_STOP -> if (v < 1.0) {
                st = St.ARMED; statusTv.text = "PRONTO – pode acelerar!"; beep(false)
            }
            St.ARMED -> if (v >= START_KMH) {
                val tc = interp(prevT, prevV, t, v, START_KMH)
                val slope = (v - prevV) / max(1.0, t - prevT)
                tStart = max(prevT, tc - START_KMH / max(slope, 1e-4))
                st = St.RUNNING; splits.clear(); maxRpm = 0.0
                statusTv.text = "Medindo 0–100..."
                ui.post(tick)
            }
            St.RUNNING -> {
                for (target in TARGETS) if (target !in splits && v >= target) {
                    splits[target] = (interp(prevT, prevV, t, v, target.toDouble()) - tStart) / 1000.0
                    showSplits()
                }
                rpm?.let { maxRpm = max(maxRpm, it) }
                when {
                    100 in splits -> finish()
                    (t - tStart) > 60_000 -> abort("Passou de 60 s – cancelado.")
                    v < 1.0 && (t - tStart) > 2000 -> abort("Carro parou – cancelado.")
                }
            }
            else -> {}
        }
    }

    private fun finish() {
        st = St.DONE
        ui.removeCallbacks(tick)
        val t100 = splits[100]!!
        clockTv.text = String.format(br, "%.2f s", t100)
        statusTv.text = "0–100 concluído! Toque em “Nova medição” para repetir."
        beep(true)
        val line = SimpleDateFormat("dd/MM HH:mm", br).format(Date()) +
                String.format(br, "  0–60 %.2f  0–100 %.2f s", splits[60] ?: 0.0, t100) +
                if (maxRpm > 0) String.format(br, "  (máx %.0f rpm)", maxRpm) else ""
        val hist = (listOf(line) + (prefs.getString("hist", "") ?: "").split("\n").filter { it.isNotBlank() }).take(15)
        prefs.edit().putString("hist", hist.joinToString("\n")).apply()
        showHistory()
    }

    private fun abort(msg: String) {
        st = St.WAIT_STOP
        ui.removeCallbacks(tick)
        statusTv.text = "$msg Pare o carro para armar de novo."
    }

    private fun showSplits() {
        fun f(k: Int) = splits[k]?.let { String.format(br, "%.2f", it) } ?: "--"
        for ((t, v) in splitTvs) v.text = f(t)
    }

    private fun showHistory() {
        histTv.text = (prefs.getString("hist", "") ?: "").ifBlank { "Nenhuma medição ainda." }
    }

    // ================= gravação =================
    private fun toggleRec() {
        if (!recording) {
            rec.setLength(0); recRows = 0
            recKeys = Modules.known.map { it.key }
            rec.append("hora;t_s;vel_gps_kmh;acel_ms2;marcha;kmh_por_1000rpm;fase_0a100;")
            rec.append(recKeys.joinToString(";")).append("\n")
            recording = true
            btnRec.text = "■ Parar gravação"
            statusTv.text = "Gravando sessão..."
            if (st == St.OFF) startGps()
        } else {
            recording = false
            btnRec.text = "● Gravar sessão"
            if (recRows == 0) { statusTv.text = "Gravação vazia (sem leituras do GPS)."; return }
            try {
                statusTv.text = "Sessão salva ($recRows linhas): " + save(rec.toString())
            } catch (e: Exception) {
                statusTv.text = "Erro ao salvar: ${e.message}"
            }
        }
    }

    private var recStart = 0.0

    private fun record(t: Double, v: Double, rpm: Double?, ecu: Map<String, Double>) {
        if (recRows == 0) recStart = t
        val t0rec = recStart
        val ratio = if (rpm != null && rpm > 500) v / (rpm / 1000.0) else null
        val hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        fun n(x: Double?, d: Int = 2) = x?.let { String.format(br, "%.${d}f", it) } ?: ""
        rec.append(hora).append(';').append(n((t - t0rec) / 1000.0, 3)).append(';')
            .append(n(v, 1)).append(';').append(n(accel, 3)).append(';')
            .append(if (gearNow > 0) gearNow.toString() else "").append(';')
            .append(n(ratio, 2)).append(';').append(st.name).append(';')
            .append(recKeys.joinToString(";") { n(ecu[it]) }).append("\n")
        recRows++
    }

    private fun save(text: String): String {
        val name = "sessao_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LeitorECU")
            }
            val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("não foi possível criar o arquivo")
            act.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return "Downloads/LeitorECU/$name"
        }
        val dir = File(act.getExternalFilesDir(null), "sessoes").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
        return File(dir, name).absolutePath
    }

    private fun beep(done: Boolean) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tg.startTone(if (done) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 300)
            ui.postDelayed({ tg.release() }, 500)
        } catch (_: Exception) {}
    }

    @Deprecated("API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { statusTv.text = "GPS desligado no celular." }

    companion object {
        const val REQ = 2
        private const val START_KMH = 3.0
        private val TARGETS = listOf(60, 80, 100)
    }
}

/** Aprende as relações de marcha (km/h a cada 1000 rpm) por agrupamento e salva nas preferências. */
class Gears(private val prefs: android.content.SharedPreferences) {
    private val list = mutableListOf<DoubleArray>()   // [centro, contagem]

    init {
        prefs.getString("gears", "")?.split(",")?.mapNotNull { e ->
            val p = e.split(":"); if (p.size == 2) doubleArrayOf(p[0].toDouble(), p[1].toDouble()) else null
        }?.let { list.addAll(it) }
    }

    /** Centros confiáveis (vistos várias vezes), em ordem crescente = 1ª, 2ª, ... */
    val centers: List<Double> get() = list.filter { it[1] >= 3 }.map { it[0] }.sorted()

    fun learn(r: Double) {
        val near = list.minByOrNull { abs(it[0] - r) / it[0] }
        if (near != null && abs(near[0] - r) / near[0] < 0.10) {
            near[0] = near[0] * 0.9 + r * 0.1
            near[1] = min(near[1] + 1, 1000.0)
        } else {
            list.add(doubleArrayOf(r, 1.0))
            if (list.size > 7) list.remove(list.minByOrNull { it[1] }!!)   // descarta ruído
        }
        prefs.edit().putString("gears", list.joinToString(",") { "${it[0]}:${it[1]}" }).apply()
    }

    /** Número da marcha (1..n) para a relação atual, ou 0 se não bate com nenhuma (embreagem/troca). */
    fun match(r: Double): Int {
        val c = centers
        val i = c.indices.minByOrNull { abs(c[it] - r) / c[it] } ?: return 0
        return if (abs(c[i] - r) / c[i] < 0.07) i + 1 else 0
    }

    fun reset() { list.clear(); prefs.edit().remove("gears").apply() }
}

/** Média da aceleração por faixa de rotação (250 rpm), separada por marcha. */
class GearCurve {
    val bins = HashMap<Int, HashMap<Int, DoubleArray>>()   // marcha -> faixa -> [soma, n]

    fun add(gear: Int, rpm: Double, a: Double) {
        val b = (rpm / 250).toInt()
        val perGear: HashMap<Int, DoubleArray> = bins.getOrPut(gear) { HashMap() }
        val s: DoubleArray = perGear.getOrPut(b) { doubleArrayOf(0.0, 0.0) }
        s[0] = s[0] + a
        s[1] = s[1] + 1.0
    }

    fun clear() = bins.clear()
}

/** Gráfico: aceleração (m/s²) × rotação, uma linha por marcha. */
class GearGraphView(ctx: Context, private val curve: GearCurve) : View(ctx) {
    private val colors = listOf("#3FA9F5", "#7CDB6A", "#FFB300", "#FF6B6B", "#C38CFF", "#4DD0E1").map { Color.parseColor(it) }
    // tamanhos em dp/sp convertidos para pixels (iguais em qualquer densidade de tela)
    private val d = ctx.resources.displayMetrics.density
    private val sd = ctx.resources.displayMetrics.scaledDensity
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = Color.parseColor("#2C3138"); strokeWidth = 1f * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * sd; color = Color.parseColor("#9AA4AE") }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.parseColor("#1E2228"))
        val l = 30f * d; val r = width - 8f * d; val top = 26f * d; val b = height - 22f * d
        val rpmMin = 1000.0; val rpmMax = 6500.0
        var aMax = 0.5
        curve.bins.values.forEach { m -> m.values.forEach { if (it[1] > 0) aMax = max(aMax, it[0] / it[1]) } }
        aMax = (aMax * 1.15)
        for (k in 0..4) {
            val y = b - (b - top) * k / 4
            c.drawLine(l, y, r, y, grid)
            c.drawText(String.format(Locale("pt", "BR"), "%.1f", aMax * k / 4), 2f * d, y + 4f * d, txt)
        }
        for (rpm in listOf(2000, 3000, 4000, 5000, 6000)) {
            val x = (l + (r - l) * (rpm - rpmMin) / (rpmMax - rpmMin)).toFloat()
            c.drawLine(x, top, x, b, grid)
            c.drawText("${rpm / 1000}k", x - 6f * d, b + 16f * d, txt)
        }
        c.drawText("m/s²", 2f * d, 16f * d, txt)
        if (curve.bins.isEmpty()) {
            c.drawText("Acelere em cada marcha", l + 8f * d, (top + b) / 2, txt)
            c.drawText("para formar as curvas.", l + 8f * d, (top + b) / 2 + 16f * d, txt); return
        }
        var lx = l + 40f * d
        for ((g, m) in curve.bins.toSortedMap()) {
            val col = colors[(g - 1).coerceIn(0, colors.size - 1)]
            line.color = col; dot.color = col
            val pts = m.toSortedMap().filter { it.value[1] > 0 }.map { (bin, s) ->
                val rpm = bin * 250 + 125.0
                val x = (l + (r - l) * (rpm - rpmMin) / (rpmMax - rpmMin)).toFloat()
                val y = (b - (b - top) * ((s[0] / s[1]) / aMax)).toFloat()
                x to y
            }
            val path = Path()
            pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y); c.drawCircle(x, y, 3f * d, dot) }
            if (pts.size > 1) c.drawPath(path, line)
            txt.color = col
            c.drawText("${g}ª", lx, 16f * d, txt)
            txt.color = Color.parseColor("#9AA4AE")
            lx += 30f * d
        }
    }
}
