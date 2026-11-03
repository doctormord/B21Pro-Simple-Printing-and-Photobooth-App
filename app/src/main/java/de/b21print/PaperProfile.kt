package de.b21print

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// A paper (roll type) with the tone curves measured on it in the app. A roll with an RFID tag is
// matched by its barcode when the printer connects; others are picked by hand. Without a profile
// (or without a curve for the mode and density) the built-in defaults apply: PrintCurve for 1-bit
// (the other calibratable looks fall back to the photo curve, coarse Amiga to none) and PrintCurve.GRAY for gray.
class PaperProfile(
    val id: String,
    var name: String,
    var barcode: String? = null,
    // Key: mode and density ("gray3", "mono3" for the 1-bit photo look, "bayer3" Amiga fine, "blue3"
    // blue noise, "riemersma3", "amiga3" coarse Amiga).
    // Value: darkness per chart step, 0 = paper, 1 = the darkest step (gray: levels 0..15; 1-bit: the
    // 25 tones of TestChart.calibration, dithered with that look)
    val curves: MutableMap<String, FloatArray> = mutableMapOf(),
    // Gray load balance (last, last0; percent of the original, 0 = original) the gray curve was measured
    // with, same keys as curves. A curve only fits the load it was calibrated with.
    val load: MutableMap<String, IntArray> = mutableMapOf(),
    // 1-bit heat (percent of the original, 0 = original) the 1-bit curves were measured with, same keys as curves
    val heat: MutableMap<String, Int> = mutableMapOf(),
    // Head temperature (°C) when the chart for that curve was printed; the printer lowers the heating
    // when the head is warm, so a curve only fits prints that start at about the same temperature
    val calTemp: MutableMap<String, Int> = mutableMapOf(),
) {
    fun curve(mode: String, density: Int) = curves["$mode$density"]

    // Gray only: the density to take the curve from. The exact one when calibrated, else the nearest
    // calibrated density of this paper (lower one on a tie), else null
    fun grayDensity(density: Int): Int? =
        if (curves.containsKey("$GRAY$density")) density
        else (0..15).filter { curves.containsKey("$GRAY$it") }.minWithOrNull(compareBy({ Math.abs(it - density) }, { it }))

    companion object {
        const val GRAY = "gray"
        const val MONO = "mono"
        const val BAYER = "bayer"
        const val BLUE = "blue"
        const val RIEMERSMA = "riemersma"
        const val AMIGA = "amiga"

        fun load(prefs: SharedPreferences): MutableList<PaperProfile> {
            val arr = runCatching { JSONArray(prefs.getString("profiles", "[]")) }.getOrElse { JSONArray() }
            return MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val c = o.optJSONObject("curves") ?: JSONObject()
                PaperProfile(o.getString("id"), o.getString("name"), o.optString("barcode").ifEmpty { null },
                    c.keys().asSequence().associateWith { k ->
                        val a = c.getJSONArray(k); FloatArray(a.length()) { a.getDouble(it).toFloat() }
                    }.toMutableMap(),
                    (o.optJSONObject("load") ?: JSONObject()).let { h -> h.keys().asSequence().associateWith { k -> h.getJSONArray(k).let { a -> intArrayOf(a.getInt(0), a.getInt(1)) } }.toMutableMap() },
                    (o.optJSONObject("heat") ?: JSONObject()).let { h -> h.keys().asSequence().associateWith { h.getInt(it) }.toMutableMap() },
                    (o.optJSONObject("calTemp") ?: JSONObject()).let { h -> h.keys().asSequence().associateWith { h.getInt(it) }.toMutableMap() })
            }
        }

        fun save(prefs: SharedPreferences, list: List<PaperProfile>) {
            val arr = JSONArray()
            for (p in list) arr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); p.barcode?.let { put("barcode", it) }
                put("load", JSONObject().apply { for ((k, v) in p.load) put(k, JSONArray().put(v[0]).put(v[1])) })
                put("heat", JSONObject().apply { for ((k, v) in p.heat) put(k, v) })
                put("calTemp", JSONObject().apply { for ((k, v) in p.calTemp) put(k, v) })
                put("curves", JSONObject().apply {
                    for ((k, v) in p.curves) put(k, JSONArray().apply { v.forEach { put(Math.round(it * 1000) / 1000.0) } })
                })
            })
            prefs.edit().putString("profiles", arr.toString()).apply()
        }
    }
}
