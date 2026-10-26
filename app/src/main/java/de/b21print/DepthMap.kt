package de.b21print

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Depth map of Samsung portrait photos ("Live focus"). The camera appends its extra data to the
// JPEG in a trailer (SEF): the file ends with 'SEFT', before that the length of a directory that
// starts with 'SEFH'. Each entry points back from the directory to a block: 4 bytes, the name
// length, the name, then the data. "DualShot_DepthMap_1" is raw 8 bits per pixel, as many as fit
// the photo's aspect (1024 × 768 for 4:3), in the stored orientation of the JPEG, dark = near.
// Other phones store depth differently (Google: Dynamic Depth); not supported.
class DepthMap(
    // Nearness 0..255 (255 = nearest) in gray, aligned with the decoded photo
    val bitmap: Bitmap,
    // Suggested subject boundary (0..1 of the nearness): the Otsu split of the histogram
    val cut: Float,
) {
    companion object {
        private const val ENTRY = "DualShot_DepthMap_1"

        // photoW/photoH: the decoded photo (EXIF rotation applied). Null if there is no depth map.
        fun read(cr: ContentResolver, uri: Uri, photoW: Int, photoH: Int): DepthMap? = runCatching {
            val raw = cr.openFileDescriptor(uri, "r")?.let { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch -> sefEntry(ch, ENTRY) }.also { pfd.close() }
            } ?: return null
            val orientation = cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            // The map has the aspect of the stored (unrotated) photo
            val aspect = if (rotation % 180 != 0) photoH.toDouble() / photoW else photoW.toDouble() / photoH
            val w = sqrt(raw.size * aspect).roundToInt()
            if (w <= 0 || raw.size % w != 0 || abs(raw.size / w * aspect - w) > 2) return null
            fromRaw(raw, w, raw.size / w, rotation)
        }.getOrNull()

        private fun sefEntry(ch: java.nio.channels.FileChannel, name: String): ByteArray? {
            fun read(pos: Long, n: Int): ByteBuffer {
                val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
                var p = pos
                while (b.hasRemaining()) { if (ch.read(b, p).also { p += it } < 0) break }
                return b.flip() as ByteBuffer
            }
            val size = ch.size()
            if (size < 16) return null
            val tail = read(size - 8, 8)
            val dirLen = tail.int
            if (String(ByteArray(4).also { tail.get(it) }, Charsets.US_ASCII) != "SEFT") return null
            val dirPos = size - 8 - dirLen
            if (dirLen < 12 || dirPos < 0) return null
            val dir = read(dirPos, dirLen)
            if (String(ByteArray(4).also { dir.get(it) }, Charsets.US_ASCII) != "SEFH") return null
            dir.int // version
            val count = dir.int
            repeat(count.coerceAtMost((dirLen - 12) / 12)) {
                dir.short; dir.short // padding, type
                val offset = dir.int.toLong(); val len = dir.int
                val pos = dirPos - offset
                if (pos < 0 || len < 8) return@repeat
                val head = read(pos, 8); head.int
                val nameLen = head.int
                if (nameLen != name.length || len < 8 + nameLen) return@repeat
                if (String(read(pos + 8, nameLen).array(), Charsets.US_ASCII) != name) return@repeat
                return read(pos + 8 + nameLen, len - 8 - nameLen).array()
            }
            return null
        }

        // Flips the map to nearness, stretches it over 1 %..99 % so the boundary slider covers the
        // depth range of this photo, and turns it like the photo
        fun fromRaw(raw: ByteArray, w: Int, h: Int, rotation: Int): DepthMap? {
            val hist = IntArray(256)
            for (b in raw) hist[255 - (b.toInt() and 0xff)]++
            fun pct(q: Double): Int {
                var acc = 0
                for (i in 0..255) { acc += hist[i]; if (acc >= raw.size * q) return i }
                return 255
            }
            val lo = pct(0.01); val hi = pct(0.99)
            if (hi - lo < 8) return null
            val lut = IntArray(256) { v -> ((v - lo) * 255f / (hi - lo)).roundToInt().coerceIn(0, 255) }
            val px = IntArray(w * h) { i -> lut[255 - (raw[i].toInt() and 0xff)].let { Color.rgb(it, it, it) } }
            var bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            if (rotation != 0) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            }
            val stretched = IntArray(256)
            for (v in 0..255) stretched[lut[v]] += hist[v]
            return DepthMap(bmp, otsu(stretched) / 255f)
        }

        private fun otsu(hist: IntArray): Int {
            val total = hist.sum().toDouble()
            val sumAll = hist.indices.sumOf { it.toDouble() * hist[it] }
            var wB = 0.0; var sumB = 0.0; var best = 0.0; var t = 128
            for (i in 0 until 256) {
                wB += hist[i]; if (wB == 0.0 || wB == total) continue
                sumB += i.toDouble() * hist[i]
                val mB = sumB / wB; val mF = (sumAll - sumB) / (total - wB)
                val between = wB * (total - wB) * (mB - mF) * (mB - mF)
                if (between > best) { best = between; t = i }
            }
            return t
        }
    }
}
