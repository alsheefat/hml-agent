package com.hmlai.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID
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

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var historyAdapter: HistoryAdapter
    private var currentConversationId: String = UUID.randomUUID().toString()
    private var isTemporaryChat = false
    private lateinit var temporaryChatButton: ImageButton
    private lateinit var temporaryBanner: TextView

    // Files picked via the "+" button, waiting to be sent with the next message.
    private val pendingAttachments = mutableListOf<Uri>()
    private lateinit var attachmentsScroll: HorizontalScrollView
    private lateinit var attachmentsPreview: LinearLayout

    private lateinit var input: EditText

    // Stage 3: flashlight + "call <contact>" need these to actually run.
    // Re-requested here (not just declared in the manifest) since they're
    // dangerous/runtime permissions on API 26+.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results handled implicitly — DeviceCommandHandler re-checks permission when a command runs */ }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> uris.forEach { addAttachment(it) } }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            val current = input.text?.toString().orEmpty()
            input.setText(if (current.isBlank()) spoken else "$current $spoken")
            input.setSelection(input.text?.length ?: 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)
        messageList = findViewById(R.id.messageList)
        adapter = ChatAdapter(messages)
        messageList.layoutManager = LinearLayoutManager(this)
        messageList.adapter = adapter

        input = findViewById(R.id.messageInput)
        val sendButton = findViewById<ImageButton>(R.id.sendButton)
        val newChatButton = findViewById<ImageButton>(R.id.newChatButton)
        val menuButton = findViewById<ImageButton>(R.id.menuButton)
        val attachButton = findViewById<ImageButton>(R.id.attachButton)
        val micButton = findViewById<ImageButton>(R.id.micButton)
        val drawerNewChat = findViewById<View>(R.id.drawerNewChat)
        val accountRow = findViewById<View>(R.id.accountRow)
        temporaryChatButton = findViewById(R.id.temporaryChatButton)
        temporaryBanner = findViewById(R.id.temporaryBanner)

        attachmentsScroll = findViewById(R.id.attachmentsScroll)
        attachmentsPreview = findViewById(R.id.attachmentsPreview)

        setupDrawer(menuButton, drawerNewChat)
        setupAccountRow(accountRow)
        requestDevicePermissionsIfNeeded()
        requestExactAlarmPermissionIfNeeded()

        sendButton.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                handleUserInput(text)
                input.setText("")
            }
        }

        newChatButton.setOnClickListener { startNewChat() }
        temporaryChatButton.setOnClickListener { startTemporaryChat() }

        attachButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        micButton.setOnClickListener { startVoiceInput() }
    }

    private fun requestDevicePermissionsIfNeeded() {
        val permissions = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS
        )
        val needed = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    startActivity(intent)
                } catch (e: Exception) {
                    // Some devices/OEMs don't support this settings screen — reminders will
                    // just fail gracefully with a clear message when actually scheduled.
                }
            }
        }
    }

    // ---------------------------------------------------------------- drawer / history

    private fun setupDrawer(menuButton: ImageButton, drawerNewChat: View) {
        menuButton.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        drawerNewChat.setOnClickListener {
            startNewChat()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        val historyList = findViewById<RecyclerView>(R.id.historyList)
        historyAdapter = HistoryAdapter(
            ConversationStore.loadAll(this),
            onClick = { conversation ->
                loadConversation(conversation)
                drawerLayout.closeDrawer(GravityCompat.START)
            },
            onOptionsClick = { conversation, anchor ->
                showConversationOptions(conversation, anchor)
            }
        )
        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = historyAdapter

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                // Refresh in case a conversation was just saved.
                refreshHistory()
            }
        })

        // Opened from a pinned Home-screen shortcut for a specific conversation
        // (see addHomeScreenShortcut / LoginActivity's forwarding of this extra).
        intent.getStringExtra(EXTRA_OPEN_CONVERSATION_ID)?.let { id ->
            ConversationStore.get(this, id)?.let { loadConversation(it) }
        }
    }

    private fun refreshHistory() {
        historyAdapter.setConversations(ConversationStore.loadAll(this))
        historyAdapter.setActive(currentConversationId)
    }

    /** Long-press-free options menu (tap the ⋮ button) matching the rename / pin /
     * share / delete pattern most chat apps use for managing conversation history. */
    private fun showConversationOptions(conversation: Conversation, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_history_item, popup.menu)
        popup.menu.findItem(R.id.action_pin).setTitle(
            if (conversation.pinned) R.string.unpin_conversation else R.string.pin_conversation
        )
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename -> {
                    showRenameDialog(conversation)
                    true
                }
                R.id.action_pin -> {
                    ConversationStore.setPinned(this, conversation.id, !conversation.pinned)
                    refreshHistory()
                    true
                }
                R.id.action_share -> {
                    shareConversation(conversation)
                    true
                }
                R.id.action_shortcut -> {
                    addHomeScreenShortcut(conversation)
                    true
                }
                R.id.action_delete -> {
                    confirmDelete(conversation)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(conversation: Conversation) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val editText = EditText(this).apply {
            setText(conversation.title)
            setSelection(text.length)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_conversation)
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val newTitle = editText.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    ConversationStore.rename(this, conversation.id, newTitle)
                    refreshHistory()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(conversation: Conversation) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_conversation_confirm, conversation.title))
            .setPositiveButton(R.string.delete_conversation) { _, _ ->
                ConversationStore.delete(this, conversation.id)
                if (conversation.id == currentConversationId) {
                    startNewChat()
                }
                refreshHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareConversation(conversation: Conversation) {
        val transcript = buildString {
            appendLine(conversation.title)
            appendLine()
            for (m in conversation.messages) {
                append(if (m.isUser) "You: " else "HML Agent: ")
                appendLine(m.text)
            }
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, conversation.title)
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_conversation)))
    }

    private fun addHomeScreenShortcut(conversation: Conversation) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, R.string.shortcuts_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        // Routed through LoginActivity (not MainActivity directly) since it's the
        // exported/launcher activity — it forwards straight back here once a
        // session already exists, which is true for every logged-in or guest user.
        val shortcutIntent = Intent(this, LoginActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(EXTRA_OPEN_CONVERSATION_ID, conversation.id)
        }
        val shortcut = ShortcutInfoCompat.Builder(this, "conversation_${conversation.id}")
            .setShortLabel(conversation.title.take(10))
            .setLongLabel(conversation.title)
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(shortcutIntent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
    }

    private fun setupAccountRow(accountRow: View) {
        findViewById<TextView>(R.id.accountName).text = SessionManager.getName(this)
        findViewById<TextView>(R.id.accountSubtitle).text = SessionManager.getSubtitle(this)
        findViewById<TextView>(R.id.accountInitial).text =
            SessionManager.getName(this).trim().take(1).uppercase(Locale.getDefault()).ifBlank { "A" }

        accountRow.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.log_out_confirm)
                .setPositiveButton(R.string.log_out) { _, _ ->
                    SessionManager.clear(this)
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun startNewChat() {
        persistCurrentConversation()
        currentConversationId = UUID.randomUUID().toString()
        isTemporaryChat = false
        adapter.clear()
        clearAttachments()
        updateTemporaryUi()
        refreshHistory()
    }

    private fun startTemporaryChat() {
        persistCurrentConversation()
        currentConversationId = UUID.randomUUID().toString()
        isTemporaryChat = true
        adapter.clear()
        clearAttachments()
        updateTemporaryUi()
        historyAdapter.setActive(null)
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun updateTemporaryUi() {
        temporaryBanner.visibility = if (isTemporaryChat) View.VISIBLE else View.GONE
        val tint = if (isTemporaryChat) R.color.blue_glow else R.color.text_secondary
        temporaryChatButton.setColorFilter(ContextCompat.getColor(this, tint), PorterDuff.Mode.SRC_IN)
        temporaryChatButton.isActivated = isTemporaryChat
    }

    private fun loadConversation(conversation: Conversation) {
        persistCurrentConversation()
        currentConversationId = conversation.id
        isTemporaryChat = false
        adapter.setMessages(conversation.messages)
        clearAttachments()
        updateTemporaryUi()
        scrollToBottom()
        historyAdapter.setActive(currentConversationId)
    }

    private fun persistCurrentConversation() {
        if (isTemporaryChat || messages.isEmpty()) return
        val title = messages.firstOrNull { it.isUser }?.text?.take(48) ?: "New conversation"
        ConversationStore.save(this, Conversation(currentConversationId, title, messages.toMutableList()))
    }

    // ---------------------------------------------------------------- attachments

    private fun addAttachment(uri: Uri) {
        pendingAttachments.add(uri)
        val chip = LayoutInflater.from(this)
            .inflate(R.layout.item_attachment_chip, attachmentsPreview, false)
        chip.findViewById<TextView>(R.id.chipFileName).text = queryFileName(uri)
        chip.findViewById<ImageButton>(R.id.chipRemove).setOnClickListener {
            val index = attachmentsPreview.indexOfChild(chip)
            if (index in pendingAttachments.indices) pendingAttachments.removeAt(index)
            attachmentsPreview.removeView(chip)
            attachmentsScroll.visibility = if (pendingAttachments.isEmpty()) View.GONE else View.VISIBLE
        }
        attachmentsPreview.addView(chip)
        attachmentsScroll.visibility = View.VISIBLE
    }

    private fun clearAttachments() {
        pendingAttachments.clear()
        attachmentsPreview.removeAllViews()
        attachmentsScroll.visibility = View.GONE
    }

    private fun queryFileName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)?.let { name = it }
            }
        }
        return name
    }

    // ---------------------------------------------------------------- voice input

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_input))
        }
        if (intent.resolveActivity(packageManager) != null) {
            speechLauncher.launch(intent)
        } else {
            Toast.makeText(this, "No speech recognition app found on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- sending

    /** Every message first goes through the local device-command handler
     * (call a contact, flashlight on/off). Only if it's NOT a device
     * command does it get sent to the AI server as a normal chat message. */
    private fun handleUserInput(text: String) {
        val attachmentNames = pendingAttachments.map { queryFileName(it) }
        adapter.addMessage(ChatMessage(text, isUser = true, attachments = attachmentNames))
        clearAttachments()
        scrollToBottom()
        persistCurrentConversation()

        // Scheduled/delayed commands ("remind me to X at 5pm", "call mom
        // at 6:30") are checked first, since they use their own time-
        // parsing syntax that shouldn't fall through to plain commands.
        val scheduledCommand = ScheduledActionHandler.tryParse(text)
        if (scheduledCommand != null) {
            val confirmation = ScheduledActionHandler.schedule(this, scheduledCommand)
            adapter.addMessage(ChatMessage(confirmation, isUser = false))
            scrollToBottom()
            persistCurrentConversation()
            return
        }

        val commandResult = DeviceCommandHandler.tryHandle(this, text)
        if (commandResult.handled) {
            adapter.addMessage(ChatMessage(commandResult.responseText, isUser = false))
            scrollToBottom()
            persistCurrentConversation()
            return
        }

        sendToServer(text, attachmentNames)
    }

    private fun sendToServer(text: String, attachmentNames: List<String>) {
        // Show a temporary "thinking" bubble while waiting for the server.
        adapter.addMessage(ChatMessage("…", isUser = false))
        scrollToBottom()
        val thinkingIndex = messages.size - 1

        val json = JSONObject().apply {
            put("message", text)
            // NOTE: the server at `serverUrl` only needs to read this if it wants to
            // acknowledge/process attachments — today it's sent as filenames only.
            // Wire up real file upload (e.g. multipart or base64 content) once the
            // backend has an endpoint that accepts it.
            put("attachments", JSONArray(attachmentNames))
        }.toString()
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
            persistCurrentConversation()
        }
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty()) {
            messageList.scrollToPosition(messages.size - 1)
        }
    }

    override fun onPause() {
        super.onPause()
        persistCurrentConversation()
    }

    companion object {
        /** Intent extra used to jump straight to a specific conversation when the
         * app is launched from a pinned Home-screen shortcut. */
        const val EXTRA_OPEN_CONVERSATION_ID = "open_conversation_id"
    }
}
