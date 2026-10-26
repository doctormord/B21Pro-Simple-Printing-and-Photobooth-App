package de.b21print

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// A paper (roll type) with the tone curves measured on it in the app. A roll with an RFID tag is
// matched by its barcode when the printer connects; others are picked by hand. Without a profile
// (or without a curve for the mode and density) the built-in defaults apply: PrintCurve for 1-bit
// (Amiga fine falls back to the photo curve) and PrintCurve.GRAY for gray.
class PaperProfile(
    val id: String,
    var name: String,
    var barcode: String? = null,
    // Key: mode and density ("gray3", "mono3" for the 1-bit photo look, "bayer3" for Amiga fine).
    // Value: darkness per chart step, 0 = paper, 1 = the darkest step (gray: levels 0..15; 1-bit: the
    // 25 tones of TestChart.calibration, dithered with that look)
    val curves: MutableMap<String, FloatArray> = mutableMapOf(),
) {
    fun curve(mode: String, density: Int) = curves["$mode$density"]

    companion object {
        const val GRAY = "gray"
        const val MONO = "mono"
        const val BAYER = "bayer"

        fun load(prefs: SharedPreferences): MutableList<PaperProfile> {
            val arr = runCatching { JSONArray(prefs.getString("profiles", "[]")) }.getOrElse { JSONArray() }
            return MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val c = o.optJSONObject("curves") ?: JSONObject()
                PaperProfile(o.getString("id"), o.getString("name"), o.optString("barcode").ifEmpty { null },
                    c.keys().asSequence().associateWith { k ->
                        val a = c.getJSONArray(k); FloatArray(a.length()) { a.getDouble(it).toFloat() }
                    }.toMutableMap())
            }
        }

        fun save(prefs: SharedPreferences, list: List<PaperProfile>) {
            val arr = JSONArray()
            for (p in list) arr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); p.barcode?.let { put("barcode", it) }
                put("curves", JSONObject().apply {
                    for ((k, v) in p.curves) put(k, JSONArray().apply { v.forEach { put(Math.round(it * 1000) / 1000.0) } })
                })
            })
            prefs.edit().putString("profiles", arr.toString()).apply()
        }
    }
}
