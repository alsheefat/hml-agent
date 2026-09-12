package com.hmlai.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Very small local store for past conversations, backed by SharedPreferences.
 * Good enough for "recent chats" in the drawer without needing a database or
 * a server-side session concept — everything lives on-device.
 */
object ConversationStore {
    private const val PREFS = "hml_agent_conversations"
    private const val KEY = "conversations"
    private const val MAX_SAVED = 50

    fun loadAll(context: Context): MutableList<Conversation> {
        val raw = prefs(context).getString(KEY, null) ?: return mutableListOf()
        val result = mutableListOf<Conversation>()
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val messages = mutableListOf<ChatMessage>()
            val msgArray = obj.getJSONArray("messages")
            for (j in 0 until msgArray.length()) {
                val m = msgArray.getJSONObject(j)
                val attachments = mutableListOf<String>()
                m.optJSONArray("attachments")?.let { attArray ->
                    for (k in 0 until attArray.length()) attachments.add(attArray.getString(k))
                }
                messages.add(ChatMessage(m.getString("text"), m.getBoolean("isUser"), attachments))
            }
            result.add(
                Conversation(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    messages = messages,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    pinned = obj.optBoolean("pinned", false),
                    customTitle = obj.optBoolean("customTitle", false)
                )
            )
        }
        // Pinned conversations always float to the top; within each group, newest first.
        return result
            .sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.createdAt })
            .toMutableList()
    }

    fun get(context: Context, id: String): Conversation? =
        loadAll(context).find { it.id == id }

    /**
     * Upserts a conversation by id. Empty conversations are not persisted.
     * Preserves an existing `pinned`/`customTitle` state (and the custom title
     * itself) so that auto-saving new messages never silently un-pins a
     * conversation or clobbers a title the user set by hand.
     */
    fun save(context: Context, conversation: Conversation) {
        if (conversation.messages.isEmpty()) return
        val existing = loadAll(context)
        val previous = existing.find { it.id == conversation.id }
        val merged = conversation.copy(
            title = if (previous?.customTitle == true) previous.title else conversation.title,
            pinned = previous?.pinned ?: conversation.pinned,
            customTitle = previous?.customTitle ?: conversation.customTitle
        )
        val all = existing.filterNot { it.id == merged.id }.toMutableList()
        all.add(0, merged)
        persist(context, all.take(MAX_SAVED))
    }

    fun rename(context: Context, id: String, newTitle: String) {
        val all = loadAll(context)
        val target = all.find { it.id == id } ?: return
        target.title = newTitle
        target.customTitle = true
        persist(context, all)
    }

    fun setPinned(context: Context, id: String, pinned: Boolean) {
        val all = loadAll(context)
        val target = all.find { it.id == id } ?: return
        target.pinned = pinned
        persist(context, all)
    }

    fun delete(context: Context, id: String) {
        val all = loadAll(context).filterNot { it.id == id }
        persist(context, all)
    }

    private fun persist(context: Context, conversations: List<Conversation>) {
        val array = JSONArray()
        for (c in conversations) {
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("title", c.title)
            obj.put("createdAt", c.createdAt)
            obj.put("pinned", c.pinned)
            obj.put("customTitle", c.customTitle)
            val msgArray = JSONArray()
            for (m in c.messages) {
                val mObj = JSONObject()
                mObj.put("text", m.text)
                mObj.put("isUser", m.isUser)
                mObj.put("attachments", JSONArray(m.attachments))
                msgArray.put(mObj)
            }
            obj.put("messages", msgArray)
            array.put(obj)
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
