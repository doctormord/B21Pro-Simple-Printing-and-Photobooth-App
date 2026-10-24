package de.b21print

import java.util.UUID

// NIIMBOT B21 Pro packet format: 55 55 CMD LEN DATA... XOR(CMD,LEN,DATA) AA AA
object Niimbot {
    val SERVICE_UUID: UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
    val CHAR_UUID: UUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DPI = 300
    const val PRINTHEAD_PIXELS = 591
    const val MAX_WIDTH_MM = 50.0
    const val MAX_HEIGHT_MM = 200.0

    const val CONNECT = 0xC1
    const val SET_DENSITY = 0x21
    const val SET_LABEL_TYPE = 0x23
    const val PRINT_START = 0x01
    const val PAGE_START = 0x03
    const val SET_PAGE_SIZE = 0x13
    const val SET_QUANTITY = 0x15
    const val PRINT_EMPTY_ROW = 0x84
    const val PRINT_BITMAP_ROW = 0x85
    const val PAGE_END = 0xE3
    const val PRINT_STATUS = 0xA3
    const val PRINT_END = 0xF3
    const val HEARTBEAT = 0xDC
    const val START_FW = 0xF5          // start firmware update (normal packet, [major,minor])
    // Firmware replies (CRC32 packets): 0x90 request CRC, 0x9A request chunk, 0x9D check result,
    // 0x9E final result. Commands: 0x91 CRC, 0x9B chunk, 0x9C no more chunks, 0x92 commit.
    const val RFID_INFO = 0x1A
    const val RFID_INFO2 = 0x1C
    const val ANTI_FAKE = 0x0B
    const val PRINTER_STATUS_DATA = 0xA5

    // Request -> Response
    val RESPONSE = mapOf(
        CONNECT to 0xC2, SET_DENSITY to 0x31, SET_LABEL_TYPE to 0x33, PRINT_START to 0x02,
        PAGE_START to 0x04, SET_PAGE_SIZE to 0x14, SET_QUANTITY to 0x16, PAGE_END to 0xE4, PRINT_STATUS to 0xB3,
        PRINT_END to 0xF4, HEARTBEAT to 0xDD,
    )

    class Packet(val cmd: Int, val data: ByteArray)

    fun packet(cmd: Int, data: IntArray = IntArray(0)): ByteArray {
        var chk = cmd xor data.size
        for (b in data) chk = chk xor b
        val out = ArrayList<Int>(data.size + 8)
        // The connect packet expects a 0x03 prefix
        if (cmd == CONNECT) out.add(0x03)
        out.addAll(listOf(0x55, 0x55, cmd, data.size))
        data.forEach { out.add(it and 0xFF) }
        out.addAll(listOf(chk and 0xFF, 0xAA, 0xAA))
        return ByteArray(out.size) { out[it].toByte() }
    }

    // Splits the buffer into packets; returns the packets and the unparsed rest
    fun parsePackets(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val packets = ArrayList<Packet>()
        var i = 0
        val u = { k: Int -> buf[k].toInt() and 0xFF }
        while (i + 7 <= buf.size) {
            if (u(i) != 0x55 || u(i + 1) != 0x55) { i++; continue }
            val cmd = u(i + 2)
            val len = u(i + 3)
            val end = i + 4 + len + 3
            if (end > buf.size) break
            var chk = cmd xor len
            for (k in 0 until len) chk = chk xor u(i + 4 + k)
            if (chk == u(i + 4 + len) && u(end - 2) == 0xAA && u(end - 1) == 0xAA) {
                packets.add(Packet(cmd, buf.copyOfRange(i + 4, i + 4 + len)))
                i = end
            } else {
                i++
            }
        }
        return packets to buf.copyOfRange(i, buf.size)
    }

    fun u16(n: Int) = intArrayOf((n shr 8) and 0xFF, n and 0xFF)

    // Firmware packet with CRC32 (update flow):
    // 55 55 CMD chunkHi chunkLo LEN DATA... CRC32(4, big-endian) AA AA
    // CRC32 over [CMD, chunkHi, chunkLo, LEN, DATA...]
    fun crc32Packet(cmd: Int, chunk: Int, data: ByteArray): ByteArray {
        val bodyLen = 1 + 2 + 1 + data.size
        val body = ByteArray(bodyLen)
        body[0] = cmd.toByte()
        body[1] = ((chunk shr 8) and 0xFF).toByte()
        body[2] = (chunk and 0xFF).toByte()
        body[3] = (data.size and 0xFF).toByte()
        System.arraycopy(data, 0, body, 4, data.size)
        val crc = java.util.zip.CRC32().apply { update(body) }.value.toInt()
        val out = ByteArray(2 + bodyLen + 4 + 2)
        out[0] = 0x55; out[1] = 0x55
        System.arraycopy(body, 0, out, 2, bodyLen)
        val p = 2 + bodyLen
        out[p] = ((crc ushr 24) and 0xFF).toByte()
        out[p + 1] = ((crc ushr 16) and 0xFF).toByte()
        out[p + 2] = ((crc ushr 8) and 0xFF).toByte()
        out[p + 3] = (crc and 0xFF).toByte()
        out[p + 4] = 0xAA.toByte(); out[p + 5] = 0xAA.toByte()
        return out
    }

    fun u32be(n: Int) = byteArrayOf(
        ((n ushr 24) and 0xFF).toByte(), ((n ushr 16) and 0xFF).toByte(),
        ((n ushr 8) and 0xFF).toByte(), (n and 0xFF).toByte(),
    )

    // Parses CRC32 firmware packets; returns (cmd, chunk, data) and the rest.
    // Firmware replies we care about (short packets, small LEN).
    private val FW_RESP = intArrayOf(0x90, 0x9A, 0x9D, 0x9E)

    // Skips foreign packets (e.g. the normal 0xF6 ack) and resyncs at the next 55 55
    // instead of getting stuck on a wrong length byte.
    fun parseCrc32Packets(buf: ByteArray): Pair<List<Triple<Int, Int, ByteArray>>, ByteArray> {
        val out = ArrayList<Triple<Int, Int, ByteArray>>()
        var i = 0
        val u = { k: Int -> buf[k].toInt() and 0xFF }
        fun nextHead(from: Int): Int {
            var k = from
            while (k + 1 < buf.size) { if (u(k) == 0x55 && u(k + 1) == 0x55) return k; k++ }
            return -1
        }
        while (i + 6 <= buf.size) {
            if (u(i) != 0x55 || u(i + 1) != 0x55) { i++; continue }
            val cmd = u(i + 2)
            if (cmd !in FW_RESP) {          // foreign packet (e.g. 0xF6): skip to the next header
                val n = nextHead(i + 2); if (n < 0) { i = buf.size; break }; i = n; continue
            }
            val len = u(i + 5)
            val end = i + 6 + len + 4 + 2
            if (end > buf.size) {           // maybe incomplete: another header follows? otherwise wait
                val n = nextHead(i + 2); if (n < 0) break else { i = n; continue }
            }
            if (u(end - 2) == 0xAA && u(end - 1) == 0xAA) {
                val chunk = (u(i + 3) shl 8) or u(i + 4)
                out.add(Triple(cmd, chunk, buf.copyOfRange(i + 6, i + 6 + len)))
                i = end
            } else {
                val n = nextHead(i + 2); if (n < 0) break else i = n
            }
        }
        return out to buf.copyOfRange(i, buf.size)
    }

    // bitmap: 1 = black, width*height row by row. Identical rows are merged.
    fun encodeRows(bitmap: ByteArray, width: Int, height: Int): List<ByteArray> {
        val rowBytes = (width + 7) / 8
        val rows = Array(height) { y ->
            val row = ByteArray(rowBytes)
            for (x in 0 until width) {
                if (bitmap[y * width + x].toInt() != 0) {
                    row[x shr 3] = (row[x shr 3].toInt() or (0x80 shr (x and 7))).toByte()
                }
            }
            row
        }
        // Pixel count per print head third when the row fits (as in niimbluelib)
        val chunk = PRINTHEAD_PIXELS / 8 / 3
        val canSplit = rowBytes <= chunk * 3
        val out = ArrayList<ByteArray>()
        var y = 0
        while (y < height) {
            var rep = 1
            while (y + rep < height && rep < 255 && rows[y].contentEquals(rows[y + rep])) rep++
            val row = rows[y]
            var total = 0
            val parts = IntArray(3)
            row.forEachIndexed { i, b ->
                val c = Integer.bitCount(b.toInt() and 0xFF)
                total += c
                if (canSplit) parts[minOf(2, i / chunk)] += c
            }
            if (total == 0) {
                out.add(packet(PRINT_EMPTY_ROW, u16(y) + rep))
            } else {
                // Pixel count: split into thirds, otherwise the total
                val counts = if (canSplit && parts.all { it <= 255 }) parts
                else intArrayOf(0, total and 0xFF, (total shr 8) and 0xFF)
                val rowInts = IntArray(rowBytes) { row[it].toInt() and 0xFF }
                out.add(packet(PRINT_BITMAP_ROW, u16(y) + counts + rep + rowInts))
            }
            y += rep
        }
        return out
    }
}
