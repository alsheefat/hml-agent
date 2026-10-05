package com.hmlai.agent

import org.json.JSONObject

/** Small local semantic layer used as extra context for the remote planner.
 * It separates the entity being acted on from execution modifiers so phrases
 * such as "Call Baba via Grameenphone" do not become one contact name. */
object CommandSemantics {
    fun parse(raw: String): JSONObject {
        val text = raw.trim().replace(Regex("\\s+(?:at|for)\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?(?:\\s+tomorrow)?\\s*$", RegexOption.IGNORE_CASE), "").trim()
        val lower = text.lowercase()
        val out = JSONObject()

        Regex("^\\s*call\\s+(.+?)(?:\\s+via\\s+(.+))?\\s*$", RegexOption.IGNORE_CASE).find(text)?.let {
            out.put("action", "call")
            out.put("contact", it.groupValues[1].trim())
            if (it.groupValues[2].isNotBlank()) out.put("carrier", it.groupValues[2].trim())
        }

        Regex("^\\s*(?:message|text|dm)\\s+(.+?)(?:\\s+(?:on|via|using)\\s+([A-Za-z][A-Za-z0-9 ._-]*))?\\s*$", RegexOption.IGNORE_CASE).find(text)?.let {
            out.put("action", "message")
            out.put("person", it.groupValues[1].trim())
            if (it.groupValues[2].isNotBlank()) out.put("platform", it.groupValues[2].trim())
        }

        Regex("^\\s*(?:play|watch)\\s+(.+?)(?:\\s+on\\s+([A-Za-z][A-Za-z0-9 ._-]*))?\\s*$", RegexOption.IGNORE_CASE).find(text)?.let {
            out.put("action", "play")
            out.put("content", it.groupValues[1].trim())
            if (it.groupValues[2].isNotBlank()) out.put("platform", it.groupValues[2].trim())
        }

        if (lower.contains("send") || lower.contains("message")) out.put("preserve_message_text", true)
        return out
    }
}
