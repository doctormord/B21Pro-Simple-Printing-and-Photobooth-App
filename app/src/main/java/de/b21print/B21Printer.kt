package de.b21print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

// BLE connection to the B21 Pro. No RFID query, no label check.
@SuppressLint("MissingPermission")
class B21Printer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onDisconnect: () -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private var ch: BluetoothGattCharacteristic? = null
    @Volatile private var writePayload = 20   // usable bytes per BLE write (MTU-3)
    private var rx = ByteArray(0)
    private val gattLock = Mutex()
    private var pendingOp: CompletableDeferred<Int>? = null
    private var connected: CompletableDeferred<Unit>? = null
    private val waiters = mutableListOf<Pair<Int, CompletableDeferred<ByteArray>>>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected?.completeExceptionally(Exception("Connection failed ($status)"))
                pendingOp?.completeExceptionally(Exception("Disconnected"))
                g.close()
                gatt = null
                onDisconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            connected?.complete(Unit)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            writePayload = (mtu - 3).coerceAtLeast(20)
            pendingOp?.complete(mtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onData(c.value ?: return)
        }
    }

    // Raw receive hook for the debug console: sees every incoming byte,
    // including text replies that do not follow the 0x55 protocol.
    var rawListener: ((ByteArray) -> Unit)? = null

    // Send raw bytes (e.g. a text command for the firmware debug console)
    suspend fun sendRaw(bytes: ByteArray) = write(bytes)

    // Firmware mode: replies are CRC32 packets (different framing)
    @Volatile private var fwMode = false
    private var fwRx = ByteArray(0)
    // Lossless inbox: every firmware packet is buffered so nothing is dropped between two
    // waits (the printer pipelines chunk requests).
    private val fwInbox = ArrayDeque<Triple<Int, Int, ByteArray>>()
    private var fwSignal: CompletableDeferred<Unit>? = null

    private fun onData(chunk: ByteArray) {
        rawListener?.let { runCatching { it(chunk) } }
        if (fwMode) { onFwData(chunk); return }
        val (packets, rest) = Niimbot.parsePackets(rx + chunk)
        rx = if (rest.size > 4096) ByteArray(0) else rest
        synchronized(waiters) {
            for (p in packets) {
                val i = waiters.indexOfFirst { it.first == p.cmd }
                if (i >= 0) waiters.removeAt(i).second.complete(p.data)
                // Surface unexpected replies (e.g. error codes)
                else if (verbose || (p.cmd != 0xDD && p.cmd != 0xD3)) log("RX 0x%02X %s".format(p.cmd, hex(p.data)))
            }
        }
    }

    private fun onFwData(chunk: ByteArray) {
        val (packets, rest) = Niimbot.parseCrc32Packets(fwRx + chunk)
        fwRx = if (rest.size > 1024) ByteArray(0) else rest
        if (packets.isEmpty()) return
        synchronized(fwInbox) {
            for (p in packets) fwInbox.addLast(p)
            fwSignal?.complete(Unit)
        }
    }

    private suspend fun fwWait(cmds: IntArray, timeoutMs: Long): Triple<Int, Int, ByteArray> {
        try {
            return withTimeout(timeoutMs) {
                while (true) {
                    val signal: CompletableDeferred<Unit>
                    synchronized(fwInbox) {
                        val i = fwInbox.indexOfFirst { it.first in cmds }
                        if (i >= 0) {
                            val p = fwInbox.removeAt(i)
                            return@withTimeout p
                        }
                        signal = CompletableDeferred()
                        fwSignal = signal
                    }
                    signal.await()
                }
                @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
            }
        } catch (e: TimeoutCancellationException) {
            throw Exception("Firmware timeout (waiting for ${cmds.joinToString { "0x%02X".format(it) }})")
        }
    }

    // The printer often drops the first packet after a pause; a heartbeat wakes the link.
    private suspend fun warmUp() {
        repeat(3) { runCatching { request(Niimbot.HEARTBEAT, intArrayOf(1), 1200) }.onSuccess { return } }
    }

    // Firmware update over BLE. data = complete .bin including header, version e.g. 3.10.
    // The printer erases its staging area first (slow), receives chunks, checks the CRC, then commits.
    suspend fun firmwareUpgrade(
        data: ByteArray, major: Int, minor: Int, onProgress: (Int, Int) -> Unit,
    ) {
        val chunkSize = 200
        val total = (data.size + chunkSize - 1) / chunkSize
        val fileCrc = java.util.zip.CRC32().apply { update(data) }.value.toInt()
        log("Firmware: start (version $major.$minor, ${data.size} bytes, $total chunks)")
        warmUp()                                     // wake the link
        fwRx = ByteArray(0)
        synchronized(fwInbox) { fwInbox.clear(); fwSignal = null }
        fwMode = true
        try {
            // Send start several times, the first packet is often lost
            var started = false
            for (t in 0 until 6) {
                write(Niimbot.packet(Niimbot.START_FW, intArrayOf(major, minor)))
                if (runCatching { fwWait(intArrayOf(0x90), 4000) }.isSuccess) { started = true; break }
                log("Firmware: no reply to start attempt ${t + 1}, retrying …")
            }
            if (!started) throw Exception("Printer does not answer the update start (0x90)")
            write(Niimbot.crc32Packet(0x91, 0, Niimbot.u32be(fileCrc)))
            log("Firmware: CRC sent, printer is erasing …")
            var timeout = 90000L                      // generous, erasing the staging area takes a while
            while (true) {
                val (cmd, chunk, d) = fwWait(intArrayOf(0x9A, 0x9E), timeout)
                timeout = 20000L
                if (cmd == 0x9E) {
                    if (d.isEmpty() || d[0].toInt() == 0) throw Exception("Printer rejected the update (version/CRC)")
                    break
                }
                val off = chunk * chunkSize
                if (off >= data.size) break
                write(Niimbot.crc32Packet(0x9B, chunk, data.copyOfRange(off, minOf(off + chunkSize, data.size))))
                onProgress(chunk + 1, total)
            }
            write(Niimbot.crc32Packet(0x9C, 0, byteArrayOf(1)))     // no more chunks
            val chk = fwWait(intArrayOf(0x9D), 30000).third
            if (chk.isEmpty() || chk[0].toInt() != 1) throw Exception("CRC check in the printer failed")
            log("Firmware: CRC ok, committing …")
            write(Niimbot.crc32Packet(0x92, 0, byteArrayOf(1)))     // Commit
            val res = fwWait(intArrayOf(0x9E), 30000).third
            if (res.isEmpty() || res[0].toInt() != 1) throw Exception("Firmware rejected (commit)")
            log("Firmware: done, printer is rebooting")
        } finally {
            fwMode = false
            fwRx = ByteArray(0)
            synchronized(fwInbox) { fwInbox.clear(); fwSignal = null }
        }
    }

    // Runs one GATT operation and waits for its callback
    private suspend fun gattOp(start: () -> Boolean): Int = gattLock.withLock {
        val op = CompletableDeferred<Int>()
        pendingOp = op
        if (!start()) throw Exception("GATT operation rejected")
        withTimeout(5000) { op.await() }
    }

    suspend fun connect(device: BluetoothDevice) {
        val c = CompletableDeferred<Unit>()
        connected = c
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(15000) { c.await() }
        val g = gatt ?: throw Exception("Not connected")
        val characteristic = g.getService(Niimbot.SERVICE_UUID)?.getCharacteristic(Niimbot.CHAR_UUID)
            ?: throw Exception("Printer service not found. Is this a B21?")
        ch = characteristic
        gattOp { g.requestMtu(247) }
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(Niimbot.CCCD_UUID)
        gattOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
        request(Niimbot.CONNECT, intArrayOf(1))
        log("Connected: ${device.name}")
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    private suspend fun write(bytes: ByteArray) {
        // Split large packets (e.g. firmware chunks) to the MTU
        if (bytes.size > writePayload) {
            var off = 0
            while (off < bytes.size) {
                val end = minOf(off + writePayload, bytes.size)
                writeOne(bytes.copyOfRange(off, end))
                off = end
            }
        } else {
            writeOne(bytes)
        }
    }

    private suspend fun writeOne(bytes: ByteArray) {
        val g = gatt ?: throw Exception("Not connected")
        val c = ch ?: throw Exception("Not connected")
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gattOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    suspend fun request(cmd: Int, data: IntArray, timeoutMs: Long = 3000): ByteArray {
        val expect = Niimbot.RESPONSE.getValue(cmd)
        val d = CompletableDeferred<ByteArray>()
        val entry = expect to d
        synchronized(waiters) { waiters.add(entry) }
        try {
            write(Niimbot.packet(cmd, data))
            return withTimeout(timeoutMs) { d.await() }
        } catch (e: Exception) {
            synchronized(waiters) { waiters.remove(entry) }
            if (e is TimeoutCancellationException) throw Exception("Timeout waiting for 0x%02X".format(cmd))
            throw e
        }
    }

    // Print sequence per firmware generation. According to niimbluelib/Thermalith the B21 Pro
    // uses the "D110M-v4" sequence (no PageStart); the others are kept as fallbacks.
    enum class Variant(val label: String) { V4("V4"), B1("B1"), V1("B21 legacy") }

    var variant: Variant? = null

    // Print speed, part of the V4 (D110M_V4) print start: 1 = clarity (default), 0 = speed.
    var speed = 1
    // Log every reply (diagnostics)
    var verbose = false
    var onVariantFound: (Variant) -> Unit = {}

    private class StartFailed(msg: String) : Exception(msg)

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    // Command without waiting for a reply (some firmwares do not answer)
    private suspend fun oneWay(cmd: Int, data: IntArray) {
        runCatching { request(cmd, data, 500) }
    }

    private suspend fun startPage(v: Variant, width: Int, height: Int, copies: Int) {
        val ps = when (v) {
            // 9-byte D110M_V4 start: copies, 0,0,0,0, pageColor(0), speed, flag(0)
            Variant.V4 -> Niimbot.u16(copies) + intArrayOf(0, 0, 0, 0, 0, speed, 0)
            Variant.B1 -> Niimbot.u16(copies) + intArrayOf(0, 0, 0, 0, 0)
            Variant.V1 -> intArrayOf(1)
        }
        val r = runCatching { request(Niimbot.PRINT_START, ps) }.getOrElse { throw StartFailed(it.message ?: "") }
        log("Print start ${v.label} → ${hex(r)}")
        if (r.isNotEmpty() && r[0].toInt() == 0) throw StartFailed("Print start rejected (${hex(r)})")
        if (v == Variant.V4) {
            // V4: query status, then a 13-byte page size with copy count, no PageStart
            oneWay(Niimbot.PRINT_STATUS, intArrayOf(1))
            val size = Niimbot.u16(height) + Niimbot.u16(width) + Niimbot.u16(copies) +
                intArrayOf(0, 0, 0, 0, 0, 0, 0)
            runCatching { request(Niimbot.SET_PAGE_SIZE, size) }.getOrElse { throw StartFailed(it.message ?: "") }
            return
        }
        runCatching { request(Niimbot.PAGE_START, intArrayOf(1)) }.getOrElse { throw StartFailed(it.message ?: "") }
        val size = if (v == Variant.B1) Niimbot.u16(height) + Niimbot.u16(width) + Niimbot.u16(copies)
        else Niimbot.u16(height)
        runCatching { request(Niimbot.SET_PAGE_SIZE, size) }.getOrElse { throw StartFailed(it.message ?: "") }
        if (v == Variant.V1) runCatching { request(Niimbot.SET_QUANTITY, Niimbot.u16(copies)) }
    }

    // Center narrower images on the full print head width (the protocol has no x offset)
    private fun padToHead(bitmap: ByteArray, width: Int, height: Int): ByteArray {
        val head = Niimbot.PRINTHEAD_PIXELS
        if (width >= head) return bitmap
        val left = (head - width) / 2
        val out = ByteArray(head * height)
        for (y in 0 until height) System.arraycopy(bitmap, y * width, out, y * head + left, width)
        return out
    }

    suspend fun print(
        bitmap: ByteArray, width: Int, height: Int,
        density: Int, labelType: Int, copies: Int,
        onProgress: (Float) -> Unit,
    ) {
        val img = padToHead(bitmap, width, height)
        val w = maxOf(width, Niimbot.PRINTHEAD_PIXELS)

        warmUp()   // the first packet after a pause is often lost
        // Density with one retry in case the first command is swallowed
        val d = runCatching { request(Niimbot.SET_DENSITY, intArrayOf(density)) }
            .getOrElse { request(Niimbot.SET_DENSITY, intArrayOf(density)) }
        val l = request(Niimbot.SET_LABEL_TYPE, intArrayOf(labelType))
        if (d.firstOrNull()?.toInt() == 0 || l.firstOrNull()?.toInt() == 0) {
            log("Note: density ${hex(d)}, paper type ${hex(l)}")
        }

        val order = listOfNotNull(variant) + Variant.entries.filter { it != variant }
        var used: Variant? = null
        for (v in order) {
            try {
                startPage(v, w, height, copies)
                if (variant != v) { variant = v; onVariantFound(v) }
                log("Protocol ${v.label} ok")
                used = v
                break
            } catch (e: StartFailed) {
                log("Protocol ${v.label}: ${e.message}")
                runCatching { request(Niimbot.PRINT_END, intArrayOf(1), 1500) }
                delay(400)
            }
        }
        if (used == null) throw Exception("Printer accepts no print job, see log")

        val rows = Niimbot.encodeRows(img, w, height)
        rows.forEachIndexed { i, row ->
            write(row)
            // Short pause so the printer buffer does not overflow
            if (i % 8 == 7) delay(10)
            onProgress((i + 1f) / rows.size)
        }

        var ok = false
        for (t in 0 until 10) {
            ok = runCatching { request(Niimbot.PAGE_END, intArrayOf(1), 1000)[0].toInt() == 1 }.getOrDefault(false)
            if (ok) break
            delay(300)
        }
        if (!ok) log("Page end not confirmed")

        // Wait until all copies are printed
        for (t in 0 until 120) {
            val s = runCatching { request(Niimbot.PRINT_STATUS, intArrayOf(1)) }.getOrNull()
            if (s != null && s.size >= 2 && (((s[0].toInt() and 0xFF) shl 8) or (s[1].toInt() and 0xFF)) >= copies) break
            delay(500)
        }
        // V4 sends a heartbeat before the end
        if (used == Variant.V4) oneWay(Niimbot.HEARTBEAT, intArrayOf(1))
        runCatching { request(Niimbot.PRINT_END, intArrayOf(1)) }
        log("Print finished")
    }

    // Queries what the printer reports about the roll and authenticity check; raw replies go to the log
    suspend fun diagnose() {
        verbose = true
        try {
            for ((name, cmd, data) in listOf(
                Triple("RFID info", Niimbot.RFID_INFO, intArrayOf(1)),
                Triple("RFID info 2", Niimbot.RFID_INFO2, intArrayOf(1)),
                Triple("Anti-Fake", Niimbot.ANTI_FAKE, intArrayOf(1)),
                Triple("Heartbeat", Niimbot.HEARTBEAT, intArrayOf(1)),
                Triple("Status", Niimbot.PRINTER_STATUS_DATA, intArrayOf(1)),
            )) {
                log("→ $name")
                write(Niimbot.packet(cmd, data))
                delay(800)
            }
        } finally {
            verbose = false
        }
    }
}
