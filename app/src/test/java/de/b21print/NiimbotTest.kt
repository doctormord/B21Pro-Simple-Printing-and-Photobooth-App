package de.b21print

import org.junit.Assert.assertEquals
import org.junit.Test

class NiimbotTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    @Test
    fun packets() {
        assertEquals("03 55 55 c1 01 01 c1 aa aa", hex(Niimbot.packet(Niimbot.CONNECT, intArrayOf(1))))
        assertEquals("55 55 21 01 03 23 aa aa", hex(Niimbot.packet(Niimbot.SET_DENSITY, intArrayOf(3))))
        val (p, rest) = Niimbot.parsePackets(byteArrayOf(0) + Niimbot.packet(0x31, intArrayOf(1)))
        assertEquals(0x31, p.single().cmd)
        assertEquals(0, rest.size)
    }

    @Test
    fun rows() {
        val w = 591; val h = 4
        val bm = ByteArray(w * h)
        for (x in 0 until w) { bm[x] = 1; bm[w + x] = 1 }
        bm[3 * w + 590] = 1
        val rows = Niimbot.encodeRows(bm, w, h)
        assertEquals(3, rows.size)
        assertEquals("55 55 85 50 00 00 00 4f 02 02 ff ff", hex(rows[0].copyOfRange(0, 12)))
        assertEquals("55 55 84 03 00 02 01 84 aa aa", hex(rows[1]))
        assertEquals("55 55 85 50 00 03 00 01 00 01 00 00", hex(rows[2].copyOfRange(0, 12)))
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    // Independent decoder of the row format; the encoder must round-trip through it
    @Test
    fun grayRoundTrip() {
        val w = Niimbot.COLOR_HEAD; val h = 300
        val levels = TestChart.grayWedge(h, "D3")
        val out = ByteArray(w * h)
        for (row in Niimbot.encodeGrayRows(levels, h)) {
            val p = row.bytes
            assertEquals(Niimbot.GRAY_ROWS, u(p, 2))
            val len = (u(p, 3) shl 8) or u(p, 4)
            var x = 0
            for (k in 2 until 5 + len) x = x xor u(p, k)
            assertEquals(x, u(p, 5 + len))
            val d = p.copyOfRange(5, 5 + len)
            if (u(d, 0) == 0x84) continue
            val y = (u(d, 1) shl 8) or u(d, 2)
            val n = u(d, 4)
            val runs = HashMap<Int, Int>()
            for (k in 0 until n step 2) runs[((u(d, 5 + k) and 1) shl 8) or u(d, 6 + k)] = (u(d, 5 + k) and 0xFE) - 2
            val px = ArrayList<Int>()
            for (i in 5 + n until d.size) {
                px += u(d, i) shr 4; px += u(d, i) and 15
                runs.remove(i - 5 - n)?.let { r -> repeat(r) { px += px.last() } }
            }
            assertEquals(w, px.size)
            for (xx in 0 until w) out[y * w + xx] = px[xx].toByte()
        }
        assert(levels.contentEquals(out))
    }

    @Test
    fun twoColorRoundTrip() {
        val w = Niimbot.COLOR_HEAD; val h = 400
        val (black, red) = TestChart.twoColor(h, "D3")
        val k = ByteArray(w * h); val r = ByteArray(w * h)
        val seen = HashSet<Int>()
        fun put(t: ByteArray, y: Int, bits: (Int) -> Boolean) { for (x in 0 until w) if (bits(x)) t[y * w + x] = 1 }
        for (row in Niimbot.encodeTwoColorRows(black, red, h)) {
            val p = row.bytes
            val d = p.copyOfRange(4, 4 + u(p, 3))
            when (u(d, 0)) {
                0 -> continue
                1, 2 -> {
                    val y = (u(d, 2) shl 8) or u(d, 3)
                    assert(seen.add(y))
                    put(if (u(d, 0) == 1) r else k, y) { x -> (u(d, 5 + (x shr 3)) shr (7 - (x and 7))) and 1 == 1 }
                }
                3 -> {
                    val y = (u(d, 1) shl 8) or u(d, 2)
                    assert(seen.add(y))
                    val all = d.copyOfRange(4, 76)
                    val mask = ByteArray(72)
                    var m = 76
                    for (i in 0 until 72) if (all[i].toInt() != 0) mask[i] = d[m++]
                    assertEquals(d.size, m)
                    fun bit(a: ByteArray, x: Int) = (a[x shr 3].toInt() shr (7 - (x and 7))) and 1 == 1
                    put(r, y) { x -> bit(all, x) && bit(mask, x) }
                    put(k, y) { x -> bit(all, x) && !bit(mask, x) }
                }
            }
        }
        assert(black.contentEquals(k))
        assert(ByteArray(w * h) { if (red[it].toInt() != 0 && black[it].toInt() == 0) 1 else 0 }.contentEquals(r))
    }
}
