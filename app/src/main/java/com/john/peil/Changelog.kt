package com.john.peil

import android.content.Context
import org.json.JSONArray

/** Versionsverlauf aus assets/changelog.json – neueste Version zuerst. */
object Changelog {
    class Release(val version: String, val date: String, val changes: List<String>)

    fun load(context: Context): List<Release> = runCatching {
        val json = context.assets.open("changelog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val c = o.getJSONArray("changes")
            Release(o.getString("version"), o.optString("date"), (0 until c.length()).map { c.getString(it) })
        }
    }.getOrDefault(emptyList())
}
