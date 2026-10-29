package com.timecable.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random

data class Item(val id: String, val title: String, val file: String, val durMs: Long) {
    val url: String get() = "https://archive.org/download/$id/${Uri.encode(file, "/")}"
}

/** Internet Archive client: search -> per-item metadata (file + duration) -> on-disk cache. */
object Archive {
    private const val TARGET = 24
    private val FORMATS = listOf("h.264", "MPEG4", "h.264 IA", "512Kb MPEG4", "h.264 HD")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "TimeCable/1.0 (Android TV)")
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun titleOf(t: Any?): String = when (t) {
        is JSONArray -> t.optString(0)
        is String -> t
        else -> ""
    }

    private fun search(q: String): List<Pair<String, String>> {
        val u = Uri.parse("https://archive.org/advancedsearch.php").buildUpon()
            .appendQueryParameter("q", q)
            .appendQueryParameter("fl[]", "identifier")
            .appendQueryParameter("fl[]", "title")
            .appendQueryParameter("sort[]", "downloads desc")
            .appendQueryParameter("rows", "150")
            .appendQueryParameter("page", "1")
            .appendQueryParameter("output", "json")
            .build().toString()
        val docs = JSONObject(get(u)).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            d.getString("identifier") to titleOf(d.opt("title"))
        }
    }

    private fun parseLen(s: String): Double {
        if (s.isBlank()) return 0.0
        if (!s.contains(':')) return s.toDoubleOrNull() ?: 0.0
        return s.split(':').fold(0.0) { acc, p -> acc * 60 + (p.toDoubleOrNull() ?: 0.0) }
    }

    private fun resolve(id: String, title: String): Item? {
        val files = JSONObject(get("https://archive.org/metadata/$id")).optJSONArray("files") ?: return null
        for (fmt in FORMATS) {
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                if (f.optString("format") != fmt) continue
                val ms = (parseLen(f.optString("length")) * 1000).toLong()
                if (ms in 60_000L..14_400_000L) {
                    return Item(id, title.ifBlank { id }, f.getString("name"), ms)
                }
            }
        }
        return null
    }

    fun readCache(dir: File, key: String): List<Item> = try {
        val a = JSONArray(File(dir, "ch_$key.json").readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Item(o.getString("id"), o.getString("t"), o.getString("f"), o.getLong("d"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun writeCache(dir: File, key: String, items: List<Item>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("f", it.file).put("d", it.durMs)) }
        try { File(dir, "ch_$key.json").writeText(a.toString()) } catch (e: Exception) { }
    }

    /** Returns the channel's playable items; calls onUpdate as the list grows. Append-only so schedules stay stable. */
    suspend fun load(dir: File, ch: ChannelDef, bad: Set<String>, onUpdate: (List<Item>) -> Unit): List<Item> =
        withContext(Dispatchers.IO) {
            val items = readCache(dir, ch.callsign).filter { it.id !in bad }.toMutableList()
            if (items.isNotEmpty()) onUpdate(items.toList())
            if (items.size >= TARGET) return@withContext items.toList()
            val skip = items.map { it.id }.toSet() + bad
            val pool = try { search(ch.query) } catch (e: Exception) { return@withContext items.toList() }
            val order = pool.filter { it.first !in skip }.shuffled(Random(ch.callsign.hashCode().toLong()))
            for (batch in order.chunked(4)) {
                if (items.size >= TARGET) break
                val got = batch.map { (id, t) ->
                    async { try { resolve(id, t) } catch (e: Exception) { null } }
                }.awaitAll().filterNotNull()
                if (got.isNotEmpty()) {
                    items.addAll(got)
                    writeCache(dir, ch.callsign, items)
                    onUpdate(items.toList())
                }
            }
            items.toList()
        }
}
