package com.hmlai.agent

data class Conversation(
    val id: String,
    var title: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    // Pinned conversations are sorted to the top of the drawer, above everything else.
    var pinned: Boolean = false,
    // Once the user renames a conversation by hand, auto-titling (which normally
    // just takes the first user message) must stop overwriting it on every save.
    var customTitle: Boolean = false
)
