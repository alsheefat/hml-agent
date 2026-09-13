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
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return result.sortedByDescending { it.createdAt }.toMutableList()
    }

    /** Upserts a conversation by id. Empty conversations are not persisted. */
    fun save(context: Context, conversation: Conversation) {
        if (conversation.messages.isEmpty()) return
        val all = loadAll(context).filterNot { it.id == conversation.id }.toMutableList()
        all.add(0, conversation)
        persist(context, all.take(MAX_SAVED))
    }

    private fun persist(context: Context, conversations: List<Conversation>) {
        val array = JSONArray()
        for (c in conversations) {
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("title", c.title)
            obj.put("createdAt", c.createdAt)
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
