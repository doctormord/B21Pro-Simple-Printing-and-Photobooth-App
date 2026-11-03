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

// BLE connection to the B21 Pro. The roll's RFID tag is read only to pick its paper profile; no label check.
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

    // Byte data[7] of the 1-bit print start (V4): 1 = clarity, 0 = what the manufacturer's app sends. Set in
    // the debug console. The gray print start always sends 1.
    var startByte: () -> Int = { 1 }
    // Patched firmware (loadheat): the print start carries 11 bytes (data[8] last, data[9] last0,
    // data[10] 1-bit heat). Original firmware reads only data[6] and data[7], so there the app sends the
    // manufacturer's 9 bytes. heat1Bit: percent of the original, 0 = original
    var patched: () -> Boolean = { false }
    var heat1Bit: () -> Int = { 0 }
    // The 11-byte print start got no answer but the 9-byte one did: the printer is not patched
    var onNotPatched: () -> Unit = {}
    // Sends a print start: the 11-byte form first when the printer is marked patched, then, if that gets
    // no answer, the 9-byte form (which also switches the printer profile to original)
    private suspend fun requestStart(long: IntArray, short: IntArray): Pair<ByteArray, Int> {
        if (!patched()) return request(Niimbot.PRINT_START, short) to short.size
        val first = runCatching { request(Niimbot.PRINT_START, long) }
        first.getOrNull()?.let { return it to long.size }
        log("No answer to the ${long.size}-byte print start, trying ${short.size} bytes")
        val r = request(Niimbot.PRINT_START, short)
        onNotPatched()
        return r to short.size
    }
    // Gray load balance of the loadbyte firmware: data[8] = last (levels 1-15), data[9] = last0 (level 0),
    // percent of the original, 0 = original
    var grayLoad: () -> Pair<Int, Int> = { 0 to 0 }
    // Head temperature read before the last colour print, and a hook for the app to judge it
    // (temperature, density) -> warning text or null
    var lastStartTempC: Int? = null
    var startTempCheck: (Int, Int) -> String? = { _, _ -> null }
    var notify: (String) -> Unit = {}
    // Name of the firmware file flashed last through the app (the printer reports 3.10 for every build)
    var firmwareNote: () -> String = { "" }
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
        val d7 = startByte().coerceIn(0, 1)
        val (r, n) = try {
            when (v) {
                // D110M_V4 start: copies, 0,0,0,0, colour mode, data[7], then the original's flag 0, or on patched
                // firmware last, last0 (both 0 in 1-bit) and the 1-bit heat. The manufacturer's app sends colour
                // mode 2 for 1-bit; on paper that only printed lighter, so it stays 0 (niimbluelib).
                Variant.V4 -> requestStart(
                    Niimbot.u16(copies) + intArrayOf(0, 0, 0, 0, 0, d7, 0, 0, heat1Bit().coerceIn(0, 255)),
                    Niimbot.u16(copies) + intArrayOf(0, 0, 0, 0, 0, d7, 0))
                Variant.B1 -> request(Niimbot.PRINT_START, Niimbot.u16(copies) + intArrayOf(0, 0, 0, 0, 0)).let { it to 7 }
                Variant.V1 -> request(Niimbot.PRINT_START, intArrayOf(1)).let { it to 1 }
            }
        } catch (e: Exception) { throw StartFailed(e.message ?: "") }
        log("Print start ${v.label}" + (if (v == Variant.V4) ", $n bytes, data[7]=$d7" + (if (n > 9) ", heat=${heat1Bit()}" else "") else "") + " → ${hex(r)}")
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
        readBattery()?.let { requireCharge(it) }
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

        // Several row packets per BLE write; the manufacturer's app also sends a whole label in under a
        // second without waiting
        val rows = Niimbot.encodeRows(img, w, height)
        val t0 = System.currentTimeMillis()
        val chunk = java.io.ByteArrayOutputStream()
        var writes = 0
        suspend fun flush() {
            if (chunk.size() == 0) return
            write(chunk.toByteArray())
            chunk.reset()
            // Short pause so the printer buffer does not overflow
            if (++writes % 8 == 0) delay(10)
        }
        rows.forEachIndexed { i, row ->
            if (chunk.size() + row.size > writePayload) flush()
            chunk.write(row)
            onProgress((i + 1f) / rows.size)
        }
        flush()
        log("Rows sent in ${System.currentTimeMillis() - t0} ms ($writes writes)")

        finishJob(copies, heartbeat = used == Variant.V4)
    }

    private suspend fun finishJob(copies: Int, heartbeat: Boolean) {
        var ok = false
        for (t in 0 until 10) {
            ok = runCatching { request(Niimbot.PAGE_END, intArrayOf(1), 1000)[0].toInt() == 1 }.getOrDefault(false)
            if (ok) break
            delay(300)
        }
        if (!ok) log("Page end not confirmed")

        // Wait until all copies are printed. The copy counter has been seen not to rise on some jobs
        // (the wait then runs its full minute), so the polls are logged to see which.
        var polls = 0; var last: ByteArray? = null
        val w0 = System.currentTimeMillis()
        for (t in 0 until 120) {
            polls = t + 1
            val s = runCatching { request(Niimbot.PRINT_STATUS, intArrayOf(1)) }.getOrNull()
            last = s
            if (s != null && s.size >= 2 && (((s[0].toInt() and 0xFF) shl 8) or (s[1].toInt() and 0xFF)) >= copies) break
            delay(500)
        }
        log("Waited for the printer: $polls polls, ${System.currentTimeMillis() - w0} ms, last status ${last?.let { hex(it) } ?: "none"}")
        // V4 sends a heartbeat before the end
        if (heartbeat) oneWay(Niimbot.HEARTBEAT, intArrayOf(1))
        runCatching { request(Niimbot.PRINT_END, intArrayOf(1)) }
        log("Print finished")
    }

    // Prints a rendered label in its ink: 1-bit, gray levels, or red/black (twoColorRoll also sends
    // plain 1-bit labels such as signs through the two-colour mode, all black)
    suspend fun printResult(
        result: ImageProcessor.Result, twoColorRoll: Boolean, density: Int, labelType: Int, copies: Int,
        onProgress: (Float) -> Unit,
    ) {
        val r = result.forPrint()
        val levels = r.levels
        if (levels == null && !twoColorRoll) return print(r.bits, r.width, r.height, density, labelType, copies, onProgress)
        for (c in 0 until copies) {
            val part: (Float) -> Unit = { f -> onProgress((c + f) / copies) }
            if (levels != null) {
                printGray(toColorHead(levels, r.width, r.height), r.height, density, labelType, part)
            } else {
                val red = r.red ?: ByteArray(r.bits.size)
                val black = ByteArray(r.bits.size) { if (r.bits[it].toInt() != 0 && red[it].toInt() == 0) 1 else 0 }
                printTwoColor(toColorHead(black, r.width, r.height), toColorHead(red, r.width, r.height),
                    r.height, density, labelType, part)
            }
        }
    }

    // The colour modes address 576 dots: narrower images are centred, the 591-dot width is trimmed evenly
    private fun toColorHead(a: ByteArray, width: Int, height: Int): ByteArray {
        val head = Niimbot.COLOR_HEAD
        if (width == head) return a
        val out = ByteArray(head * height)
        val n = minOf(width, head)
        val src = maxOf(0, (width - head) / 2); val dst = maxOf(0, (head - width) / 2)
        for (y in 0 until height) System.arraycopy(a, y * width + src, out, y * head + dst, n)
        return out
    }

    // 16-level gray (levels: 576 × height, 0..15)
    suspend fun printGray(
        levels: ByteArray, height: Int, density: Int, labelType: Int, onProgress: (Float) -> Unit,
    ) = printColor(Niimbot.PAGE_COLOR_GRAY, Niimbot.encodeGrayRows(levels, height), height, density, labelType, onProgress)

    // Black and red on a two-colour roll (planes: 576 × height, 1 = ink)
    suspend fun printTwoColor(black: ByteArray, red: ByteArray, height: Int, density: Int, labelType: Int, onProgress: (Float) -> Unit) =
        printColor(Niimbot.PAGE_COLOR_TWO, Niimbot.encodeTwoColorRows(black, red, height), height, density, labelType, onProgress)

    // Colour modes: V4 start with the colour in the page-colour byte and speed 0, page width 575 for gray
    // and 576 for red/black, then the rows.
    // These modes print slower than BLE delivers, and the printer silently drops rows once its buffer is
    // full. So rows go out in blocks of 100, and before each block the status
    // (bytes 4–5: free buffer rows, 508 when idle in gray mode) is polled until there is room again.
    private suspend fun printColor(
        color: Int, rows: List<Niimbot.Row>, height: Int, density: Int, labelType: Int, onProgress: (Float) -> Unit,
    ) {
        warmUp()
        logBattery("before")?.let {
            requireCharge(it)
            lastStartTempC = it.tempC
            if (color == Niimbot.PAGE_COLOR_GRAY) startTempCheck(it.tempC, density)?.let { m -> log("Warning: $m"); notify(m) }
        }
        // Which paper the printer thinks it has: the same job prints differently on different rolls
        readRoll()?.let { log("Roll tag: barcode ${it.barcode}, serial ${it.serial}, ${it.used} of ${it.total} used, paper type ${it.type}") }
            ?: log("Roll tag: none")
        log("Firmware file flashed last: ${firmwareNote().ifEmpty { "unknown" }}")
        log("Label type sent: $labelType, density $density, ${rows.size} row commands for $height rows")
        runCatching { request(Niimbot.SET_DENSITY, intArrayOf(density)) }
            .getOrElse { request(Niimbot.SET_DENSITY, intArrayOf(density)) }
        request(Niimbot.SET_LABEL_TYPE, intArrayOf(labelType))
        val colourSpeed = if (color == Niimbot.PAGE_COLOR_GRAY) 1 else 0
        val gray = color == Niimbot.PAGE_COLOR_GRAY
        val (last, last0) = if (gray) grayLoad().let { it.first.coerceIn(0, 255) to it.second.coerceIn(0, 255) } else 0 to 0
        // Gray on patched firmware: 11 bytes, 00 01 00 00 00 00 03 01 <last> <last0> 00; the original's form
        // is the 9 bytes 00 01 00 00 00 00 03 01 00. The other colour modes always send 9 bytes.
        val (r, n) = if (gray) requestStart(
            Niimbot.u16(1) + intArrayOf(0, 0, 0, 0, color, colourSpeed, last, last0, 0),
            Niimbot.u16(1) + intArrayOf(0, 0, 0, 0, color, colourSpeed, 0))
        else request(Niimbot.PRINT_START, Niimbot.u16(1) + intArrayOf(0, 0, 0, 0, color, colourSpeed, 0)) to 9
        log("Print start colour $color, $n bytes, data[7]=$colourSpeed" + (if (gray && n > 9) ", last=$last, last0=$last0" else "") + ", printer reply ${hex(r)}")
        if (r.isNotEmpty() && r[0].toInt() == 0) throw Exception("Printer rejected colour mode $color (${hex(r)})")
        oneWay(Niimbot.PRINT_STATUS, intArrayOf(1))
        val width = if (color == Niimbot.PAGE_COLOR_GRAY) Niimbot.COLOR_HEAD - 1 else Niimbot.COLOR_HEAD
        request(Niimbot.SET_PAGE_SIZE, Niimbot.u16(height) + Niimbot.u16(width) + Niimbot.u16(1) + IntArray(7))

        // Timing per block (ms since the first row: waiting for room, free rows, sending, sync reply), to
        // see whether the printer ran dry; a print that changes look mid-way points there
        val t0 = System.currentTimeMillis()
        var tBlock = t0; var free = -1; var waited = 0L
        rows.forEachIndexed { i, row ->
            if (i % BLOCK == 0) {
                val w0 = System.currentTimeMillis()
                free = waitForRoom(BLOCK)
                tBlock = System.currentTimeMillis(); waited = tBlock - w0
            }
            write(row.bytes)
            if (i % BLOCK == BLOCK - 1 && color == Niimbot.PAGE_COLOR_GRAY) {
                val s0 = System.currentTimeMillis()
                runCatching { request(Niimbot.PRINT_SYNC, Niimbot.u16(row.last) + 1, 15000) }
                    .onFailure { log("No sync reply at row ${row.last}") }
                val now = System.currentTimeMillis()
                log("Block to row ${row.last}: t=${s0 - t0} ms, waited $waited ms, free $free, sent ${s0 - tBlock} ms, sync ${now - s0} ms")
            } else if (i % 8 == 7) delay(10)
            onProgress((i + 1f) / rows.size)
        }
        finishJob(1, heartbeat = true)
        logBattery("after")
    }

    // Supply voltage and head temperature around a colour print: identical jobs printed up to twice as
    // dark, so what the printer reports about its supply is worth having next to the block timings
    // At battery level 0 the printer does not answer the print start (seen at 7061 mV; a test at 7072 mV
    // still printed), so say so up front instead of waiting for a timeout
    private fun requireCharge(b: Niimbot.Battery) {
        if (b.level == 0) {
            log("Battery level 0 (raw ${b.raw}): the printer refuses print jobs")
            throw Exception("Printer battery is empty (level 0, ${b.raw} mV). Charge it or connect power, then print again.")
        }
    }

    private suspend fun logBattery(tag: String): Niimbot.Battery? {
        // Right after a job the printer often does not answer yet
        var b = readBattery()
        if (b == null) { delay(1500); b = readBattery() }
        log(b?.let { "Battery $tag print: raw ${it.raw}, level ${it.level}, temp ${it.tempC} °C [${hex(it.bytes)}]" } ?: "Battery $tag print: no reply")
        return b
    }

    private val BLOCK = 100

    // Returns the free rows seen last (-1 when the printer does not tell)
    private suspend fun waitForRoom(rows: Int): Int {
        val until = System.currentTimeMillis() + 60000
        var free = -1
        while (System.currentTimeMillis() < until) {
            val s = runCatching { request(Niimbot.PRINT_STATUS, intArrayOf(1), 1000) }.getOrNull()
            if (s == null || s.size < 6) return -1     // firmware without the field: carry on
            free = ((s[4].toInt() and 0xFF) shl 8) or (s[5].toInt() and 0xFF)
            if (free >= rows) return free
            delay(150)
        }
        log("Printer buffer stayed full, sending anyway")
        return free
    }

    // Battery state from the heartbeat; null when the printer does not answer
    suspend fun readBattery(): Niimbot.Battery? =
        runCatching { request(Niimbot.HEARTBEAT, intArrayOf(1), 1500) }.getOrNull()?.let { Niimbot.parseBattery(it) }

    class Roll(val barcode: String, val serial: String, val total: Int, val used: Int, val type: Int)

    // The roll's RFID tag: 8-byte UID, barcode and serial (each length-prefixed), labels total and
    // used (u16), paper type. A first byte of 0 means no tag (no-name rolls).
    suspend fun readRoll(): Roll? {
        val d = runCatching { request(Niimbot.RFID_INFO, intArrayOf(1)) }.getOrNull() ?: return null
        if (d.isEmpty() || d[0].toInt() == 0) return null
        return runCatching {
            var i = 8
            fun u8() = d[i++].toInt() and 0xFF
            fun str() = u8().let { n -> String(d, i, n, Charsets.US_ASCII).also { i += n } }
            val barcode = str(); val serial = str()
            val total = (u8() shl 8) or u8(); val used = (u8() shl 8) or u8()
            Roll(barcode, serial, total, used, u8())
        }.getOrNull()?.takeIf { it.barcode.isNotBlank() }
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
