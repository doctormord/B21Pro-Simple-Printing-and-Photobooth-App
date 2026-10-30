package de.b21print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// The last prints, kept as the finished dots (bits, gray levels, red plane) so a print can be
// repeated exactly, without the source photo or the settings it came from
object PrintArchive {
    private const val MAGIC = 0x42323150
    private const val MAX_JOBS = 20
    private const val THUMB = 480

    class Entry(
        val id: Long, val title: String, val twoColor: Boolean, val density: Int, val labelType: Int,
        val copies: Int, val parts: Int, val gray: Boolean, val job: File, val thumb: File,
    )

    private fun dir(c: Context) = File(c.filesDir, "prints").apply { mkdirs() }

    // parts: what goes to the printer one after another (several labels of a panorama);
    // preview: the whole picture, for the list
    fun save(
        c: Context, parts: List<ImageProcessor.Result>, preview: Bitmap, title: String,
        twoColor: Boolean, density: Int, labelType: Int, copies: Int,
    ) {
        val d = dir(c)
        val id = System.currentTimeMillis()
        val tmp = File(d, "$id.tmp")
        DataOutputStream(GZIPOutputStream(BufferedOutputStream(tmp.outputStream()))).use { o ->
            o.writeInt(MAGIC)
            o.writeLong(id)
            o.writeUTF(title)
            o.writeBoolean(twoColor)
            o.writeInt(density); o.writeInt(labelType); o.writeInt(copies)
            o.writeInt(parts.size)
            o.writeBoolean(parts[0].levels != null)
            for (p in parts) {
                o.writeInt(p.width); o.writeInt(p.height); o.writeInt(p.rot)
                o.writeBoolean(p.levels != null); o.writeBoolean(p.red != null)
                o.write(p.bits)
                p.levels?.let { o.write(it) }
                p.red?.let { o.write(it) }
            }
        }
        val scale = minOf(1f, THUMB.toFloat() / maxOf(preview.width, preview.height))
        val thumb = if (scale < 1f) Bitmap.createScaledBitmap(preview, (preview.width * scale).toInt().coerceAtLeast(1),
            (preview.height * scale).toInt().coerceAtLeast(1), true) else preview
        File(d, "$id.png").outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(File(d, "$id.job"))
        list(c).drop(MAX_JOBS).forEach { delete(it) }
    }

    private fun readHeader(i: DataInputStream, job: File, thumb: File): Entry {
        require(i.readInt() == MAGIC) { "Not a print file" }
        val id = i.readLong(); val title = i.readUTF(); val two = i.readBoolean()
        val density = i.readInt(); val type = i.readInt(); val copies = i.readInt()
        val n = i.readInt(); val gray = i.readBoolean()
        return Entry(id, title, two, density, type, copies, n, gray, job, thumb)
    }

    // Newest first
    fun list(c: Context): List<Entry> = (dir(c).listFiles { f -> f.extension == "job" } ?: emptyArray())
        .mapNotNull { f ->
            runCatching {
                DataInputStream(GZIPInputStream(BufferedInputStream(f.inputStream()))).use { readHeader(it, f, File(f.parentFile, f.nameWithoutExtension + ".png")) }
            }.getOrNull()
        }.sortedByDescending { it.id }

    fun load(e: Entry): List<ImageProcessor.Result> {
        val preview = BitmapFactory.decodeFile(e.thumb.path) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        DataInputStream(GZIPInputStream(BufferedInputStream(e.job.inputStream()))).use { i ->
            readHeader(i, e.job, e.thumb)
            return List(e.parts) {
                val w = i.readInt(); val h = i.readInt(); val rot = i.readInt()
                val hasLevels = i.readBoolean(); val hasRed = i.readBoolean()
                fun read() = ByteArray(w * h).also { a -> i.readFully(a) }
                val bits = read()
                val levels = if (hasLevels) read() else null
                val red = if (hasRed) read() else null
                ImageProcessor.Result(bits, w, h, preview, levels, red, rot)
            }
        }
    }

    fun delete(e: Entry) { e.job.delete(); e.thumb.delete() }
}
