package fr.decmike.lecteur

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Recent(val uri: String, val name: String, val time: Long)

/** Historique des fichiers ouverts via le sélecteur (stocké localement). */
object Recents {
    private const val PREFS = "recents"
    private const val KEY = "list"
    private const val MAX = 30

    fun list(ctx: Context): List<Recent> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Recent(o.getString("uri"), o.getString("name"), o.optLong("time"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, items: List<Recent>) {
        val a = JSONArray()
        items.take(MAX).forEach { a.put(JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.time)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun add(ctx: Context, uri: Uri, name: String) {
        val items = list(ctx).filter { it.uri != uri.toString() }.toMutableList()
        items.add(0, Recent(uri.toString(), name, System.currentTimeMillis()))
        save(ctx, items)
    }

    fun remove(ctx: Context, uri: String) = save(ctx, list(ctx).filter { it.uri != uri })

    fun clear(ctx: Context) = save(ctx, emptyList())
}
