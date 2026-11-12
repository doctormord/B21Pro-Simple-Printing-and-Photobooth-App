package de.b21print

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.util.regex.Pattern

// Licenses of the bundled libraries. The AboutLibraries Gradle plugin lists them at build time in
// res/raw/aboutlibraries.json; it runs offline, so the list has no license texts. The texts that have
// to ship with the app (Apache-2.0, libyuv's BSD license) are in res/raw; ML Kit and the Android SDK
// parts are under Google's terms, which are only linked.
object Licenses {
    // short: the SPDX id where there is one (the plugin keys other licenses by a hash)
    private class License(val name: String, val short: String, val url: String, val text: Int?)
    private class Lib(val name: String, val id: String, val version: String, val licenses: List<License>)

    private fun textFor(key: String, url: String) = when {
        key == "Apache-2.0" -> R.raw.license_apache_2_0
        "libyuv" in url -> R.raw.license_libyuv
        else -> null
    }

    private fun load(c: Context): List<Lib> {
        val json = JSONObject(c.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() })
        val known = json.getJSONObject("licenses")
        val libs = json.getJSONArray("libraries")
        return (0 until libs.length()).map { i ->
            val o = libs.getJSONObject(i)
            val keys = o.optJSONArray("licenses")
            val lic = (0 until (keys?.length() ?: 0)).map { k ->
                val key = keys!!.getString(k)
                val l = known.optJSONObject(key)
                val url = l?.optString("url").orEmpty()
                val name = l?.optString("name") ?: key
                License(name, if (key.matches(Regex("[0-9a-f]{32}"))) name else key, url, textFor(key, url))
            }
            Lib(o.optString("name").ifEmpty { o.getString("uniqueId") }, o.getString("uniqueId"),
                o.optString("artifactVersion"), lic)
        }.sortedBy { it.name.lowercase() }
    }

    // The texts are wrapped at 80 columns, which breaks badly on a phone: one line per paragraph
    private fun reflow(t: String) = t.trim().split(Regex("\n\\s*\n"))
        .joinToString("\n\n") { p -> p.lines().joinToString(" ") { it.trim() } }

    fun show(c: Context) {
        val libs = load(c)
        val rows = libs.map {
            val head = "${it.name} ${it.version}"
            // names like "common" only say what they are with the group
            SpannableString("$head\n${it.id.substringBefore(':')} · ${it.licenses.joinToString { l -> l.short }}").apply {
                setSpan(RelativeSizeSpan(0.8f), head.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        MaterialAlertDialogBuilder(c)
            .setTitle(R.string.oss_licenses)
            .setItems(rows.toTypedArray<CharSequence>()) { _, i -> showLib(c, libs[i]) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showLib(c: Context, lib: Lib) {
        val text = buildString {
            append(lib.id).append(' ').append(lib.version).append("\n\n")
            for (l in lib.licenses) {
                append(l.name).append('\n')
                if (l.url.isNotEmpty()) append(l.url).append('\n')
                l.text?.let { append('\n').append(reflow(c.resources.openRawResource(it).bufferedReader().use { r -> r.readText() })) }
                append('\n')
            }
        }
        val pad = (20 * c.resources.displayMetrics.density).toInt()
        val tv = TextView(c).apply {
            setText(text)
            setPadding(pad, pad / 2, pad, 0)
            textSize = 12f
            // only real addresses: WEB_URLS would also link "com.google" in the artifact id
            Linkify.addLinks(this, Pattern.compile("https?://\\S+"), null)
            movementMethod = LinkMovementMethod.getInstance()
        }
        MaterialAlertDialogBuilder(c)
            .setTitle(lib.name)
            .setView(ScrollView(c).apply { addView(tv) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
