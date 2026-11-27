package br.leitorecu

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sin

/** Canal de texto com o ELM327: envia um comando e espera o prompt '>'. */
abstract class Link {
    private val buf = StringBuilder()
    private val lock = Object()

    abstract val connected: Boolean
    protected abstract fun writeBytes(data: ByteArray)
    abstract fun close()

    protected fun onData(s: String) = synchronized(lock) {
        buf.append(s)
        lock.notifyAll()
    }

    protected fun wake() = synchronized(lock) { lock.notifyAll() }

    fun command(cmd: String, timeoutMs: Long): List<String> {
        synchronized(lock) { buf.setLength(0) }
        writeBytes((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val deadline = System.currentTimeMillis() + timeoutMs
        val text: String
        synchronized(lock) {
            while (buf.indexOf(">") < 0) {
                if (!connected) throw IOException("Conexão Bluetooth perdida")
                val rem = deadline - System.currentTimeMillis()
                if (rem <= 0) break
                lock.wait(minOf(rem, 200))
            }
            text = buf.toString()
        }
        val lines = text.replace(">", "").replace("\u0000", "")
            .split(Regex("[\r\n]+")).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (lines.isNotEmpty() && lines[0].replace(" ", "").equals(cmd.replace(" ", ""), true)) {
            lines.removeAt(0) // eco
        }
        return lines
    }
}

/** ELM327 Bluetooth LE (ex.: "OBDBLE": serviço FFF0, recebe em FFF1, envia em FFF2). */
@SuppressLint("MissingPermission")
class BleLink(private val ctx: Context, private val device: BluetoothDevice) : Link() {
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var noResponse = false
    @Volatile private var up = false
    private val ready = CountDownLatch(1)
    private val writeDone = Semaphore(0)

    override val connected get() = up

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else {
                up = false
                ready.countDown()
                wake()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val chars = g.services.flatMap { it.characteristics }
            val notifyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
            val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            val rx = pick(chars.filter { it.properties and notifyMask != 0 }, listOf("fff1", "ffe1"))
            val t = pick(chars.filter { it.properties and writeMask != 0 }, listOf("fff2", "ffe1"))
            if (rx == null || t == null) {
                ready.countDown(); return
            }
            tx = t
            noResponse = t.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            g.setCharacteristicNotification(rx, true)
            val d = rx.getDescriptor(CCCD)
            if (d == null) {
                up = true; ready.countDown(); return
            }
            val v = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, v)
            } else {
                @Suppress("DEPRECATION")
                d.value = v
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            up = true
            ready.countDown()
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            onData(String(v, Charsets.US_ASCII))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(String(value, Charsets.US_ASCII))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.release()
        }
    }

    private fun pick(list: List<BluetoothGattCharacteristic>, pref: List<String>): BluetoothGattCharacteristic? {
        for (p in pref) list.firstOrNull { it.uuid.toString().lowercase().startsWith("0000$p") }?.let { return it }
        return list.firstOrNull()
    }

    fun connect(): BleLink {
        gatt = if (Build.VERSION.SDK_INT >= 23)
            device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(ctx, false, cb)
        if (!ready.await(20, TimeUnit.SECONDS) || !up) {
            close()
            throw IOException("Não foi possível conectar ao adaptador (o celular está conectado em outro app?)")
        }
        return this
    }

    override fun writeBytes(data: ByteArray) {
        val g = gatt ?: throw IOException("Sem conexão")
        val c = tx ?: throw IOException("Sem conexão")
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var i = 0
        while (i < data.size) {
            val chunk = data.copyOfRange(i, minOf(i + 20, data.size))
            writeDone.drainPermits()
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, chunk, type)
            } else {
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = chunk
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            writeDone.tryAcquire(1, TimeUnit.SECONDS)
            i += 20
        }
    }

    override fun close() {
        up = false
        try {
            gatt?.disconnect(); gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        wake()
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/** Simula a central SIM 32 do Clio (mesmo layout de bytes do carro real). */
class DemoLink : Link() {
    private val t0 = System.currentTimeMillis()
    @Volatile private var open = true
    private var dtcs = "0133"
    override val connected get() = open
    override fun close() { open = false }

    override fun writeBytes(data: ByteArray) {
        Thread.sleep(60)
        // aceita também o pedido com o número de respostas no fim ("21A2 1" -> "21A2")
        val c = String(data, Charsets.US_ASCII).trim().uppercase().replace(Regex("^(21[0-9A-F]{2}) +1$"), "$1")
            .replace(" ", "")
        onData(resp(c) + "\r\r>")
    }

    private fun hex(b: IntArray) = b.joinToString("") { "%02X".format(it and 0xFF) }

    private fun resp(c: String): String {
        val t = (System.currentTimeMillis() - t0) / 1000.0
        val rev = max(0.0, sin(t / 5.0))                // acelera e solta em ciclos
        val rpm = (750 + 1900 * rev).toInt()
        return when {
            c == "ATZ" -> "ELM327 v1.5 (DEMO)"
            c == "ATDP" -> "AUTO, ISO 14230-4 (KWP FAST)"
            c.startsWith("AT") -> "OK"
            c == "0100" -> "410000000000"
            c.startsWith("01") -> "7F0112"
            c == "03" -> "43" + (dtcs + "00000000").take(12)
            c == "07" -> "47000000000000"
            c == "04" -> { dtcs = "0000"; "44" }
            c == "2180" -> "6180820053915204303031820040024600D450000203E2FF0000"
            c == "21A0" -> {
                val b = IntArray(24)
                val map = ((350 + 60 * rev - 103) / 0.0578125).toInt()
                b[12] = map shr 8; b[13] = map; hex(b).let { "61A0$it" }
            }
            c == "21A2" -> {
                val b = IntArray(22)
                b[0] = if (rev < 0.05) 0xF0 else 0x80   // pé levantado (bit 6), como no carro
                b[7] = rpm shr 8; b[8] = rpm
                b[2] = if (rev < 0.05) 0xD6 else 0x96
                b[11] = if ((t * 2).toInt() % 2 == 0) 15 else 80          // lambda oscilando
                b[13] = ((minOf(90.0, 70 + t / 6) + 40) / 0.625).toInt()  // água
                b[14] = ((46 + 40) / 0.625).toInt()                          // ar
                b[15] = ((13.7 - 8) / 0.032).toInt()                         // bateria
                b[16] = 138
                b[17] = 2; b[18] = 240                                         // PR041 = 752
                b[20] = (36 + 193 * rev * 0.4).toInt(); b[21] = b[20] + 2           // pistas do pedal
                "61A2" + hex(b)
            }
            c == "21A3" -> {
                val b = IntArray(24); b[15] = (36 + 193 * rev * 0.4).toInt(); b[18] = (32 + 10 * rev).toInt(); b[16] = 36; "61A3" + hex(b)
            }
            c == "21A4" -> {
                val b = IntArray(24)
                val inj = ((4.0 + 1.5 * rev) / 0.004).toInt()
                b[13] = inj shr 8; b[14] = inj
                b[15] = ((1.5 + 28 * rev + 23.625) / 0.375).toInt()
                "61A4" + hex(b)
            }
            c == "21A1" -> "61A1" + hex(IntArray(24))
            c.startsWith("21") -> "7F2112"
            else -> "?"
        }
    }
}
