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
}
