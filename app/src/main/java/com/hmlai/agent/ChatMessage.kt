package com.hmlai.agent

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val attachments: List<String> = emptyList()
)
