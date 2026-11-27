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

    /**
     * "Só 1 resposta": o ELM327 aceita um dígito no fim do pedido ("21A2 1") dizendo quantas respostas esperar.
     * Sem isso, depois de receber a resposta ele ainda espera o tempo-limite para ver se vem outra (nas capturas:
     * ~246 ms por pedido, dos quais só ~60–80 ms são da central). null = ainda testando; false = o clone não aceita.
     */
    @Volatile var oneResponse: Boolean? = null; private set

    /** Tempo médio por pedido à central (ms), medido. */
    @Volatile var msPerRead = 0.0; private set

    private fun parse61(lines: List<String>, target: String): ByteArray? {
        for (l in hexLines(lines)) {
            val i = l.indexOf(target)
            if (i >= 0) {
                val d = l.substring(i + 4)
                return hexToBytes(d.substring(0, d.length / 2 * 2))
            }
        }
        return null
    }

    /** Serviço Renault 21 (ReadDataByLocalIdentifier): devolve os bytes depois de "61 xx". */
    fun renaultRead(lid: Int, quiet: Boolean = true): ByteArray? {
        val target = "61%02X".format(lid)
        val base = "21%02X".format(lid)
        val t0 = System.currentTimeMillis()
        var r: ByteArray? = null
        if (oneResponse != false) {
            r = parse61(send("$base 1", 3000, quiet), target)
            if (r != null) oneResponse = true
        }
        if (r == null) {
            r = parse61(send(base, 3000, quiet), target)
            // só desiste do modo rápido se o pedido normal funcionou e o rápido não (clone não aceita)
            if (r != null && oneResponse == null) oneResponse = false
        }
        if (r != null) {
            val dt = (System.currentTimeMillis() - t0).toDouble()
            msPerRead = if (msPerRead == 0.0) dt else msPerRead * 0.8 + dt * 0.2
        }
        return r
    }

    fun ident(): String? {
        val d = renaultRead(0x80, false) ?: return null
        if (d.size < 18) return null
        fun hx(a: Int, b: Int) = d.copyOfRange(a, b).joinToString("") { "%02X".format(it) }
        val sup = String(d.copyOfRange(6, 9), Charsets.US_ASCII)
        return "Ref. ${hx(0, 5)} • HW ${hx(9, 14)} • Programa ${hx(14, 16)} • Versão ${hx(16, 18)} • " +
                "Diag %02X • Fornecedor %s".format(d[5], sup)
    }

    /** Identificação completa (2180) campo a campo, no layout Renault. */
    fun identDetails(): List<Pair<String, String>> {
        val d = renaultRead(0x80, false) ?: return listOf("Identificação" to "sem resposta")
        fun hx(a: Int, b: Int) = if (d.size >= b) d.copyOfRange(a, b).joinToString("") { "%02X".format(it) } else "--"
        val out = mutableListOf(
            "Referência Renault da central" to hx(0, 5),
            "Versão do diagnóstico (Vdiag)" to hx(5, 6),
            "Fornecedor" to (if (d.size >= 9) String(d.copyOfRange(6, 9), Charsets.US_ASCII) + " (001 = Siemens)" else "--"),
            "Número de hardware" to hx(9, 14),
            "Número do programa" to hx(14, 16),
            "Versão do software" to hx(16, 18),
            "Número de calibração" to hx(18, 20),
            "Calibração de ajuste" to hx(20, 21),
            "Calibração do sistema" to hx(21, 22),
        )
        out += "Resposta completa (2180)" to d.joinToString(" ") { "%02X".format(it) }
        return out
    }

    /** Lê um identificador local qualquer e devolve hex + texto legível (para VIN, rastreabilidade...). */
    fun readInfo(lid: Int): String? {
        val d = renaultRead(lid, false) ?: return null
        val hex = d.joinToString(" ") { "%02X".format(it) }
        val ascii = d.map { val c = it.toInt() and 0xFF; if (c in 32..126) c.toChar() else '·' }.joinToString("")
        return "$hex\n\"$ascii\""
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

        // Quadro de falhas da SIM32 D4 Vdiag 04 (manual Renault 17B): DTC OBD -> código Renault DF
        private val DESC = mapOf(
            "P0110" to "DF003 – Circuito do sensor de temperatura do ar",
            "P0115" to "DF004 – Circuito do sensor de temperatura da água",
            "P0325" to "DF006 – Circuito do sensor de detonação",
            "P0627" to "DF008 – Circuito de comando do relé da bomba de combustível",
            "P0685" to "DF009 – Circuito de comando do relé principal (atuadores)",
            "P0480" to "DF010 – Circuito do eletroventilador – velocidade baixa",
            "P0443" to "DF014 – Circuito da eletroválvula de purga do cânister",
            "P0135" to "DF018 – Aquecimento da sonda lambda anterior",
            "P0633" to "DF021 – Imobilizador (antiarranque)",
            "P0606" to "DF022 – Central de injeção (calculador)",
            "P0335" to "DF025 – Circuito do sensor de rotação (volante do motor)",
            "P0481" to "DF030 – Circuito do eletroventilador – velocidade alta",
            "P0141" to "DF038 – Aquecimento da sonda lambda posterior",
            "P0105" to "DF045 – Circuito do sensor de pressão do coletor",
            "P0201" to "DF052 – Circuito do injetor – cilindro 1",
            "P0202" to "DF053 – Circuito do injetor – cilindro 2",
            "P0203" to "DF054 – Circuito do injetor – cilindro 3",
            "P0204" to "DF055 – Circuito do injetor – cilindro 4",
            "P0130" to "DF057 – Circuito da sonda lambda anterior",
            "P0136" to "DF058 – Circuito da sonda lambda posterior",
            "P0351" to "DF061 – Circuito da bobina de ignição 1-4",
            "P0352" to "DF062 – Circuito da bobina de ignição 2-3",
            "P0500" to "DF064 – Sinal de velocidade do veículo",
            "P0313" to "DF079 – Falha de combustão com combustível na reserva",
            "P0504" to "DF095 – Informação dos freios (interruptor do pedal)",
            "P0641" to "DF096 – Tensão de alimentação nº 1 dos sensores",
            "P0651" to "DF097 – Tensão de alimentação nº 2 dos sensores",
            "P0301" to "DF111 – Falha de combustão – cilindro 1",
            "P0302" to "DF112 – Falha de combustão – cilindro 2",
            "P0303" to "DF113 – Falha de combustão – cilindro 3",
            "P0304" to "DF114 – Falha de combustão – cilindro 4",
            "P0170" to "DF116 – Falha funcional do circuito de combustível (mistura)",
            "P0530" to "DF118 – Circuito do sensor de pressão do gás do A/C",
            "P0650" to "DF120 – Circuito da luz de anomalia (OBD)",
            "P0120" to "DF123 – Potenciômetro da borboleta – pista 1",
            "P0220" to "DF124 – Potenciômetro da borboleta – pista 2",
            "P0225" to "DF125 – Potenciômetro do pedal – pista 1",
            "P2120" to "DF126 – Potenciômetro do pedal – pista 2",
            "U0101" to "DF128 – Comunicação CAN com câmbio automático/robotizado",
            "P0560" to "DF157 – Tensão da bateria",
            "P0300" to "DF165 – Detecção de falhas de combustão",
            "P0460" to "DF187 – Informação de nível mínimo de combustível",
            "P0315" to "DF189 – Coroa dentada do volante (aprendizado)",
            "P0655" to "DF215 – Circuito da luz de gravidade 2",
            "P0638" to "DF216 – Corpo de borboleta motorizada",
            "P0645" to "DF228 – Comando do compressor do A/C",
            "U0122" to "DF233 – Controle de trajetória (ESP)",
            "P2101" to "DF254 – Comando da borboleta motorizada",
            "U0155" to "DF344 – Comunicação injeção – painel de instrumentos",
            "U0302" to "DF360 – Função câmbio automático",
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

    /** Corte de injeção de verdade (desaceleração): tempo de injeção ~0 com o motor girando. */
    fun fuelCut(b: Map<Int, ByteArray>): Boolean {
        fun s(k: String) = SIGNALS.firstOrNull { it.key == k }?.read(b)
        val rpm = s("rpm") ?: return false
        if (rpm <= 900) return false
        val inj = s("inj")
        if (inj != null && inj < 0.3) return true
        // se a central continuar informando um tempo de injeção no corte, a sonda denuncia: sem combustível ela
        // cai para perto de 0 V (só ar) com o pé solto e a regulação desligada
        val lam = s("lambda")
        return lam != null && lam < 0.15 && s("st_pe") == 1.0 && s("st_inj") == 0.0
    }

    /** Central regulando a mistura pela sonda (malha fechada). */
    fun closedLoop(b: Map<Int, ByteArray>) = SIGNALS.firstOrNull { it.key == "st_inj" }?.read(b) == 1.0

    val SIGNALS = listOf(
        Signal("rpm", "Rotação", "rpm", 0xA2, 7, 2, 0, false) { it.toDouble() },
        Signal("map", "Pressão coletor", "mbar", 0xA0, 12, 2, 0, false) {
            val m = it * 0.0578125 + 103; if (m > 1100) null else m
        },
        // confirmado na partida a frio (26/11/2026): água 20 °C e ar 21 °C, iguais ao ambiente
        Signal("agua", "Temp. motor", "°C", 0xA2, 13, 1, 0, false) { it * 0.625 - 40 },
        Signal("ar", "Temp. ar", "°C", 0xA2, 14, 1, 0, false) { it * 0.625 - 40 },
        // PR126 – manual Renault: 14 % com o pé levantado (byte 36 × 100/256 = 14 %)
        Signal("pedal", "Pedal acelerador", "%", 0xA3, 15, 1, 0, false) { it * 100.0 / 256 },
        // PR206/PR207 – pistas 1 e 2 do pedal (14 % em repouso, como no manual)
        // pistas do pedal: NÃO estão na escala do PR126 (pedal no fundo: PR126 89,5 % e pistas 75–77 %) e chegam atrasadas
        Signal("pedal1", "Pedal pista 1", "% (escala própria)", 0xA2, 20, 1, 0, true) { it * 100.0 / 256 },
        Signal("pedal2", "Pedal pista 2", "% (escala própria)", 0xA2, 21, 1, 0, true) { it * 100.0 / 256 },
        Signal("avanco", "Avanço ignição", "°", 0xA4, 15, 1, 1, false) { it * 0.375 - 23.625 },
        Signal("inj", "Tempo injeção", "ms", 0xA4, 13, 2, 2, false) { it * 0.004 },
        Signal("lambda", "Sonda lambda", "V", 0xA2, 11, 1, 2, false) { it * 0.00976 },
        // PR041 rotação de marcha lenta desejada: manual "PR041 = 752 r.p.m." – medido exatamente 752
        Signal("rpm_alvo", "Marcha lenta desejada", "rpm", 0xA2, 17, 2, 0, false) { it.toDouble() },
        // PR008 posição aprendida do pedal solto: manual 14 % – medido 36 × 100/256 = 14,1 %
        Signal("pedal_aprend", "Pedal solto (aprendido)", "%", 0xA3, 16, 1, 1, false) { it * 100.0 / 256 },
        // PR010 sonda lambda posterior (provável): vizinha da anterior; 488 mV desligado / 462 mV em lenta
        // (manual: ~500 mV / 400 mV). Sem sonda traseira instalada, mostra a tensão de repouso da entrada.
        // fica parada em 0,45–0,49 V ligado e desligado: tensão de referência de entrada vazia. Carro BR antes do OBDBr-2
        // normalmente só tem a sonda dianteira, então provavelmente não há sonda traseira instalada
        Signal("lambda2", "Sonda traseira (prov. não instalada)", "V", 0xA2, 12, 1, 2, true) { it * 0.00976 },
        // PR209 sinal de detonação (provável, valor bruto): 0 com motor parado, 72–111 em lenta, sobe com a rotação
        Signal("knock", "Sinal de detonação", "bruto", 0xA4, 16, 1, 0, true) { it.toDouble() },
        // Borboleta (PR017?) – o manual dá 13 % com motor parado e este byte dá 0: pode ser o COMANDO da borboleta
        // motorizada, e não a posição medida. Em lenta bate (12 %). 12,1–12,9 % em marcha lenta (manual: 12 %). 0 com motor parado.
        Signal("borb", "Borboleta medida", "%", 0xA3, 18, 1, 1, true) { it * 100.0 / 256 },
        // Estados (bits) – deduzidos das capturas, coerentes amostra a amostra
        // 21A2 byte 2 bit 7: NÃO é "injeção ativa". Nas capturas, quando ele vai a 0 a injeção continua (2–3,5 ms) e a
        // sonda segue rica; o que acontece junto é a correção da riqueza travar em 128 (neutra). Ou seja, é a
        // regulação de riqueza (malha fechada pela sonda). O corte de injeção de verdade = tempo de injeção ~0 ms.
        Signal("st_inj", "Malha fechada (sonda)", "1 = regulando / 0 = malha aberta", 0xA2, 2, 1, 0, true) {
            if (it and 0x80 != 0) 1.0 else 0.0
        },
        Signal("st_lenta", "Regulação marcha lenta", "1 = ativa", 0xA2, 2, 1, 0, true) {
            if (it and 0x40 != 0) 1.0 else 0.0
        },
        // 21A2 byte 0 (cópia em 21A3 byte 0): 240 (F0) pé solto, 128 (80) pisando, 131 (83) pedal no fundo – nas 3
        // capturas, inclusive com o motor desligado. (Antes estava em 21A0 byte 0, que fica sempre 0: erro corrigido.)
        Signal("st_pe", "Pé levantado", "1 = sim", 0xA2, 0, 1, 0, true) {
            if (it and 0x40 != 0) 1.0 else 0.0
        },
        Signal("st_fundo", "Pé no fundo", "1 = pedal no fundo", 0xA2, 0, 1, 0, true) {
            if (it and 0x02 != 0) 1.0 else 0.0
        },
        // PR035 "Valor de corrección de riqueza" – o manual mostra o valor bruto (128 = neutro)
        Signal("corr", "Correção riqueza", "128 = neutro", 0xA2, 16, 1, 0, false) { it.toDouble() },
        Signal("bat", "Bateria", "V", 0xA2, 15, 1, 1, false) { it * 0.032 + 8 },
    )
}
