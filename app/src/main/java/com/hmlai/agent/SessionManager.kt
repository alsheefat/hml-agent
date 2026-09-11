package com.hmlai.agent

import android.content.Context

/** Tiny wrapper around SharedPreferences that remembers whether someone is logged in. */
object SessionManager {
    private const val PREFS = "hml_agent_session"

    fun saveSession(context: Context, name: String, subtitle: String) {
        prefs(context).edit()
            .putBoolean("logged_in", true)
            .putString("name", name)
            .putString("subtitle", subtitle)
            .apply()
    }

    fun isLoggedIn(context: Context): Boolean =
        prefs(context).getBoolean("logged_in", false)

    fun getName(context: Context): String =
        prefs(context).getString("name", "My Agent") ?: "My Agent"

    fun getSubtitle(context: Context): String =
        prefs(context).getString("subtitle", "Personal workspace") ?: "Personal workspace"

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
