package com.hmlai.agent

import android.content.Context
import org.json.JSONObject

/** Small local Skill library. A Skill is a named, previously successful autonomous goal. */
object SkillStore {
    private const val PREFS = "hml_skills"
    private const val KEY_LAST = "last_goal"
    private const val KEY_SKILLS = "skills"
    private const val MAX_SKILLS = 12

    fun setLastGoal(context: Context, goal: String) {
        if (goal.isNotBlank()) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST, goal.trim()).apply()
    }

    fun getLastGoal(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    fun save(context: Context, name: String, goal: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = try { JSONObject(prefs.getString(KEY_SKILLS, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        json.put(name.trim().lowercase(), goal.trim())
        val keys = json.keys().asSequence().toList()
        if (keys.size > MAX_SKILLS) {
            json.remove(keys.first())
        }
        prefs.edit().putString(KEY_SKILLS, json.toString()).apply()
    }

    fun get(context: Context, name: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = try { JSONObject(prefs.getString(KEY_SKILLS, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        return json.optString(name.trim().lowercase(), null)
    }
}
