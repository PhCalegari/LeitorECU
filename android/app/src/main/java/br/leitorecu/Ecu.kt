package br.leitorecu

/** Driver ELM327 + serviços KWP2000 usados pela SIM 32 do Clio. */
class Elm327(private val link: Link, private val log: (String) -> Unit) {
    var version = ""
    var protocolName = ""

    fun send(cmd: String, timeoutMs: Long = 3000, quiet: Boolean = false): List<String> {
        val lines = link.command(cmd, timeoutMs)
        if (!quiet) log(">> $cmd\n" + if (lines.isEmpty()) "(sem resposta)" else lines.joinToString("\n"))
        return lines
    }

    private fun hexLines(lines: List<String>): List<String> = lines.mapNotNull { l ->
        val s = l.replace(" ", "").uppercase().replace(Regex("^[0-9A-F]:"), "")
        if (s.isNotEmpty() && s.all { it in "0123456789ABCDEF" }) s else null
    }

    private fun hexToBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Reset, configuração e conexão com a ECU (o Clio responde em KWP FAST). */
    fun initialize(status: (String) -> Unit): Boolean {
        status("Reiniciando ELM327...")
        val z = send("ATZ", 5000)
        Thread.sleep(300)
        version = z.firstOrNull { "ELM" in it.uppercase() } ?: z.joinToString(" ")
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) send(c)
        for ((i, p) in listOf("0", "0", "5").withIndex()) {
            status("Conectando na central (tentativa ${i + 1}/3)...")
            send("ATPC")
            send("ATSP$p")
            if (hexLines(send("0100", 20000)).any { "4100" in it }) {
                protocolName = send("ATDP").joinToString(" ")
                return true
            }
            Thread.sleep(1500)
        }
        return false
    }

    /** Serviço Renault 21 (ReadDataByLocalIdentifier): devolve os bytes depois de "61 xx". */
    fun renaultRead(lid: Int, quiet: Boolean = true): ByteArray? {
        val target = "61%02X".format(lid)
        for (l in hexLines(send("21%02X".format(lid), 3000, quiet))) {
            val i = l.indexOf(target)
            if (i >= 0) {
                val d = l.substring(i + 4)
                return hexToBytes(d.substring(0, d.length / 2 * 2))
            }
        }
        return null
    }

    fun ident(): String? {
        val d = renaultRead(0x80, false) ?: return null
        if (d.size < 18) return null
        fun hx(a: Int, b: Int) = d.copyOfRange(a, b).joinToString("") { "%02X".format(it) }
        val sup = String(d.copyOfRange(6, 9), Charsets.US_ASCII)
        return "Ref. ${hx(0, 5)} • HW ${hx(9, 14)} • Programa ${hx(14, 16)} • Versão ${hx(16, 18)} • " +
                "Diag %02X • Fornecedor %s".format(d[5], sup)
    }

    fun readDtcs(mode: String): List<String> {
        val resp = if (mode == "03") "43" else "47"
        val out = mutableListOf<String>()
        for (h in hexLines(send(mode, 10000))) {
            if (!h.startsWith(resp)) continue
            val body = h.substring(2)
            var j = 0
            while (j + 4 <= body.length) {
                val c = body.substring(j, j + 4)
                if (c != "0000") decodeDtc(c).let { if (it !in out) out.add(it) }
                j += 4
            }
        }
        return out
    }

    fun clearDtcs(): Boolean = hexLines(send("04", 10000)).any { "44" in it }

    companion object {
        fun decodeDtc(h: String): String {
            val f = h[0].digitToInt(16)
            return "PCBU"[f shr 2] + (f and 3).toString() + h.substring(1)
        }

        private val DESC = mapOf(
            "P0105" to "Sensor de pressão do coletor (MAP) – circuito",
            "P0106" to "Sensor MAP – faixa/desempenho",
            "P0110" to "Sensor de temperatura do ar – circuito",
            "P0115" to "Sensor de temperatura do motor – circuito",
            "P0120" to "Sensor de posição da borboleta – circuito",
            "P0130" to "Sonda lambda B1S1 – circuito",
            "P0133" to "Sonda lambda B1S1 – resposta lenta",
            "P0135" to "Sonda lambda B1S1 – aquecimento",
            "P0171" to "Mistura muito pobre",
            "P0172" to "Mistura muito rica",
            "P0201" to "Injetor cilindro 1", "P0202" to "Injetor cilindro 2",
            "P0203" to "Injetor cilindro 3", "P0204" to "Injetor cilindro 4",
            "P0300" to "Falha de ignição aleatória/múltipla",
            "P0301" to "Falha de ignição – cilindro 1", "P0302" to "Falha de ignição – cilindro 2",
            "P0303" to "Falha de ignição – cilindro 3", "P0304" to "Falha de ignição – cilindro 4",
            "P0325" to "Sensor de detonação – circuito",
            "P0335" to "Sensor de rotação/PMS – circuito",
            "P0340" to "Sensor de fase – circuito",
            "P0420" to "Eficiência do catalisador abaixo do limite",
            "P0443" to "Válvula do cânister – circuito",
            "P0500" to "Sensor de velocidade do veículo",
            "P0505" to "Controle da marcha lenta",
            "P0560" to "Tensão do sistema (bateria)",
            "P0601" to "Memória da central – erro",
        )

        fun describe(code: String) = DESC[code]
            ?: if (code.length > 1 && code[1] in "13") "Código específico da Renault" else "Descrição não cadastrada"
    }
}

/** Um valor do mapa Renault (SIM 32, programa D4, Vdiag 04). */
class Signal(
    val key: String, val name: String, val unit: String,
    val lid: Int, val start: Int, val len: Int, val dec: Int,
    val provisional: Boolean, val conv: (Int) -> Double?,
) {
    fun read(blocks: Map<Int, ByteArray>): Double? {
        val d = blocks[lid] ?: return null
        if (d.size < start + len) return null
        var raw = 0
        for (i in 0 until len) raw = (raw shl 8) or (d[start + i].toInt() and 0xFF)
        return conv(raw)
    }
}

object RenaultMap {
    val LIDS = listOf(0xA0, 0xA1, 0xA2, 0xA3, 0xA4)

    val SIGNALS = listOf(
        Signal("rpm", "Rotação", "rpm", 0xA2, 7, 2, 0, false) { it.toDouble() },
        Signal("map", "Pressão coletor", "mbar", 0xA0, 12, 2, 0, false) {
            val m = it * 0.0578125 + 103; if (m > 1100) null else m
        },
        Signal("agua", "Temp. motor", "°C", 0xA2, 13, 1, 0, true) { it * 0.625 - 40 },
        Signal("ar", "Temp. ar", "°C", 0xA2, 14, 1, 0, true) { it * 0.625 - 40 },
        Signal("pedal", "Pedal/borboleta", "%", 0xA3, 15, 1, 0, false) {
            ((it - 36) * 100.0 / 193).coerceIn(0.0, 100.0)
        },
        Signal("avanco", "Avanço ignição", "°", 0xA4, 15, 1, 1, false) { it * 0.375 - 23.625 },
        Signal("inj", "Tempo injeção", "ms", 0xA4, 13, 2, 2, false) { it * 0.004 },
        Signal("lambda", "Sonda lambda", "V", 0xA2, 11, 1, 2, false) { it * 0.00976 },
        Signal("corr", "Correção mistura", "%", 0xA2, 16, 1, 1, true) { (it - 128) * 100.0 / 128 },
        Signal("bat", "Bateria", "V", 0xA2, 15, 1, 1, false) { it * 0.032 + 8 },
    )
}
