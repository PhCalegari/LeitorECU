package br.leitorecu

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {

    // ---------- cores ----------
    private val BG = Color.parseColor("#121417")
    private val CARD = Color.parseColor("#1E2228")
    private val ACCENT = Color.parseColor("#3FA9F5")
    private val WARN = Color.parseColor("#FFB300")
    private val MUTED = Color.parseColor("#9AA4AE")

    private val ui = Handler(Looper.getMainLooper())
    private val br = Locale("pt", "BR")

    // ---------- estado ----------
    @Volatile private var link: Link? = null
    @Volatile private var elm: Elm327? = null
    @Volatile private var monitoring = false
    @Volatile private var running = true
    private var opener: (() -> Link)? = null
    private val jobs = LinkedBlockingQueue<() -> Unit>()
    private val blocks = HashMap<Int, ByteArray>()
    private val stats = HashMap<Pair<Int, Int>, IntArray>() // min,max
    private var lids = RenaultMap.LIDS
    private var misses = 0
    private var cycleMs = 0L

    // captura guiada
    @Volatile private var phase: String? = null
    private val samples = ArrayList<Triple<Long, String, Map<Int, ByteArray>>>()

    // ---------- views ----------
    private lateinit var status: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDemo: Button
    private lateinit var btnDisc: Button
    private lateinit var pages: List<View>
    private lateinit var tabs: List<Button>
    private val valueViews = HashMap<String, TextView>()
    private lateinit var rate: TextView
    private lateinit var btnPause: Button
    private lateinit var diagOut: TextView
    private lateinit var identView: TextView
    private lateinit var rawView: TextView
    private lateinit var root: FrameLayout
    private var overlay: LinearLayout? = null

    private val worker = Thread { workerLoop() }

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        worker.isDaemon = true
        worker.start()
    }

    override fun onDestroy() {
        running = false
        monitoring = false
        try { link?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------- helpers de UI ----------
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            this.text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }

    private fun setStatus(s: String) = ui.post { status.text = s }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(8)) }
        root.addView(col)

        col.addView(text("LeitorECU • Clio 1.0 16V (SIM 32)", 18f, Color.WHITE, true))
        status = text("Desconectado", 13f, MUTED).apply { setPadding(0, dp(4), 0, dp(8)) }
        col.addView(status)

        val row = LinearLayout(this)
        btnConnect = button("Conectar") { askPermissionsAndConnect() }
        btnDemo = button("Demo") { connect(demo = true) }
        btnDisc = button("Desconectar") { disconnect() }.apply { isEnabled = false }
        listOf(btnConnect, btnDemo, btnDisc).forEach { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        col.addView(row)

        val tabRow = LinearLayout(this).apply { setPadding(0, dp(6), 0, dp(6)) }
        tabs = listOf("Painel", "Diagnóstico", "Bytes").mapIndexed { i, s ->
            button(s) { showPage(i) }.also { tabRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        col.addView(tabRow)

        val content = FrameLayout(this)
        col.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        pages = listOf(buildDashboard(), buildDiag(), buildRaw())
        pages.forEach { content.addView(it) }
        showPage(0)
        setContentView(root)
    }

    private fun showPage(i: Int) {
        pages.forEachIndexed { j, v -> v.visibility = if (i == j) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { j, b -> b.alpha = if (i == j) 1f else 0.55f }
    }

    private fun buildDashboard(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(col)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        btnPause = button("Pausar leitura") {
            monitoring = !monitoring
            btnPause.text = if (monitoring) "Pausar leitura" else "Retomar leitura"
        }.apply { isEnabled = false }
        rate = text("", 12f, MUTED).apply { setPadding(dp(10), 0, 0, 0) }
        top.addView(btnPause); top.addView(rate)
        col.addView(top)

        val grid = GridLayout(this).apply { columnCount = 2 }
        for (s in RenaultMap.SIGNALS) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(CARD)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            card.addView(text(s.name, 13f, MUTED))
            val v = text("--", 30f, Color.WHITE, true)
            card.addView(v)
            card.addView(text(s.unit + if (s.provisional) "  • provisório" else "", 12f,
                if (s.provisional) WARN else MUTED))
            valueViews[s.key] = v
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0; lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            grid.addView(card, lp)
        }
        col.addView(grid)
        col.addView(text("Velocidade ainda não identificada. Valores “provisório” precisam de confirmação.", 12f, MUTED)
            .apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
        return sv
    }

    private fun buildDiag(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(col)
        val r = LinearLayout(this)
        r.addView(button("Ler códigos") { readDtcs("03") }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(button("Pendentes") { readDtcs("07") }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(button("Apagar") { confirmClear() }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(r)
        diagOut = text("Conecte e toque em “Ler códigos”.", 15f).apply {
            background = rounded(CARD); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(diagOut, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(text("Central", 13f, MUTED).apply { setPadding(dp(4), dp(14), 0, dp(4)) })
        identView = text("--", 13f).apply { background = rounded(CARD); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        col.addView(identView)
        return sv
    }

    private fun buildRaw(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(col)
        val r = LinearLayout(this)
        r.addView(button("Captura guiada") { startGuided() }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(button("Zerar mín/máx") { synchronized(stats) { stats.clear() } }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(r)
        col.addView(text("byte: hex dec [mín-máx]   * = variou", 12f, MUTED).apply { setPadding(dp(4), dp(6), 0, dp(4)) })
        rawView = text("--", 12f).apply { typeface = Typeface.MONOSPACE }
        col.addView(rawView)
        return sv
    }

    // ---------- conexão ----------
    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun askPermissionsAndConnect() {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (Build.VERSION.SDK_INT >= 23 && missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        else connect(demo = false)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) connect(demo = false)
        else setStatus("Permissão de Bluetooth negada. Libere nas configurações do Android.")
    }

    private fun adapter(): BluetoothAdapter? = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun connect(demo: Boolean) {
        if (!demo) {
            val ad = adapter()
            if (ad == null) { setStatus("Este celular não tem Bluetooth."); return }
            if (!ad.isEnabled) {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return
            }
        }
        btnConnect.isEnabled = false; btnDemo.isEnabled = false
        jobs.put {
            try {
                if (demo) {
                    opener = { DemoLink() }
                } else {
                    val dev = findDevice() ?: throw Exception(
                        "Adaptador OBD não encontrado. Ligue a ignição, feche o app OBD de outro celular " +
                                "e, em Android antigo, ative a Localização.")
                    getSharedPreferences("cfg", 0).edit().putString("addr", dev.address).apply()
                    opener = { BleLink(this, dev).connect() }
                }
                openAndInit()
                ui.post { onConnected() }
            } catch (e: Exception) {
                ui.post { btnConnect.isEnabled = true; btnDemo.isEnabled = true }
                setStatus("Falha: ${e.message}")
            }
        }
    }

    /** Procura o adaptador BLE por ~8 s (nome com OBD/ELM/VLINK/…). */
    private fun findDevice(): BluetoothDevice? {
        val ad = adapter() ?: return null
        val scanner = ad.bluetoothLeScanner ?: return null
        setStatus("Procurando adaptador Bluetooth...")
        var found: BluetoothDevice? = null
        val saved = getSharedPreferences("cfg", 0).getString("addr", null)
        val keys = listOf("OBD", "ELM", "VLINK", "V-LINK", "VGATE", "ICAR", "VEEPEAK", "KONNWEI")
        val lock = Object()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = (r.scanRecord?.deviceName ?: r.device.name ?: "").uppercase()
                if (r.device.address == saved || keys.any { it in name }) synchronized(lock) {
                    if (found == null) { found = r.device; lock.notifyAll() }
                }
            }
        }
        scanner.startScan(cb)
        synchronized(lock) { if (found == null) lock.wait(8000) }
        try { scanner.stopScan(cb) } catch (_: Exception) {}
        return found
    }

    private fun openAndInit() {
        val l = opener!!.invoke()
        link = l
        val e = Elm327(l) { }
        if (!e.initialize { setStatus(it) }) {
            l.close()
            throw Exception("O adaptador respondeu, mas a central não. A ignição está ligada?")
        }
        elm = e
        setStatus("Lendo identificação e blocos Renault...")
        val id = e.ident()
        lids = RenaultMap.LIDS.filter { e.renaultRead(it) != null }
        ui.post { identView.text = (id ?: "--") + "\n${e.version}\n${e.protocolName}" }
        misses = 0
    }

    private fun onConnected() {
        btnDisc.isEnabled = true; btnPause.isEnabled = true
        btnPause.text = "Pausar leitura"
        monitoring = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setStatus("Conectado – ${elm?.protocolName ?: ""}")
    }

    private fun disconnect() {
        monitoring = false
        jobs.put {
            try { link?.close() } catch (_: Exception) {}
            link = null; elm = null
            ui.post {
                btnConnect.isEnabled = true; btnDemo.isEnabled = true
                btnDisc.isEnabled = false; btnPause.isEnabled = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            setStatus("Desconectado")
        }
    }

    // ---------- worker ----------
    private fun workerLoop() {
        while (running) {
            val job = jobs.poll(50, TimeUnit.MILLISECONDS)
            if (job != null) {
                try { job() } catch (e: Exception) { setStatus("Erro: ${e.message}") }
                continue
            }
            if (monitoring && elm != null) {
                try {
                    pollCycle()
                } catch (e: Exception) {
                    if (!reconnect(e.message ?: "erro")) {
                        monitoring = false
                        setStatus("Conexão perdida: ${e.message}. Toque em Conectar.")
                        ui.post { btnConnect.isEnabled = true; btnDemo.isEnabled = true }
                    }
                }
            }
        }
    }

    private fun reconnect(why: String): Boolean {
        for (n in 1..4) {
            if (!monitoring || !running) return false
            setStatus("Conexão caiu ($why) – reconectando $n/4...")
            try { link?.close() } catch (_: Exception) {}
            Thread.sleep(if (n == 1) 6000 else 2000)   // a sessão KWP anterior precisa expirar
            try {
                openAndInit()
                setStatus("Reconectado")
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun pollCycle() {
        val e = elm ?: return
        val t0 = System.currentTimeMillis()
        var got = 0
        val snap = HashMap<Int, ByteArray>()
        for (lid in lids) {
            if (!monitoring) return
            val d = e.renaultRead(lid) ?: continue
            got++
            snap[lid] = d
            synchronized(stats) {
                d.forEachIndexed { i, b ->
                    val v = b.toInt() and 0xFF
                    val s = stats.getOrPut(lid to i) { intArrayOf(v, v) }
                    if (v < s[0]) s[0] = v
                    if (v > s[1]) s[1] = v
                }
            }
        }
        misses = if (got > 0) 0 else misses + 1
        if (misses >= 3) { misses = 0; throw java.io.IOException("a central parou de responder") }
        synchronized(blocks) { blocks.putAll(snap) }
        phase?.let { p -> synchronized(samples) { samples.add(Triple(System.currentTimeMillis(), p, HashMap(snap))) } }
        cycleMs = System.currentTimeMillis() - t0
        val copy = synchronized(blocks) { HashMap(blocks) }
        ui.post { updateUi(copy) }
    }

    private fun updateUi(b: Map<Int, ByteArray>) {
        for (s in RenaultMap.SIGNALS) {
            val v = s.read(b) ?: continue
            valueViews[s.key]?.text = String.format(br, "%.${s.dec}f", v)
        }
        rate.text = "ciclo ${cycleMs} ms"
        val sb = StringBuilder()
        synchronized(stats) {
            for (lid in lids) {
                val d = b[lid] ?: continue
                sb.append("21%02X  (%d bytes)\n".format(lid, d.size))
                d.forEachIndexed { i, x ->
                    val v = x.toInt() and 0xFF
                    val st = stats[lid to i] ?: intArrayOf(v, v)
                    sb.append("  %2d: %02X %3d [%3d-%3d]%s\n".format(i, v, v, st[0], st[1], if (st[0] != st[1]) " *" else ""))
                }
            }
        }
        rawView.text = sb.toString()
    }

    // ---------- diagnóstico ----------
    private fun readDtcs(mode: String) {
        val e = elm ?: run { diagOut.text = "Conecte primeiro."; return }
        diagOut.text = "Lendo..."
        jobs.put {
            val codes = e.readDtcs(mode)
            val tipo = if (mode == "03") "armazenado(s)" else "pendente(s)"
            ui.post {
                diagOut.text = if (codes.isEmpty()) "Nenhum código $tipo." else
                    "${codes.size} código(s) $tipo:\n\n" + codes.joinToString("\n") { "$it – ${Elm327.describe(it)}" }
            }
        }
    }

    private fun confirmClear() {
        val e = elm ?: run { diagOut.text = "Conecte primeiro."; return }
        AlertDialog.Builder(this)
            .setTitle("Apagar códigos de falha?")
            .setMessage("Faça com a ignição LIGADA e o motor DESLIGADO. Se o defeito continuar, o código volta.")
            .setPositiveButton("Apagar") { _, _ ->
                jobs.put {
                    val ok = e.clearDtcs()
                    ui.post { diagOut.text = if (ok) "Códigos apagados." else "A central não confirmou o apagamento." }
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ---------- captura guiada ----------
    private data class Step(val title: String, val msg: String, val secs: Int, val phase: String?, val color: String)

    private val steps: List<Step> = listOf(
        Step("PREPARE-SE", "Motor em marcha lenta.\nCarro PARADO, ponto morto, freio de mão.", 8, null, "#455A64"),
        Step("MARCHA LENTA", "NÃO toque no acelerador.", 20, "lenta", "#2E7D32"),
    ) + List(3) {
        listOf(
            Step("ACELERE!", "Segure ~2.500 rpm", 6, "acelerando", "#C62828"),
            Step("SOLTE", "Tire o pé e espere.", 12, "soltou", "#F9A825"),
        )
    }.flatten()

    private fun startGuided() {
        if (elm == null) { setStatus("Conecte primeiro."); return }
        monitoring = true
        synchronized(samples) { samples.clear() }
        val ov = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16)); isClickable = true
        }
        val title = text("", 44f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val msg = text("", 20f).apply { gravity = Gravity.CENTER }
        val count = text("", 96f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val info = text("", 14f).apply { gravity = Gravity.CENTER }
        listOf(title, msg, count, info).forEach { ov.addView(it) }
        ov.addView(button("Cancelar") { endGuided(cancel = true) })
        overlay = ov
        root.addView(ov, FrameLayout.LayoutParams(-1, -1))

        fun run(i: Int) {
            if (overlay == null) return
            if (i >= steps.size) { endGuided(cancel = false); return }
            val s = steps[i]
            phase = s.phase
            ov.setBackgroundColor(Color.parseColor(s.color))
            title.text = s.title; msg.text = s.msg
            info.text = "Etapa ${i + 1} de ${steps.size}"
            beep(s.phase == "acelerando")
            var rem = s.secs
            val tick = object : Runnable {
                override fun run() {
                    if (overlay == null) return
                    if (rem <= 0) { run(i + 1); return }
                    count.text = rem.toString(); rem--
                    ui.postDelayed(this, 1000)
                }
            }
            tick.run()
        }
        run(0)
    }

    private fun beep(double: Boolean) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tg.startTone(if (double) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 400)
            ui.postDelayed({ tg.release() }, 600)
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(if (double) 500 else 200, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vib.vibrate(if (double) 500L else 200L)
        } catch (_: Exception) {}
    }

    private fun endGuided(cancel: Boolean) {
        phase = null
        overlay?.let { root.removeView(it) }
        overlay = null
        val rows = synchronized(samples) { ArrayList(samples).also { samples.clear() } }
        if (cancel || rows.isEmpty()) { setStatus(if (cancel) "Captura cancelada." else "Captura sem dados."); return }
        try {
            val where = saveCsv(rows)
            AlertDialog.Builder(this).setTitle("Captura salva")
                .setMessage("${rows.size} amostras gravadas em:\n$where").setPositiveButton("OK", null).show()
        } catch (e: Exception) {
            setStatus("Erro ao salvar: ${e.message}")
        }
    }

    private fun saveCsv(rows: List<Triple<Long, String, Map<Int, ByteArray>>>): String {
        val lens = lids.associateWith { lid -> rows.maxOf { it.third[lid]?.size ?: 0 } }
        val sb = StringBuilder("hora;fase")
        for (lid in lids) for (i in 0 until (lens[lid] ?: 0)) sb.append(";%02X_%02d".format(lid, i))
        sb.append("\n")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
        for ((t, p, d) in rows) {
            sb.append(fmt.format(Date(t))).append(';').append(p)
            for (lid in lids) {
                val b = d[lid]
                for (i in 0 until (lens[lid] ?: 0)) sb.append(';').append(if (b != null && i < b.size) (b[i].toInt() and 0xFF).toString() else "")
            }
            sb.append("\n")
        }
        val name = "captura_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LeitorECU")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("não foi possível criar o arquivo")
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return "Downloads/LeitorECU/$name"
        }
        val dir = File(getExternalFilesDir(null), "capturas").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
        return File(dir, name).absolutePath
    }
}
