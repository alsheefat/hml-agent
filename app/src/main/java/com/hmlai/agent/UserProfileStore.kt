package com.hmlai.agent

import android.content.Context

/**
 * On-device memory of the user's identity/preferences. This is the client's
 * half of "remembering" the user — it persists locally and gets attached to
 * every request in sendToServer(). For it to actually change what the AI
 * says, hml-agent-server has to read the "profile" field it's sent in and
 * fold it into the model's system prompt; this store alone can't do that.
 */
object UserProfileStore {
    private const val PREFS = "hml_agent_profile"
    private const val KEY_NAME_EN = "name_en"
    private const val KEY_NAME_BN = "name_bn"
    private const val KEY_NOTES = "notes"

    fun getNameEn(context: Context): String = prefs(context).getString(KEY_NAME_EN, "") ?: ""
    fun getNameBn(context: Context): String = prefs(context).getString(KEY_NAME_BN, "") ?: ""
    fun getNotes(context: Context): String = prefs(context).getString(KEY_NOTES, "") ?: ""

    fun setNameEn(context: Context, name: String) {
        if (name.isBlank()) return
        prefs(context).edit().putString(KEY_NAME_EN, name.trim()).apply()
    }

    fun setNameBn(context: Context, name: String) {
        if (name.isBlank()) return
        prefs(context).edit().putString(KEY_NAME_BN, name.trim()).apply()
    }

    /** Seeds a starting profile the first time the app runs, so memory works
     * immediately without requiring manual setup first. */
    fun seedDefaultsIfEmpty(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_NAME_EN)) {
            p.edit()
                .putString(KEY_NAME_EN, "Heemel")
                .putString(KEY_NAME_BN, "শাহ্‌রিজ আল সিফাত হিমেল")
                .putString(
                    KEY_NOTES,
                    "The honorific \"MD\" is part of this user's name (e.g. \"MD Himel\") " +
                        "and should be kept as-is, especially in Bangla replies — don't drop or alter it."
                )
                .apply()
        }
    }

    /** Picks up a plain "my name is X" / "আমার নাম X" statement typed in
     * chat and updates the remembered name automatically, going forward. */
    fun maybeLearnNameFrom(context: Context, message: String) {
        Regex("(?i)my name is ([\\p{L}.\\s]{2,40})").find(message)?.let {
            setNameEn(context, it.groupValues[1].trim().trimEnd('.', ',', '!'))
        }
        Regex("আমার নাম(?:\\s+হলো|\\s+হচ্ছে)?\\s+([^।\\n]{2,40})").find(message)?.let {
            setNameBn(context, it.groupValues[1].trim())
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
