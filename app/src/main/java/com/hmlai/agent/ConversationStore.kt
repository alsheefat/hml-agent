package com.hmlai.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Durable local conversation store.
 *
 * Stage 23 keeps the old SharedPreferences format as a migration source, but
 * stores each conversation in its own JSON file from now on. This avoids one
 * growing SharedPreferences blob taking the whole history down when chats get
 * long, and makes writes atomic per conversation.
 */
object ConversationStore {
    private const val PREFS = "hml_agent_conversations"
    private const val KEY = "conversations"
    private const val MIGRATED_KEY = "file_store_migrated_v1"
    private const val MAX_SAVED = 100

    private fun dir(context: Context): File = File(context.filesDir, "hml_conversations").apply { mkdirs() }

    fun loadAll(context: Context): MutableList<Conversation> {
        migrateLegacyIfNeeded(context)
        val result = mutableListOf<Conversation>()
        val files = dir(context).listFiles { file -> file.isFile && file.extension == "json" } ?: emptyArray()
        for (file in files) {
            try {
                parseConversation(file.readText(Charsets.UTF_8))?.let(result::add)
            } catch (_: Exception) {
                // A single damaged chat must never hide the rest of the history.
            }
        }
        return result
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })
            .toMutableList()
    }

    fun get(context: Context, id: String): Conversation? =
        loadAll(context).find { it.id == id }

    fun save(context: Context, conversation: Conversation) {
        if (conversation.messages.isEmpty()) return
        migrateLegacyIfNeeded(context)

        val previous = get(context, conversation.id)
        val merged = conversation.copy(
            title = if (previous?.customTitle == true) previous.title else conversation.title,
            pinned = previous?.pinned ?: conversation.pinned,
            customTitle = previous?.customTitle ?: conversation.customTitle,
            createdAt = previous?.createdAt ?: conversation.createdAt,
            updatedAt = System.currentTimeMillis()
        )
        writeAtomic(context, merged)
        trimOldConversations(context)
    }

    fun rename(context: Context, id: String, newTitle: String) {
        val target = get(context, id) ?: return
        target.title = newTitle.trim().ifBlank { target.title }
        target.customTitle = true
        writeAtomic(context, target)
    }

    fun setPinned(context: Context, id: String, pinned: Boolean) {
        val target = get(context, id) ?: return
        target.pinned = pinned
        writeAtomic(context, target)
    }

    fun delete(context: Context, id: String) {
        File(dir(context), safeName(id)).delete()
    }

    private fun migrateLegacyIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val targetDir = dir(context)
        if (prefs.getBoolean(MIGRATED_KEY, false)) return

        val raw = prefs.getString(KEY, null) ?: run {
            prefs.edit().putBoolean(MIGRATED_KEY, true).commit()
            return
        }
        val array = try { JSONArray(raw) } catch (_: Exception) { return }
        var imported = 0
        for (i in 0 until array.length()) {
            try {
                val conversation = parseConversation(array.getJSONObject(i).toString()) ?: continue
                val target = File(targetDir, safeName(conversation.id))
                if (!target.exists()) {
                    writeAtomic(context, conversation)
                    imported++
                }
            } catch (_: Exception) {
                // Preserve the rest of the legacy history even if one old record is bad.
            }
        }
        // Only mark migration complete after the import has actually been attempted.
        if (imported > 0 || array.length() == 0) {
            prefs.edit().putBoolean(MIGRATED_KEY, true).commit()
        }
    }

    private fun parseConversation(raw: String): Conversation? {
        val obj = JSONObject(raw)
        val id = obj.optString("id").ifBlank { return null }
        val title = obj.optString("title").ifBlank { "Conversation" }
        val messages = mutableListOf<ChatMessage>()
        val msgArray = obj.optJSONArray("messages") ?: JSONArray()
        for (j in 0 until msgArray.length()) {
            val m = msgArray.optJSONObject(j) ?: continue
            val text = m.optString("text", "")
            val attachments = mutableListOf<String>()
            m.optJSONArray("attachments")?.let { attArray ->
                for (k in 0 until attArray.length()) {
                    attachments.add(attArray.optString(k).orEmpty())
                }
            }
            messages.add(ChatMessage(text, m.optBoolean("isUser", false), attachments))
        }
        if (messages.isEmpty()) return null
        return Conversation(
            id = id,
            title = title,
            messages = messages,
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", obj.optLong("createdAt", System.currentTimeMillis())),
            pinned = obj.optBoolean("pinned", false),
            customTitle = obj.optBoolean("customTitle", false)
        )
    }

    private fun writeAtomic(context: Context, conversation: Conversation) {
        val target = File(dir(context), safeName(conversation.id))
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temp.writeText(toJson(conversation).toString(), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun toJson(c: Conversation): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("title", c.title)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
        put("pinned", c.pinned)
        put("customTitle", c.customTitle)
        put("messages", JSONArray().also { arr ->
            c.messages.forEach { m ->
                arr.put(JSONObject().apply {
                    put("text", m.text)
                    put("isUser", m.isUser)
                    put("attachments", JSONArray(m.attachments))
                })
            }
        })
    }

    private fun trimOldConversations(context: Context) {
        val all = loadAll(context)
        if (all.size <= MAX_SAVED) return
        all.drop(MAX_SAVED).forEach { File(dir(context), safeName(it.id)).delete() }
    }

    private fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"
}
