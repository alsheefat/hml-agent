package com.hmlai.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingTask(
    val id: String,
    val goal: String,
    val triggerAtMillis: Long,
    val status: String = "scheduled"
)

/** Lightweight persistent queue for scheduled autonomous missions. */
object ScheduledTaskStore {
    private const val PREFS = "hml_scheduled_tasks"
    private const val KEY = "tasks"

    fun add(context: Context, task: PendingTask) {
        val array = read(context)
        array.put(JSONObject().apply {
            put("id", task.id)
            put("goal", task.goal)
            put("trigger", task.triggerAtMillis)
            put("status", task.status)
        })
        write(context, array)
    }

    fun markRunning(context: Context, id: String) = updateStatus(context, id, "running")
    fun markFinished(context: Context, id: String) = updateStatus(context, id, "finished")
    fun markFailed(context: Context, id: String) = updateStatus(context, id, "failed")

    fun pending(context: Context): List<PendingTask> {
        val array = read(context)
        val out = mutableListOf<PendingTask>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("status", "scheduled") == "scheduled") {
                out.add(PendingTask(
                    o.optString("id"),
                    o.optString("goal"),
                    o.optLong("trigger"),
                    o.optString("status")
                ))
            }
        }
        return out.sortedBy { it.triggerAtMillis }
    }

    private fun updateStatus(context: Context, id: String, status: String) {
        val array = read(context)
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("id") == id) o.put("status", status)
        }
        write(context, array)
    }

    private fun read(context: Context): JSONArray = try {
        JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
    } catch (_: Exception) { JSONArray() }

    private fun write(context: Context, array: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
