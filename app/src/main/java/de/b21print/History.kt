package de.b21print

import android.content.SharedPreferences
import org.json.JSONArray

// Print history (last 50 signs) and saved templates
class History(private val prefs: SharedPreferences) {
    private fun load(key: String): MutableList<TextStyle> {
        val arr = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrElse { JSONArray() }
        return MutableList(arr.length()) { TextStyle.fromJson(arr.getJSONObject(it)) }
    }

    private fun save(key: String, list: List<TextStyle>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    val recent: List<TextStyle> get() = load(RECENT)
    val templates: List<TextStyle> get() = load(TEMPLATES)

    fun addRecent(s: TextStyle) {
        val list = load(RECENT)
        list.removeAll { it == s }
        list.add(0, s)
        save(RECENT, list.take(50))
    }

    fun isTemplate(s: TextStyle) = load(TEMPLATES).contains(s)

    fun toggleTemplate(s: TextStyle): Boolean {
        val list = load(TEMPLATES)
        val added = !list.remove(s)
        if (added) list.add(0, s)
        save(TEMPLATES, list)
        return added
    }

    fun removeRecent(s: TextStyle) = save(RECENT, load(RECENT).apply { remove(s) })

    companion object {
        private const val RECENT = "history_recent"
        private const val TEMPLATES = "history_templates"
    }
}
