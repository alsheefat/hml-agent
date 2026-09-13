package com.hmlai.agent

data class Conversation(
    val id: String,
    var title: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
)
