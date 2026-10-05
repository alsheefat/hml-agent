package com.hmlai.agent

import android.content.Context
import org.json.JSONArray

/**
 * Small, explicit long-term memory store kept entirely on-device.
 * Only memories the user explicitly asks HML to remember are persisted.
 */
object MemoryStore {
    private const val PREFS = "hml_agent_memory"
    private const val KEY = "items"
    private const val MAX_ITEMS = 40

    fun all(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, memory: String): Boolean {
        val value = memory.trim().trimEnd('.', '!', '?')
        if (value.isBlank()) return false
        val current = all(context).filterNot { it.equals(value, ignoreCase = true) }.toMutableList()
        current.add(0, value)
        persist(context, current.take(MAX_ITEMS))
        return true
    }

    fun remove(context: Context, query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return false
        val current = all(context)
        val filtered = current.filterNot {
            it.equals(q, ignoreCase = true) || it.contains(q, ignoreCase = true)
        }
        if (filtered.size == current.size) return false
        persist(context, filtered)
        return true
    }

    fun clear(context: Context) = persist(context, emptyList())

    fun asPromptText(context: Context): String = all(context)
        .take(20)
        .joinToString("\n") { "- $it" }

    private fun persist(context: Context, values: List<String>) {
        val array = JSONArray()
        values.forEach(array::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
