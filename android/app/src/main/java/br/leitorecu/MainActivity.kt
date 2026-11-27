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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {

    // ---------- cores (ver Ui.kt) ----------
    // cores lidas na hora (mudam com o modo diurno/noturno)
    private val BG get() = Ui.FACE
    private val CARD get() = Ui.SURFACE
    private val WARN get() = Ui.AMBER
    private val MUTED get() = Ui.MUTED
    private val C1 get() = Color.parseColor(if (Ui.night) "#00B8E6" else "#0091C2")   // série 1 do gráfico
    private val C2 get() = Color.parseColor(if (Ui.night) "#F5E13A" else "#D6336C")   // série 2 do gráfico
    private var tach: TachView? = null
    private var mixBar: MixBarView? = null

    /**
     * Bloco da sonda como medidor de lambda: λ estimado pela média de 3 s da sonda (para de pular),
     * AFR na gasolina brasileira, LEDs 0,90–1,10 e etiqueta RICA / IDEAL / POBRE / CORTE + média de 30 s.
     */
    private fun showMixInLambda(v: Double, b: Map<Int, ByteArray>) {
        val cut = RenaultMap.fuelCut(b)                          // corte de verdade: injeção ~0 ms
        val open = !cut && !RenaultMap.closedLoop(b)              // malha aberta: central não está usando a sonda
        val water = RenaultMap.SIGNALS.firstOrNull { it.key == "agua" }?.read(b)
        val cold = water != null && water < 65
        val avg = LambdaWatch.avgV() ?: v
        val lam = LambdaWatch.lambdaFromV(avg)
        mixBar?.cut = cut
        mixBar?.lam = if (cut) null else lam
        val t = tiles["lambda"] ?: return
        t.value.text = if (cut) "--" else when {
            lam <= 0.9401 -> "<0,94"                                   // sonda saturada: mais rica que isso
            lam >= 1.0599 -> ">1,06"                                   // sonda saturada: mais pobre que isso
            else -> fmt(lam, 2)
        }
        // fundo pela posição do λ: verde no ideal, vermelho rico, azul pobre (mesma escala de cores do app)
        if (!cut) {
            t.card.setBackgroundColor(Ui.heat((1.0 - (lam - 0.90) / 0.20).coerceIn(0.0, 1.0)))
            t.value.setTextColor(Color.BLACK)
            t.label.setTextColor(Color.argb(190, 0, 0, 0))
        }
        val (word, col) = when {
            cut -> "CORTE" to Color.parseColor("#6B7380")             // pé fora: injeção cortada, não é "pobre"
            cold -> "MOTOR FRIO" to Color.parseColor("#6B7380")       // malha aberta: central enriquece de propósito
            open && lam < 0.97 -> "ENRIQUECENDO" to Color.parseColor("#E53935")   // aceleração/transição: rica de propósito
            open -> "MALHA ABERTA" to Color.parseColor("#6B7380")
            lam < 0.97 -> "RICA" to Color.parseColor("#E53935")
            lam > 1.03 -> "POBRE" to Color.parseColor("#2F6BFF")
            else -> "IDEAL" to Color.parseColor("#1E9E5A")
        }
        val m = LambdaWatch.mix(b)
        val line1 = if (cut) "  pé fora do acelerador"
            else "  AFR ${fmt(lam * LambdaWatch.AFR_STOICH, 1)}  (${fmt(v, 2)} V)"
        val line2 = when {
            cut -> ""
            m == null -> "\nmédia 30 s: juntando…"
            m == LambdaWatch.MIX_COLD -> "\nmotor frio: mistura enriquecida"
            else -> "\ncorreção 30 s: ${LambdaWatch.mixLabel(m)}"
        }
        // etiqueta: texto branco sobre a cor do estado, legível sobre o bloco colorido
        val tag = " $word "
        val sp = android.text.SpannableString(tag + line1 + line2)
        sp.setSpan(android.text.style.BackgroundColorSpan(col), 0, tag.length, 0)
        sp.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 0, tag.length, 0)
        sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, tag.length, 0)
        t.unit?.text = sp
        mixBar?.onColor = !cut
    }
    private lateinit var connBox: LinearLayout
    private lateinit var discSmall: Button

    /** Conectado: esconde os botões de conexão (mais espaço para os medidores no carro; o resto fica no menu ⋮). */
    private fun setConnectedUi(on: Boolean) {
        connBox.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** Menu ⋮ da barra superior: ações de conexão e do Painel, como o menu dos programas de injeção. */
    private fun showMenu(anchor: View) {
        val pm = android.widget.PopupMenu(this, anchor)
        val m = pm.menu
        val connected = elm != null
        if (!connected && !autoActive) {
            m.add(0, 1, 0, "Conectar")
            m.add(0, 2, 1, "Demo (dados simulados)")
        } else m.add(0, 3, 0, if (connected) "Desconectar" else "Parar tentativas")
        if (connected) m.add(0, 4, 2, if (monitoring) "Pausar leitura" else "Retomar leitura")
        m.add(0, 5, 3, "Personalizar painel")
        m.add(0, 6, 4, "Consumo e calibração")
        m.add(0, 7, 5, "Conectar sozinho").apply { isCheckable = true; isChecked = autoPref() }
        m.add(0, 8, 6, "Modo diurno / noturno")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> askPermissionsAndConnect()
                2 -> connect(demo = true)
                3 -> disconnect()
                4 -> { monitoring = !monitoring; btnPause.text = if (monitoring) "Pausar" else "Retomar" }
                5 -> { showPage(0); showCustomize() }
                6 -> showFuel()
                7 -> {
                    val on = !autoPref()
                    getSharedPreferences("cfg", 0).edit().putBoolean("auto", on).apply()
                    // ligar já conectado (no carro, não no Demo) também passa a reconectar sozinho se cair
                    autoActive = on && elm != null && link !is DemoLink
                    Toast.makeText(this, if (on) "Vai conectar sozinho" else "Conexão só pelo botão", Toast.LENGTH_SHORT).show()
                }
                8 -> chooseTheme()
            }
            true
        }
        pm.show()
    }

    private val ui = Handler(Looper.getMainLooper())
    private val br = Locale("pt", "BR")

    // ---------- estado ----------
    @Volatile private var link: Link? = null
    @Volatile private var elm: Elm327? = null
    @Volatile private var monitoring = false
    @Volatile private var running = true
    /** Conexão automática ativa: fica tentando (a cada ~10 s) até achar o adaptador e a central. */
    @Volatile private var autoActive = false
    private fun autoPref() = getSharedPreferences("cfg", 0).getBoolean("auto", true)
    private var opener: (() -> Link)? = null
    private val jobs = LinkedBlockingQueue<() -> Unit>()
    private val blocks = HashMap<Int, ByteArray>()
    private val stats = HashMap<Pair<Int, Int>, IntArray>() // min,max
    private var lids = RenaultMap.LIDS
    private var misses = 0
    private var cycleMs = 0L
    /** Aba Pista aberta: lê só 21A2 (rotação, corte, pedal pista 1) e 21A4 (injeção); o resto a cada 5 ciclos. */
    @Volatile private var fastMode = false
    private var fastCount = 0
    private val FAST_LIDS = listOf(0xA2, 0xA4)

    // Painel configurável
    private lateinit var dash: MutableList<CardCfg>
    private val history = HashMap<String, ArrayDeque<Pair<Long, Double>>>()
    private var graphKeys = arrayOf<String?>(null, null)

    // captura guiada
    @Volatile private var phase: String? = null
    private val samples = ArrayList<Triple<Long, String, Map<Int, ByteArray>>>()

    // ---------- views ----------
    private lateinit var status: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDemo: Button
    private lateinit var btnDisc: Button
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    private val valueViews = HashMap<String, TextView>()
    private lateinit var grid: GridLayout
    private lateinit var rate: TextView
    private lateinit var btnPause: Button
    private lateinit var graph: GraphView
    private lateinit var spin1: Spinner
    private lateinit var spin2: Spinner
    private lateinit var diagOut: TextView
    private lateinit var identView: TextView
    private lateinit var rawView: TextView
    private lateinit var root: FrameLayout
    private var overlay: LinearLayout? = null

    private val worker = Thread { workerLoop() }
    private lateinit var drag: DragPage
    private lateinit var learn: LearnPage
    private lateinit var alertView: TextView
    private var lastAlert = 0L
    @Volatile private var latest: Map<String, Double> = emptyMap()

    // =====================================================================
    // ---------- modo diurno / noturno ----------
    /** "auto" (segue o modo escuro do celular), "day" ou "night". */
    private fun themePref() = getSharedPreferences("cfg", 0).getString("theme", "auto") ?: "auto"

    private fun wantNight() = when (themePref()) {
        "night" -> true
        "day" -> false
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun applyTheme() {
        val n = wantNight()
        Ui.setNight(n)
        setTheme(if (n) R.style.AppThemeDark else R.style.AppTheme)
        window.statusBarColor = Ui.BAR
        window.navigationBarColor = Ui.FACE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (n) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    /** Tema certo já antes do super.onCreate (evita piscar o tema errado ao abrir). */
    private fun applyThemeEarly() {
        Ui.setNight(wantNight())
        setTheme(if (Ui.night) R.style.AppThemeDark else R.style.AppTheme)
    }

    private var curPage = 0
    private var lastStatus = "Desconectado"

    /** Refaz as telas com as cores novas sem mexer na conexão com o carro. */
    private fun rebuildUi() {
        val page = curPage
        try { drag.stopGps() } catch (_: Exception) {}
        buildUi()
        showPage(page)
        val connected = elm != null
        setConnectedUi(connected)
        btnConnect.isEnabled = !connected && !autoActive
        btnDemo.isEnabled = !connected && !autoActive
        btnDisc.isEnabled = connected || autoActive
        btnPause.isEnabled = connected
        status.text = statusText(lastStatus)
    }

    private fun chooseTheme() {
        val keys = listOf("auto", "day", "night")
        val names = arrayOf("Automático (segue o modo escuro do celular)", "Diurno (claro)", "Noturno (escuro)")
        AlertDialog.Builder(this).setTitle("Modo diurno / noturno")
            .setSingleChoiceItems(names, keys.indexOf(themePref()).coerceAtLeast(0)) { d, i ->
                getSharedPreferences("cfg", 0).edit().putString("theme", keys[i]).apply()
                d.dismiss()
                if (wantNight() != Ui.night) { applyTheme(); rebuildUi() }
            }.setNegativeButton("Cancelar", null).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Seed.apply(this)   // instalação nova: carrega os dados já aprendidos no carro (embutidos no APK)
        applyThemeEarly()
        super.onCreate(savedInstanceState)
        applyTheme()
        Fuel.init(this)
        Maps.init(this)
        Learner.init(this)
        Health.init(this)
        SpeedMap.load(getSharedPreferences("cfg", 0))
        dash = DashConfig.load(this)
        val cfgP = getSharedPreferences("cfg", 0)
        // velocidade da central logo abaixo da rotação (uma vez; depois vale a ordem que o usuário escolher)
        if (!cfgP.getBoolean("vel_moved", false)) {
            val i = dash.indexOfFirst { it.key == "vel_ecu" }
            if (i > 1) { val c = dash.removeAt(i); dash.add((dash.indexOfFirst { it.key == "rpm" } + 1).coerceIn(0, dash.size), c); DashConfig.save(this, dash) }
            cfgP.edit().putBoolean("vel_moved", true).apply()
        }
        // o indicador de mistura agora fica dentro do bloco da sonda: tira o bloco separado de quem já tinha
        if (!cfgP.getBoolean("mix_now_removed", false)) {
            if (dash.removeAll { it.key == "mix_now" }) DashConfig.save(this, dash)
            cfgP.edit().putBoolean("mix_now_removed", true).putBoolean("mix_now_added", true).apply()
        }
        val gp = getSharedPreferences("cfg", 0)
        graphKeys = arrayOf(gp.getString("g1", "rpm"), gp.getString("g2", "lambda"))
        buildUi()
        worker.isDaemon = true
        worker.start()
        // conecta sozinho ao abrir (se já tem permissão de Bluetooth; senão espera o toque em Conectar)
        if (autoPref() && neededPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
            ui.postDelayed({ if (elm == null && btnConnect.isEnabled) connect(demo = false, auto = true) }, 600)
    }

    override fun onDestroy() {
        running = false
        monitoring = false
        try { link?.close() } catch (_: Exception) {}
        try { drag.stopGps() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------- helpers de UI ----------
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, size: Float = 14f, color: Int = Ui.TEXT, bold: Boolean = false) =
        TextView(this).apply {
            this.text = s; textSize = size; setTextColor(color)
            typeface = if (bold) Ui.BODY_MED else Ui.BODY
            setLineSpacing(0f, 1.15f)
        }

    /** Tela estreita (celulares comuns em pé têm ~360–412 dp). */
    private val narrow get() = resources.configuration.screenWidthDp < 400

    /** Botão compacto: sem a largura mínima de 88 dp do Android, texto numa linha só. */
    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setOnClickListener { onClick() }
        minWidth = 0; minimumWidth = 0
        minHeight = dp(42); minimumHeight = dp(42)
        setPadding(dp(6), 0, dp(6), 0)
        textSize = if (narrow) 13f else 14f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        Ui.style(this, Ui.Kind.GHOST)
    }

    /** Linha de botões com espaço entre eles. */
    private fun buttonRow(vararg b: View) = LinearLayout(this).apply {
        b.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    /** Número que diminui sozinho para caber no card (Android 8+); altura fixa para não "pular". */
    private fun fitText(tv: TextView, maxSp: Int): LinearLayout.LayoutParams {
        tv.maxLines = 1
        if (Build.VERSION.SDK_INT >= 26) {
            tv.setAutoSizeTextTypeUniformWithConfiguration(12, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        return LinearLayout.LayoutParams(-1, dp((maxSp * 1.3f).toInt()))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // no automático, o celular mudou para escuro/claro: troca as cores sem reiniciar (a conexão continua)
        if (themePref() == "auto" && wantNight() != Ui.night) { applyTheme(); rebuildUi(); return }
        rebuildDashboard()   // recalcula tamanhos ao girar a tela
    }

    private fun rounded(color: Int) = Ui.panel(this, color)

    /** Status com uma "luz" colorida na frente (verde conectado, âmbar tentando, vermelho falha). */
    private fun statusText(s: String): CharSequence {
        val sp = android.text.SpannableString("●  $s")
        sp.setSpan(android.text.style.ForegroundColorSpan(Ui.statusColor(s)), 0, 1, 0)
        return sp
    }

    private fun setStatus(s: String) = ui.post { lastStatus = s; status.text = statusText(s) }

    private fun fmt(v: Double, dec: Int) = String.format(br, "%.${dec}f", v)

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(outer)

        // barra superior de ferramenta (como TuneECU): ícone, nome, status pequeno e menu ⋮; abas embaixo
        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BAR); setPadding(dp(10), dp(8), 0, 0)
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("ECU", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setBackgroundColor(Ui.NEEDLE); Ui.num(this)
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        titles.addView(text("LeitorECU  Clio 1.0 16V", 16f, Ui.TEXT, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        status = text("", 12f, Ui.TEXT).apply {
            text = statusText("Desconectado"); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titles.addView(status)
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(text("⋮", 26f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#26000000")), null, null)
            setOnClickListener { showMenu(this) }
            contentDescription = "Menu"
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        appBar.addView(head)
        outer.addView(appBar)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // desconectado: dois botões chapados de ponta a ponta; conectado, somem (o resto fica no menu ⋮)
        connBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), 0) }
        btnConnect = button("Conectar") { askPermissionsAndConnect() }.apply { Ui.style(this, Ui.Kind.PRIMARY) }
        btnDemo = button("Demo") { connect(demo = true) }
        btnDisc = button("Parar") { disconnect() }.apply { isEnabled = false }
        connBox.addView(buttonRow(btnConnect, btnDisc, btnDemo))
        col.addView(connBox)
        discSmall = btnDisc

        // menu (abas) dentro da barra superior, em maiúsculas como os menus desses programas
        val tabRow = LinearLayout(this).apply { setPadding(0, dp(4), dp(8), 0) }
        tabs = listOf("Painel", "Gráfico", "Pista", "Diag.", "Bytes", "Aprender").mapIndexed { i, s ->
            TextView(this).apply {
                // largura pelo texto + sobra dividida igualmente: as palavras longas ganham mais espaço
                text = s.uppercase(); gravity = Gravity.CENTER; maxLines = 1; textSize = if (narrow) 12f else 13f
                letterSpacing = 0.03f
                setPadding(dp(3), 0, dp(3), 0)
                isClickable = true; isFocusable = true
                setOnClickListener { showPage(i) }
                tabRow.addView(this, LinearLayout.LayoutParams(-2, dp(42), 1f))
            }
        }
        appBar.addView(tabRow)
        outer.addView(col, LinearLayout.LayoutParams(-1, 0, 1f))

        val content = FrameLayout(this)
        col.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2) })
        drag = DragPage(this, { latest }, { synchronized(blocks) { HashMap(blocks) } })
        learn = LearnPage(this, { elm != null && monitoring && link !is DemoLink }, { k -> addToDash(k) })
        pages = listOf(buildDashboard(), buildGraph(), drag.view, buildDiag(), buildRaw(), learn.view)
        // o Painel vai de ponta a ponta (blocos); as outras abas têm margem
        pages.drop(1).forEach { it.setPadding(dp(12), dp(10), dp(12), dp(8)) }
        pages.forEach { content.addView(it) }
        showPage(0)
        setContentView(root)
    }

    private fun showPage(i: Int) {
        curPage = i
        pages.forEachIndexed { j, v -> v.visibility = if (i == j) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { j, b -> Ui.tab(b, i == j) }
        fastMode = (i == 2)   // aba Pista
    }

    // ---------- Painel ----------
    private fun buildDashboard(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(col)
        // pausar agora fica no menu ⋮ (o botão continua existindo só para guardar o estado)
        btnPause = button("Pausar") {}.apply { isEnabled = false }
        alertView = text("", 14f, Color.BLACK, true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(WARN)
            visibility = View.GONE
            setOnClickListener { showPage(5) }
        }
        col.addView(alertView)

        // grade de blocos de ponta a ponta; as frestas pretas entre eles fazem a divisão (sem caixas nem bordas)
        grid = GridLayout(this).apply { columnCount = 2; setBackgroundColor(Ui.FACE) }
        col.addView(grid)
        rate = text("", 11f, MUTED).apply {
            setPadding(dp(12), dp(6), dp(12), 0); Ui.num(this, false); visibility = View.GONE
        }
        col.addView(rate)
        col.addView(text("* a confirmar: a posição ou a escala ainda não foi comprovada no carro.  " +
                "Cor do bloco: escala de calor da faixa normal do valor (azul baixo, vermelho alto).",
            11f, MUTED).apply { setPadding(dp(12), dp(4), dp(12), dp(12)) })
        rebuildDashboard()
        return sv
    }

    /** Partes de um bloco do Painel, para pintar pela escala de calor a cada leitura. */
    private class Tile(val card: LinearLayout, val label: TextView, val value: TextView, val unit: TextView?)
    private val tiles = HashMap<String, Tile>()

    /** Bloco cheio de cor (como as células do mapa) com texto preto; sem faixa conhecida fica escuro. */
    private fun paintTile(key: String, v: Double?) {
        val t = tiles[key] ?: return
        val c = if (v != null && key != "rpm") Ui.heatFor(key, v) else null
        if (c != null) {
            t.card.setBackgroundColor(c)
            t.value.setTextColor(Color.BLACK)
            t.label.setTextColor(Color.argb(190, 0, 0, 0)); t.unit?.setTextColor(Color.argb(170, 0, 0, 0))
        } else {
            t.card.setBackgroundColor(CARD)
            t.value.setTextColor(Ui.TEXT)   // no claro o número fica escuro; a cor da rotação está no conta-giros
            t.label.setTextColor(MUTED); t.unit?.setTextColor(MUTED)
        }
    }

    /** Adiciona um medidor ao Painel (ex.: bit aprendido na aba Aprender). */
    private fun addToDash(key: String) {
        if (dash.none { it.key == key }) dash.add(CardCfg(key, false))
        DashConfig.save(this, dash)
        rebuildDashboard()
        Toast.makeText(this, "Adicionado ao Painel", Toast.LENGTH_SHORT).show()
    }

    private fun rebuildDashboard() {
        grid.removeAllViews()
        valueViews.clear()
        tiles.clear()
        tach = null
        mixBar = null
        val hair = maxOf(1, resources.displayMetrics.density.toInt())   // fresta preta de ~2 px entre os blocos
        fun cellLp(span: Int) = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, span, GridLayout.FILL, 1f)
        ).apply { width = 0; setMargins(hair, hair, hair, hair) }
        // medidor pequeno sozinho na linha ocupa a linha toda (senão sobra um buraco na placa)
        var colPos = 0
        var lastSmall: View? = null
        fun closeRow() { if (colPos == 1) lastSmall?.layoutParams = cellLp(2); colPos = 0 }
        for (cfg in dash) {
            val m = Modules.byKey(cfg.key) ?: continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(CARD)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            // rótulo curto em maiúsculas, como nas colunas dos programas de injeção; "*" = a confirmar
            val nm = when (cfg.key) {
                "rpm" -> "${m.name} (rpm)"
                "lambda" -> "Lambda λ (média 3 s)"
                else -> m.name
            } + if (m.provisional && !m.raw) " *" else ""
            val label = text(nm.uppercase(), if (cfg.big) 12f else 11f, MUTED, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; letterSpacing = 0.02f
            }
            card.addView(label)
            val maxSp = if (cfg.big) (if (narrow) 56 else 64) else (if (narrow) 34 else 38)
            val v = text("--", maxSp.toFloat(), Ui.TEXT).apply { Ui.num(this) }
            card.addView(v, fitText(v, maxSp))
            if (cfg.key == "rpm") {
                // conta-giros com o gradiente da escala de calor e a faixa vermelha
                val t = TachView(this)
                card.addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(2) })
                tach = t
            }
            if (cfg.key == "lambda") {
                // dentro do bloco da sonda: LEDs pobre (azul) à esquerda, rica (vermelho) à direita, ideal no meio
                val mb = MixBarView(this, if (cfg.big) 21 else 15)
                card.addView(mb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                mixBar = mb
            }
            val unitTv = if (cfg.key != "rpm")
                text(if (m.raw) "byte bruto 0–255" else m.unit, 11f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            else null
            if (cfg.key == "lambda") unitTv?.maxLines = 2   // AFR + correção de 30 s
            unitTv?.let { card.addView(it) }
            valueViews[cfg.key] = v
            tiles[cfg.key] = Tile(card, label, v, unitTv)
            if (cfg.big) {
                closeRow()
                grid.addView(card, cellLp(2))
            } else {
                grid.addView(card, cellLp(1))
                lastSmall = card
                colPos = (colPos + 1) % 2
            }
        }
        closeRow()
        if (dash.isEmpty()) grid.addView(text("O Painel está vazio. Abra o menu ⋮ e toque em Personalizar painel.", 14f, MUTED)
            .apply { setBackgroundColor(CARD); setPadding(dp(14), dp(14), dp(14), dp(14)) },
            GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, 2, 1f)).apply { width = 0 })
    }

    /** Lista para escolher medidores: marcar, ordenar (▲▼) e deixar grande. */
    private fun showCustomize() {
        // candidatos: conhecidos + bytes que variaram (ou já escolhidos) e ainda sem nome
        val varied = synchronized(stats) {
            stats.filter { (k, s) -> s[0] != s[1] && k !in Modules.usedBytes }.keys
                .sortedWith(compareBy({ it.first }, { it.second }))
        }
        val order = dash.map { it.key }.toMutableList()
        val all = (order + Modules.known.map { it.key } + varied.map { Modules.raw(it.first, it.second).key }).distinct()
            .toMutableList()
        val checked = all.associateWith { it in order }.toMutableMap()
        val big = all.associateWith { k -> dash.firstOrNull { it.key == k }?.big ?: false }.toMutableMap()
        // escolhidos primeiro, na ordem atual
        all.sortBy { k -> order.indexOf(k).let { if (it < 0) 1000 + all.indexOf(k) else it } }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val sv = ScrollView(this).apply { addView(list) }
        val snap = synchronized(blocks) { HashMap(blocks) }

        fun render() {
            list.removeAllViews()
            list.addView(text("Marque o que aparece no Painel. ▲▼ mudam a ordem. “Grande” ocupa a linha toda." +
                    if (varied.isEmpty()) "\n(Conecte e deixe ler um pouco para listar também os bytes sem nome que variam.)" else "",
                12f, MUTED))
            all.forEachIndexed { i, k ->
                val m = Modules.byKey(k) ?: return@forEachIndexed
                val cur = m.read(snap)?.let { " = " + fmt(it, m.dec) } ?: ""
                // linha 1: nome (pode quebrar) / linha 2: Grande + ▲▼ alinhados à direita
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                val cb = CheckBox(this).apply {
                    text = m.name + cur + if (m.raw) " (bruto)" else ""
                    isChecked = checked[k] == true
                    setOnCheckedChangeListener { _, b -> checked[k] = b }
                }
                item.addView(cb)
                val r = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                val bg = CheckBox(this).apply {
                    text = "Grande"; isChecked = big[k] == true
                    setOnCheckedChangeListener { _, b -> big[k] = b }
                }
                r.addView(bg)
                r.addView(button("▲") { if (i > 0) { all.add(i - 1, all.removeAt(i)); render() } }, LinearLayout.LayoutParams(dp(52), -2))
                r.addView(button("▼") { if (i < all.size - 1) { all.add(i + 1, all.removeAt(i)); render() } }, LinearLayout.LayoutParams(dp(52), -2))
                item.addView(r, LinearLayout.LayoutParams(-1, -2))
                list.addView(item)
                list.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(-1, 1))
            }
        }
        render()

        AlertDialog.Builder(this)
            .setTitle("Personalizar Painel")
            .setView(sv)
            .setPositiveButton("Salvar") { _, _ ->
                dash = all.filter { checked[it] == true }.map { CardCfg(it, big[it] == true) }.toMutableList()
                DashConfig.save(this, dash)
                rebuildDashboard()
                refreshGraphChoices()
            }
            .setNeutralButton("Padrão") { _, _ ->
                dash = DashConfig.default(); DashConfig.save(this, dash); rebuildDashboard(); refreshGraphChoices()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Consumo ----------
    private fun showFuel() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val info = text("", 15f)
        fun refresh() {
            val avg = Fuel.avgKml()?.let { fmt(it, 1) + " km/L" } ?: "--"
            info.text = "Viagem atual:\n" +
                    "  Combustível gasto: ${fmt(Fuel.tripL, 2)} L\n" +
                    "  Distância (GPS): ${fmt(Fuel.tripKm, 1)} km\n" +
                    "  Média: $avg\n\n" +
                    "Fator de calibração: ${fmt(Fuel.factor, 3)}" +
                    (if (Fuel.factor == 1.0) "  (ainda não calibrado)" else "") +
                    "\n\nO km/L e a distância precisam do GPS ligado (aba Pista)."
        }
        refresh()
        col.addView(info)
        val liters = android.widget.EditText(this).apply {
            hint = "Litros que entraram no abastecimento"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        col.addView(text("\nCalibrar: zere a viagem com o tanque cheio, rode pelo menos 1 tanque parcial " +
                "e, ao completar de novo, digite os litros que entraram.", 12f, MUTED))
        col.addView(liters)
        col.addView(LinearLayout(this).apply {
            addView(button("Calibrar") {
                val v = liters.text.toString().replace(",", ".").toDoubleOrNull()
                if (v == null || !Fuel.calibrate(v)) {
                    android.widget.Toast.makeText(this@MainActivity,
                        "Precisa de pelo menos 1 L calculado na viagem e um valor válido.", android.widget.Toast.LENGTH_LONG).show()
                } else { liters.setText(""); refresh() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Zerar viagem") { Fuel.resetTrip(); refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        col.addView(LinearLayout(this).apply {
            addView(button("Ligar GPS") { drag.startGps(); refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Zerar calibração") { Fuel.resetFactor(); refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        AlertDialog.Builder(this).setTitle("Consumo").setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Fechar", null).show()
    }

    // ---------- Gráfico ----------
    private fun graphCandidates(): List<String> =
        (Modules.known.map { it.key } + dash.map { it.key }).distinct()

    private lateinit var mapView: MapView
    private lateinit var mapInfo: TextView
    private var graphMode = 0          // 0 = linhas, 1 = mapa de ponto, 2 = mapa de injeção

    private fun buildGraph(): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // seletor: linhas no tempo ou mapas observados (tabela rotação × pressão, como nos programas de injeção)
        val modes = listOf("Linhas", "Ponto", "Injeção")   // curtos: em maiúsculas os nomes longos cortavam
        lateinit var modeBtns: List<Button>
        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val mapBox = ScrollView(this)
        fun setMode(m: Int) {
            graphMode = m
            modeBtns.forEachIndexed { i, b -> Ui.style(b, if (i == m) Ui.Kind.PRIMARY else Ui.Kind.GHOST) }
            lines.visibility = if (m == 0) View.VISIBLE else View.GONE
            mapBox.visibility = if (m == 0) View.GONE else View.VISIBLE
            if (m > 0) drawMap()
        }
        modeBtns = modes.mapIndexed { i, s -> button(s) { setMode(i) }.apply { textSize = 12f } }
        outer.addView(buttonRow(*modeBtns.toTypedArray()))
        val frame = FrameLayout(this)
        outer.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        frame.addView(lines)

        val mc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mapBox.addView(mc)
        mapView = MapView(this)
        mc.addView(text("Pressão do coletor (mbar) →", 12f, MUTED).apply { gravity = Gravity.END; setPadding(0, 0, dp(2), dp(4)) })
        mc.addView(mapView, LinearLayout.LayoutParams(-1, -2))
        mapInfo = text("", 12f, MUTED).apply { setPadding(dp(2), dp(10), dp(2), dp(6)); setLineSpacing(0f, 1.2f) }
        mc.addView(mapInfo)
        mc.addView(button("Zerar mapas") {
            AlertDialog.Builder(this).setMessage("Apagar os mapas observados até agora?")
                .setPositiveButton("Apagar") { _, _ -> Maps.clear(); drawMap() }.setNegativeButton("Cancelar", null).show()
        }.apply { Ui.style(this, Ui.Kind.QUIET); gravity = Gravity.CENTER_VERTICAL or Gravity.START },
            LinearLayout.LayoutParams(-2, dp(40)))
        frame.addView(mapBox)

        val col = lines
        val r = LinearLayout(this)
        spin1 = Spinner(this); spin2 = Spinner(this)
        r.addView(spin1, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(spin2, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(r)
        // janela de tempo: a escolhida fica destacada (um seletor, não três ações)
        val wins = listOf(30, 60, 120)
        lateinit var winBtns: List<Button>
        fun mark(sel: Int) = winBtns.forEachIndexed { i, b -> Ui.style(b, if (wins[i] == sel) Ui.Kind.PRIMARY else Ui.Kind.GHOST) }
        winBtns = wins.map { s -> button("$s s") { graph.windowSec = s; graph.invalidate(); mark(s) } }
        col.addView(buttonRow(*winBtns.toTypedArray()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        graph = GraphView(this)
        mark(graph.windowSec)
        col.addView(graph, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        col.addView(text("Azul = 1º valor, magenta = 2º. Cada linha tem sua própria escala.", 12f, MUTED)
            .apply { setPadding(dp(4), dp(6), dp(4), dp(4)) })
        refreshGraphChoices()
        setMode(0)
        return outer
    }

    /** Atualiza a tabela do mapa escolhido (ponto ou injeção). */
    private fun drawMap() {
        if (!::mapView.isInitialized || graphMode == 0) return
        val g = Maps.active()[graphMode - 1]
        if (mapView.grid !== g) mapView.grid = g else mapView.invalidate()
        mapInfo.text = "${g.title}: média do que a central está usando (${g.unit}) em cada faixa de rotação × pressão " +
                "do coletor. ${g.filled()} de ${g.rows * g.cols} células preenchidas; cada uma precisa de 3 leituras. " +
                "A célula vermelha é a de agora; os traços vermelhos nos cabeçalhos mostram a rotação e a pressão. " +
                "Números em itálico, mais claros, são estimados entre células lidas.\n" +
                "Só leitura: o app não grava nada na central." +
                if (Maps.usingDemo) "\nDemo: mapa simulado, não é salvo." else ""
    }

    private fun refreshGraphChoices() {
        if (!::spin1.isInitialized) return
        val keys = graphCandidates()
        val labels = keys.map { Modules.byKey(it)?.name ?: it }
        fun setup(sp: Spinner, idx: Int, allowNone: Boolean) {
            val ks: List<String?> = if (allowNone) listOf<String?>(null) + keys else keys
            val ls = if (allowNone) listOf("(nenhum)") + labels else labels
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ls)
            val sel = ks.indexOf(graphKeys[idx]).coerceAtLeast(0)
            sp.setSelection(sel)
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    graphKeys[idx] = ks[pos]
                    getSharedPreferences("cfg", 0).edit().putString("g${idx + 1}", ks[pos]).apply()
                    drawGraph()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        setup(spin1, 0, false)
        setup(spin2, 1, true)
    }

    private fun drawGraph() {
        if (!::graph.isInitialized) return
        val out = mutableListOf<GraphView.Series>()
        graphKeys.forEachIndexed { i, k ->
            val m = k?.let { Modules.byKey(it) } ?: return@forEachIndexed
            val pts = history[k]?.toList() ?: emptyList()
            out += GraphView.Series(m.name, m.unit, m.dec, pts, if (i == 0) C1 else C2)
        }
        graph.series = out
    }

    // ---------- Diagnóstico ----------
    private fun buildDiag(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(col)
        // "Apagar" é a ação que não tem volta: texto vermelho
        val clear = button("Apagar") { confirmClear() }.apply {
            setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.parseColor("#A3ACB5"), Ui.RED)))
        }
        col.addView(buttonRow(button("Ler códigos") { readDtcs("03") }, button("Pendentes") { readDtcs("07") }, clear))
        diagOut = text("Toque em “Ler códigos” para ver as falhas guardadas na central.", 15f).apply {
            background = rounded(CARD); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(diagOut, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(text("Central", 13f, MUTED).apply { setPadding(dp(4), dp(14), 0, dp(4)) })
        col.addView(button("Ler informações completas da central") { readEcuInfo() }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
        identView = text("--", 13f).apply {
            background = rounded(CARD); setPadding(dp(12), dp(12), dp(12), dp(12)); setTextIsSelectable(true)
        }
        col.addView(identView)
        return sv
    }

    private fun readEcuInfo() {
        val e = elm ?: run { identView.text = "Conecte primeiro."; return }
        identView.text = "Lendo..."
        val wasOn = monitoring
        monitoring = false
        jobs.put {
            val sb = StringBuilder()
            for ((k, v) in e.identDetails()) sb.append("$k:\n  $v\n")
            sb.append("\nAdaptador: ${e.version}\nProtocolo: ${e.protocolName}\n")
            val extras = listOf(0x81 to "VIN (2181)", 0x82 to "Rede / MUX (2182)", 0x84 to "Rastreabilidade (2184)")
            for ((lid, name) in extras) {
                sb.append("\n$name:\n  ").append(e.readInfo(lid) ?: "não suportado por esta central").append("\n")
            }
            monitoring = wasOn
            ui.post { identView.text = sb.toString() }
        }
    }

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

    // ---------- Bytes ----------
    private fun buildRaw(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(col)
        col.addView(buttonRow(button("Captura guiada") { startGuided() },
            button("Zerar mín/máx") { synchronized(stats) { stats.clear() } }))
        col.addView(text("byte: hex dec [mín-máx]   * = variou   ✓ = já identificado", 12f, MUTED)
            .apply { setPadding(dp(4), dp(6), 0, dp(4)) })
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
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        else connect(demo = false)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == DragPage.REQ) {
            drag.onPermission(results.isNotEmpty() && results.any { it == PackageManager.PERMISSION_GRANTED })
            return
        }
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) connect(demo = false)
        else setStatus("Permissão de Bluetooth negada. Libere nas configurações do Android.")
    }

    private fun adapter(): BluetoothAdapter? = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun connect(demo: Boolean, auto: Boolean = false) {
        if (!demo) {
            val ad = adapter()
            if (ad == null) { setStatus("Este celular não tem Bluetooth."); return }
            if (!ad.isEnabled) {
                if (auto) { setStatus("Bluetooth desligado – ligue o Bluetooth e toque em Conectar."); return }
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return
            }
        }
        btnConnect.isEnabled = false; btnDemo.isEnabled = false
        autoActive = !demo && autoPref()
        if (autoActive) btnDisc.isEnabled = true          // permite parar as tentativas
        jobs.put { connectLoop(demo) }
    }

    /** Tenta conectar; com a conexão automática, repete a cada ~10 s até conseguir (roda na thread de trabalho). */
    private fun connectLoop(demo: Boolean) {
        var n = 0
        while (running) {
            n++
            try {
                if (demo) {
                    opener = { DemoLink() }
                } else {
                    val dev = findDevice() ?: throw Exception(
                        "Adaptador OBD não encontrado. Ligue a ignição, feche outro app OBD " +
                                "e, em Android antigo, ative a Localização.")
                    getSharedPreferences("cfg", 0).edit().putString("addr", dev.address).apply()
                    opener = { BleLink(this, dev).connect() }
                }
                openAndInit()
                ui.post { onConnected() }
                return
            } catch (e: Exception) {
                try { link?.close() } catch (_: Exception) {}
                link = null; elm = null
                if (!autoActive || demo) {
                    ui.post { btnConnect.isEnabled = true; btnDemo.isEnabled = true; setConnectedUi(false); btnDisc.isEnabled = false }
                    setStatus("Falha: ${e.message}")
                    return
                }
                setStatus("Aguardando o carro (tentativa $n)… ${e.message}\nTentando de novo em 10 s – ou toque em Desconectar.")
                if (!waitAuto(10_000)) {
                    ui.post { btnConnect.isEnabled = true; btnDemo.isEnabled = true; setConnectedUi(false); btnDisc.isEnabled = false }
                    setStatus("Conexão automática parada.")
                    return
                }
            }
        }
    }

    /** Espera [ms] e devolve false se a conexão automática foi cancelada no meio. */
    private fun waitAuto(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (!autoActive || !running) return false
            Thread.sleep(200)
        }
        return autoActive && running
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
        // uma informação por linha (antes vinham emendadas com "•")
        ui.post { identView.text = (id?.replace(" • ", "\n") ?: "--") + "\n\n${e.version}\n${e.protocolName}" }
        misses = 0
    }

    private fun onConnected() {
        btnDisc.isEnabled = true; btnPause.isEnabled = true
        btnPause.text = "Pausar"
        monitoring = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setConnectedUi(true)
        setStatus(if (link is DemoLink) "Conectado: Demo, dados simulados" else "Conectado – ${elm?.protocolName ?: ""}")
    }

    private fun disconnect() {
        autoActive = false
        monitoring = false
        jobs.put {
            try { link?.close() } catch (_: Exception) {}
            link = null; elm = null
            ui.post {
                btnConnect.isEnabled = true; btnDemo.isEnabled = true; setConnectedUi(false)
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
                        if (autoActive) {
                            // conexão automática: continua tentando até o carro voltar
                            setStatus("Conexão perdida (${e.message}) – aguardando o carro para reconectar…")
                            connectLoop(demo = false)
                        } else {
                            setStatus("Conexão perdida: ${e.message}. Toque em Conectar.")
                            ui.post { btnConnect.isEnabled = true; btnDemo.isEnabled = true; setConnectedUi(false) }
                        }
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
        // durante a captura guiada lê tudo (o CSV precisa de todos os bytes)
        // enquanto a velocidade não foi descoberta, lê tudo (a busca precisa de todos os bytes atualizados)
        val readNow = if (fastMode && SpeedMap.known && phase == null && (fastCount++ % 5 != 0))
            lids.filter { it in FAST_LIDS || it == SpeedMap.lid }.ifEmpty { lids } else lids
        for (lid in readNow) {
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
        val now = System.currentTimeMillis()
        if (link !is DemoLink) {     // o modo Demo não pode "ensinar" nada nem somar na viagem/calibração
            Fuel.tick(b)   // acumula litros e km da viagem
            Learner.feed(b, now)
            Health.feed(b, now)
        }
        Maps.feed(b, isDemo = link is DemoLink)   // mapas observados (o Demo tem os seus, só na memória)
        if (now - lastAlert > 5000) {
            lastAlert = now
            val al = Health.alerts()
            alertView.text = al.joinToString("\n") + if (al.isNotEmpty()) "\n(toque para ver a aba Aprender)" else ""
            alertView.visibility = if (al.isEmpty()) View.GONE else View.VISIBLE
        }
        if (pages[5].visibility == View.VISIBLE) learn.refresh()
        LambdaWatch.feed(b)
        LambdaWatch.feedMix(b)
        // valores calculados (consumo) mostram "--" quando faltam dados, em vez do último valor
        for (k in listOf("cons_lh", "cons_kml", "trip_kml", "avg5_kml", "avg5_lh", "lambda_st", "mistura", "mix_now")) {
            val m = Modules.byKey(k) ?: continue
            if (m.read(b) == null) valueViews[k]?.text = "--"
        }
        // histórico p/ gráfico: valores conhecidos + escolhidos no Painel
        val keys = (Modules.known.map { it.key } + dash.map { it.key } + graphKeys.filterNotNull()).distinct()
        for (k in keys) {
            val m = Modules.byKey(k) ?: continue
            val v = m.read(b) ?: continue
            if (v == LambdaWatch.MIX_COLD) {          // "motor frio": só texto, não entra no gráfico
                valueViews[k]?.text = m.text?.invoke(v) ?: "--"; continue
            }
            val h = history.getOrPut(k) { ArrayDeque() }
            h.addLast(now to v)
            while (h.isNotEmpty() && now - h.first.first > 125_000) h.removeFirst()
            valueViews[k]?.text = m.text?.invoke(v) ?: fmt(v, m.dec)
            if (k == "rpm") tach?.rpm = v
            paintTile(k, v)   // bloco pintado pela escala de calor, como as células do mapa
            if (k == "lambda") showMixInLambda(v, b)
        }
        latest = Modules.known.mapNotNull { m -> m.read(b)?.let { m.key to it } }.toMap() +
                ("_t" to System.currentTimeMillis().toDouble())   // hora da última leitura da central
        val ec = elm
        rate.text = "ciclo ${cycleMs} ms" + (ec?.let { e ->
            (if (e.msPerRead > 0) String.format(br, ", %.0f ms por leitura", e.msPerRead) else "") +
                    when (e.oneResponse) { true -> ", modo rápido ✓"; false -> ", modo normal (o adaptador não aceita o rápido)"; else -> "" }
        } ?: "")
        rate.visibility = View.VISIBLE
        if (pages[1].visibility == View.VISIBLE) { if (graphMode == 0) drawGraph() else drawMap() }
        if (pages[4].visibility == View.VISIBLE) {
            val sb = StringBuilder()
            synchronized(stats) {
                for (lid in lids) {
                    val d = b[lid] ?: continue
                    sb.append("21%02X  (%d bytes)\n".format(lid, d.size))
                    d.forEachIndexed { i, x ->
                        val v = x.toInt() and 0xFF
                        val st = stats[lid to i] ?: intArrayOf(v, v)
                        val mark = (if (st[0] != st[1]) " *" else "") + (if ((lid to i) in Modules.usedBytes) " ✓" else "")
                        sb.append("  %2d: %02X %3d [%3d-%3d]%s\n".format(i, v, v, st[0], st[1], mark))
                    }
                }
            }
            rawView.text = sb.toString()
        }
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
        // tamanhos proporcionais ao menor lado da tela (funciona em pé e deitado)
        val s = minOf(resources.configuration.screenWidthDp, resources.configuration.screenHeightDp) / 360f
        val title = text("", 40f * s, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val msg = text("", 19f * s).apply { gravity = Gravity.CENTER }
        val count = text("", 88f * s, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val info = text("", 14f).apply { gravity = Gravity.CENTER }
        ov.addView(title, fitText(title, (40 * s).toInt()).apply { width = -1 })
        ov.addView(msg)
        ov.addView(count, fitText(count, (88 * s).toInt()))
        ov.addView(info)
        ov.addView(button("Cancelar") { endGuided(cancel = true) }, LinearLayout.LayoutParams(dp(160), -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12)
        })
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
        val fmtT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
        for ((t, p, d) in rows) {
            sb.append(fmtT.format(Date(t))).append(';').append(p)
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
