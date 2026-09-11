package com.hmlai.agent

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val serverUrl = "https://hml-agent-server.onrender.com/chat"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var messageList: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messageList = findViewById(R.id.messageList)
        adapter = ChatAdapter(messages)
        messageList.layoutManager = LinearLayoutManager(this)
        messageList.adapter = adapter

        val input = findViewById<android.widget.EditText>(R.id.messageInput)
        val sendButton = findViewById<android.widget.ImageButton>(R.id.sendButton)
        val newChatButton = findViewById<android.widget.TextView>(R.id.newChatButton)

        sendButton.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                input.setText("")
            }
        }

        newChatButton.setOnClickListener {
            adapter.clear()
        }
    }

    private fun sendMessage(text: String) {
        adapter.addMessage(ChatMessage(text, isUser = true))
        scrollToBottom()

        // Show a temporary "thinking" bubble while waiting for the server.
        adapter.addMessage(ChatMessage("…", isUser = false))
        scrollToBottom()
        val thinkingIndex = messages.size - 1

        val json = JSONObject().put("message", text).toString()
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    replaceMessage(thinkingIndex, "Couldn't reach HML Agent. Check your connection and try again.")
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()
                mainHandler.post {
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val answer = JSONObject(responseBody).optString("answer", "")
                            if (answer.isNotEmpty()) {
                                replaceMessage(thinkingIndex, answer)
                            } else {
                                replaceMessage(thinkingIndex, "HML Agent couldn't answer that right now. Try again shortly.")
                            }
                        } catch (e: Exception) {
                            replaceMessage(thinkingIndex, "Something went wrong reading the response.")
                        }
                    } else {
                        replaceMessage(thinkingIndex, "HML Agent is busy right now. Try again shortly.")
                    }
                }
            }
        })
    }

    private fun replaceMessage(index: Int, newText: String) {
        if (index in messages.indices) {
            messages[index] = ChatMessage(newText, isUser = false)
            adapter.notifyItemChanged(index)
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty()) {
            messageList.scrollToPosition(messages.size - 1)
        }
    }
}
