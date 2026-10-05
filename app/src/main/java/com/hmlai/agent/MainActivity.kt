package com.hmlai.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var homeContentView: View
    private lateinit var chatHeader: View
    private lateinit var chatTitle: TextView
    private lateinit var composerWrap: View
    private lateinit var bottomNav: View
    private lateinit var navHome: View
    private lateinit var navChat: View
    private lateinit var temporaryPill: View
    private lateinit var temporaryPillIcon: ImageView
    private lateinit var temporaryPillLabel: TextView
    private lateinit var taskCard: View
    private lateinit var taskLive: TextView
    private lateinit var taskStatus: TextView

    // True = the Home tab is selected. The home screen also shows by itself whenever the
    // conversation is empty, so a fresh chat always opens on Home.
    private var forceHome = true

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
    ) {
        // Results are handled implicitly — DeviceCommandHandler re-checks permission when a
        // command runs. Only AFTER this dialog closes do we offer the exact-alarm screen,
        // so the two never fight over the screen at the same time.
        requestExactAlarmPermissionIfNeeded()
    }

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
        UserProfileStore.seedDefaultsIfEmpty(this)

        drawerLayout = findViewById(R.id.drawerLayout)
        messageList = findViewById(R.id.messageList)
        adapter = ChatAdapter(messages)
        messageList.layoutManager = LinearLayoutManager(this)
        messageList.adapter = adapter

        input = findViewById(R.id.messageInput)
        val sendButton = findViewById<ImageButton>(R.id.sendButton)
        val newChatButton = findViewById<ImageButton>(R.id.newChatButton)
        val menuButton = findViewById<ImageButton>(R.id.menuButton)
        // These two are labeled "pill" buttons (LinearLayout in activity_main.xml), not
        // ImageButtons — casting them to ImageButton threw a ClassCastException the moment
        // MainActivity opened, which is what showed "close app" after Sign in Later.
        val attachButton = findViewById<View>(R.id.attachButton)
        val micButton = findViewById<View>(R.id.micButton)
        val drawerNewChat = findViewById<View>(R.id.drawerNewChat)
        val accountRow = findViewById<View>(R.id.accountRow)
        temporaryChatButton = findViewById(R.id.temporaryChatButton)
        temporaryBanner = findViewById(R.id.temporaryBanner)
        homeContentView = findViewById(R.id.homeContentView)
        chatHeader = findViewById(R.id.chatHeader)
        chatTitle = findViewById(R.id.chatTitle)
        composerWrap = findViewById(R.id.composerWrap)
        bottomNav = findViewById(R.id.bottomNav)
        navHome = findViewById(R.id.navHome)
        navChat = findViewById(R.id.navChat)
        temporaryPill = findViewById(R.id.temporaryPill)
        temporaryPillIcon = findViewById(R.id.temporaryPillIcon)
        temporaryPillLabel = findViewById(R.id.temporaryPillLabel)
        taskCard = findViewById(R.id.taskCard)
        taskLive = findViewById(R.id.taskLive)
        taskStatus = findViewById(R.id.taskStatus)

        attachmentsScroll = findViewById(R.id.attachmentsScroll)
        attachmentsPreview = findViewById(R.id.attachmentsPreview)

        setupDrawer(menuButton, drawerNewChat)
        setupAccountRow(accountRow)
        setupCommandChips()
        updateHomeVisibility()
        requestDevicePermissionsIfNeeded()

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
        temporaryPill.setOnClickListener { startTemporaryChat() }

        // Floating bottom navigation: Home / Chat / History
        navHome.setOnClickListener { forceHome = true; updateHomeVisibility() }
        navChat.setOnClickListener { forceHome = false; updateHomeVisibility() }
        findViewById<View>(R.id.navHistory).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Glass composer picks up the focus border from the concept.
        input.setOnFocusChangeListener { _, hasFocus -> composerWrap.isActivated = hasFocus }

        // IME-aware layout: the composer is bottom-anchored and adjustResize shrinks the
        // activity above the keyboard. Hide only the optional dock while the IME is visible.
        // This avoids the old global-layout heuristic, which could lag behind keyboard
        // animations on OEM keyboards and leave the composer underneath the IME.
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            bottomNav.visibility = if (imeVisible) View.GONE else View.VISIBLE
            insets
        }
        ViewCompat.requestApplyInsets(drawerLayout)

        applyHeadlineGradient()
        updateTemporaryUi()
    }

    /** "HML handles it." — white → sky → blue gradient text, as in the concept's h1 span. */
    private fun applyHeadlineGradient() {
        val accent = findViewById<TextView>(R.id.homeHeadlineAccent)
        accent.post {
            val width = accent.paint.measureText(accent.text.toString())
            accent.paint.shader = LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFF77C7FF.toInt(), 0xFF278DFF.toInt()),
                floatArrayOf(0.1f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            accent.invalidate()
        }
    }

    // ---- Autonomous task card (concept's .task): driven by the runner's existing callbacks ----

    private fun showTaskCard() {
        taskCard.visibility = View.VISIBLE
        taskLive.text = getString(R.string.task_live)
        taskStatus.text = ""
        setTaskSteps(now = 1, allDone = false)
    }

    private fun updateTaskCard(status: String) {
        taskStatus.text = status
        setTaskSteps(now = if (status.contains("Reading")) 1 else 3, allDone = false)
    }

    private fun finishTaskCard() {
        taskLive.text = getString(R.string.task_done)
        setTaskSteps(now = 4, allDone = true)
        mainHandler.postDelayed({ taskCard.visibility = View.GONE }, 4000)
    }

    private fun setTaskSteps(now: Int, allDone: Boolean) {
        val checks = intArrayOf(R.id.taskCheck1, R.id.taskCheck2, R.id.taskCheck3)
        val labels = intArrayOf(R.id.taskLabel1, R.id.taskLabel2, R.id.taskLabel3)
        for (i in 0..2) {
            val n = i + 1
            val check = findViewById<TextView>(checks[i])
            val label = findViewById<TextView>(labels[i])
            when {
                allDone || n < now -> {
                    check.text = "✓"; check.setTextColor(0xFF55E6A4.toInt()); label.setTextColor(0xFFAAB8C8.toInt())
                }
                n == now -> {
                    check.text = n.toString(); check.setTextColor(0xFF69BBFF.toInt()); label.setTextColor(0xFF73BCFF.toInt())
                }
                else -> {
                    check.text = n.toString(); check.setTextColor(0xFF718094.toInt()); label.setTextColor(0xFF718094.toInt())
                }
            }
        }
    }

    private fun requestDevicePermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS
        )
        // Android 13+ never shows reminder notifications unless this is granted at runtime.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            requestExactAlarmPermissionIfNeeded()
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val prefs = getSharedPreferences("hml_agent_prefs", Context.MODE_PRIVATE)
            // Only ask once. Previously this bounced the user out to Settings on EVERY launch
            // until they granted it; scheduling a reminder still explains how to grant it later.
            if (!alarmManager.canScheduleExactAlarms() && !prefs.getBoolean("asked_exact_alarm", false)) {
                prefs.edit().putBoolean("asked_exact_alarm", true).apply()
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
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
        // Consume the extras so a screen rotation / activity re-creation doesn't replay them
        // (which would re-run a scheduled task or yank the user back to that old chat).
        intent.removeExtra(EXTRA_OPEN_CONVERSATION_ID)

        // Opened from a scheduled-autonomous-task notification (see
        // ScheduledActionReceiver) — run the task now that we're in the
        // foreground with a live window Accessibility Service can act on.
        val pendingGoal = intent.getStringExtra(EXTRA_PENDING_AUTONOMOUS_GOAL)
        intent.removeExtra(EXTRA_PENDING_AUTONOMOUS_GOAL)
        pendingGoal?.let { goal ->
            adapter.addMessage(ChatMessage("⏰ Running scheduled task: $goal", isUser = false))
            forceHome = false
            updateHomeVisibility()
            scrollToBottom()
            adapter.addMessage(ChatMessage("…", isUser = false))
            scrollToBottom()
            startAutonomousTask(goal, messages.size - 1, currentConversationId)
        }
    }

    private fun refreshHistory() {
        historyAdapter.setConversations(ConversationStore.loadAll(this))
        historyAdapter.setActive(currentConversationId)
    }

    /** Long-press-free options menu (tap the ⋮ button) matching the rename / pin /
     * share / delete pattern most chat apps use for managing conversation history.
     * Uses a custom PopupWindow (not the legacy PopupMenu widget) since PopupMenu
     * unreliably ignores custom rounded-corner backgrounds on many devices/OEM
     * skins, showing a sharp-cornered system-gray popup instead of our styling. */
    private fun showConversationOptions(conversation: Conversation, anchor: View) {
        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.popup_conversation_options, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.elevation = 12f
        popupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val pinLabel = popupView.findViewById<TextView>(R.id.optionPin)
        pinLabel.text = getString(
            if (conversation.pinned) R.string.unpin_conversation else R.string.pin_conversation
        )

        popupView.findViewById<View>(R.id.optionRename).setOnClickListener {
            popupWindow.dismiss()
            showRenameDialog(conversation)
        }
        popupView.findViewById<View>(R.id.optionPin).setOnClickListener {
            popupWindow.dismiss()
            ConversationStore.setPinned(this, conversation.id, !conversation.pinned)
            refreshHistory()
        }
        popupView.findViewById<View>(R.id.optionShare).setOnClickListener {
            popupWindow.dismiss()
            shareConversation(conversation)
        }
        popupView.findViewById<View>(R.id.optionShortcut).setOnClickListener {
            popupWindow.dismiss()
            addHomeScreenShortcut(conversation)
        }
        popupView.findViewById<View>(R.id.optionDelete).setOnClickListener {
            popupWindow.dismiss()
            confirmDelete(conversation)
        }

        popupWindow.showAsDropDown(anchor, 0, 8)
    }

    private fun showRenameDialog(conversation: Conversation) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val editText = EditText(this).apply {
            setText(conversation.title)
            setSelection(text.length)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        MaterialAlertDialogBuilder(this)
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
        val dialog = MaterialAlertDialogBuilder(this)
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
        // Only this dialog's positive button gets the red destructive-action treatment —
        // rename/log-out keep the standard blue since they aren't destructive.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(this, R.color.color_error))
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

    /** Quick-command chips on the empty home screen: tapping one drops a ready-made
     * example command into the message box so the user can edit it and send. */
    private fun setupCommandChips() {
        val chips = mapOf(
            R.id.chipFlashlight to R.string.chip_flashlight_command,
            R.id.chipApps to R.string.chip_apps_command,
            R.id.chipMusic to R.string.chip_music_command,
            R.id.chipYoutube to R.string.chip_youtube_command,
            R.id.chipReminder to R.string.chip_reminder_command,
            R.id.chipPhone to R.string.chip_phone_command
        )
        val messageBox = findViewById<EditText>(R.id.messageInput)
        for ((viewId, stringId) in chips) {
            findViewById<View>(viewId).setOnClickListener {
                val command = getString(stringId)
                messageBox.setText(command)
                messageBox.setSelection(command.length)
                messageBox.requestFocus()
            }
        }
    }

    /** Shows the home screen (headline + chips) while the chat is empty, and the
     * message list once there is at least one message. */
    private fun updateHomeVisibility() {
        val showHome = messages.isEmpty() || forceHome
        homeContentView.visibility = if (showHome) View.VISIBLE else View.GONE
        messageList.visibility = if (showHome) View.GONE else View.VISIBLE
        // Chat header (title + TEMPORARY badge) belongs to the chat screen, but a temporary
        // chat also shows it on Home so the mode is never invisible.
        chatHeader.visibility = if (!showHome || isTemporaryChat) View.VISIBLE else View.GONE
        chatTitle.text = messages.firstOrNull { it.isUser }?.text?.take(40)
            ?: getString(R.string.new_conversation)
        navHome.isSelected = showHome
        navChat.isSelected = !showHome
    }

    private fun setupAccountRow(accountRow: View) {
        val name = SessionManager.getName(this)
        findViewById<TextView>(R.id.accountName).text = name
        findViewById<TextView>(R.id.accountSubtitle).text =
            if (name == "Guest") getString(R.string.account_subtitle) else SessionManager.getSubtitle(this)

        val initialView = findViewById<TextView>(R.id.accountInitial)
        val guestIconView = findViewById<ImageView>(R.id.accountGuestIcon)
        if (name == "Guest") {
            // A bare "G" read as an undesigned placeholder; an icon reads as intentional.
            initialView.visibility = View.GONE
            guestIconView.visibility = View.VISIBLE
        } else {
            initialView.visibility = View.VISIBLE
            guestIconView.visibility = View.GONE
            initialView.text = name.trim().take(1).uppercase(Locale.getDefault()).ifBlank { "A" }
        }

        accountRow.setOnClickListener {
            val statusText = if (HmlAccessibilityService.isRunning()) {
                getString(R.string.autonomous_control_on)
            } else {
                getString(R.string.autonomous_control_off)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.autonomous_control)
                .setMessage(statusText)
                .setPositiveButton(R.string.enable_autonomous_control) { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(R.string.log_out) { _, _ ->
                    MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.log_out_confirm)
                        .setPositiveButton(R.string.log_out) { _, _ ->
                            SessionManager.clear(this)
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        }

        accountRow.setOnLongClickListener {
            showEditProfileDialog()
            true
        }
    }

    /** Lets the user see/correct what HML remembers about their name, in
     * both English and Bangla — long-press the account row to open this. */
    private fun showEditProfileDialog() {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val spacing = (10 * density).toInt()

        val nameEnInput = EditText(this).apply {
            hint = getString(R.string.your_name_en_hint)
            setText(UserProfileStore.getNameEn(this@MainActivity))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
        }
        val nameBnInput = EditText(this).apply {
            hint = getString(R.string.your_name_bn_hint)
            setText(UserProfileStore.getNameBn(this@MainActivity))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            setPadding(0, spacing, 0, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(nameEnInput)
            addView(nameBnInput)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.your_name_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                UserProfileStore.setNameEn(this, nameEnInput.text.toString())
                UserProfileStore.setNameBn(this, nameBnInput.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startNewChat() {
        persistCurrentConversation()
        currentConversationId = UUID.randomUUID().toString()
        isTemporaryChat = false
        forceHome = true
        adapter.clear()
        updateHomeVisibility()
        clearAttachments()
        updateTemporaryUi()
        refreshHistory()
    }

    private fun startTemporaryChat() {
        persistCurrentConversation()
        currentConversationId = UUID.randomUUID().toString()
        isTemporaryChat = true
        forceHome = true
        adapter.clear()
        updateHomeVisibility()
        clearAttachments()
        updateTemporaryUi()
        historyAdapter.setActive(null)
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun updateTemporaryUi() {
        temporaryBanner.visibility = if (isTemporaryChat) View.VISIBLE else View.GONE
        val active = ContextCompat.getColor(this, R.color.blue_glow)
        temporaryChatButton.setColorFilter(
            if (isTemporaryChat) active else ContextCompat.getColor(this, R.color.text_icon),
            PorterDuff.Mode.SRC_IN
        )
        temporaryChatButton.isActivated = isTemporaryChat
        temporaryPill.isActivated = isTemporaryChat
        val toolColor = if (isTemporaryChat) active else ContextCompat.getColor(this, R.color.text_tool)
        temporaryPillIcon.setColorFilter(toolColor, PorterDuff.Mode.SRC_IN)
        temporaryPillLabel.setTextColor(toolColor)
        updateHomeVisibility()
    }

    private fun loadConversation(conversation: Conversation) {
        persistCurrentConversation()
        currentConversationId = conversation.id
        isTemporaryChat = false
        forceHome = false
        adapter.setMessages(conversation.messages)
        updateHomeVisibility()
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
     * (call a contact, flashlight on/off, etc) and the scheduled-action
     * parser (reminders, timed calls). Both fall back to asking the
     * server to understand the intent when the fast local patterns don't
     * match — this is what lets natural phrasing variations ("Abbu ke
     * call deo" as well as "call Abbu") both work as the same command,
     * instead of only recognizing one fixed wording. Only if a message is
     * genuinely NOT a command does it get sent to the AI server as chat. */
    private fun handleUserInput(text: String) {
        UserProfileStore.maybeLearnNameFrom(this, text)
        val attachmentNames = pendingAttachments.map { queryFileName(it) }
        adapter.addMessage(ChatMessage(text, isUser = true, attachments = attachmentNames))
        forceHome = false
        updateHomeVisibility()
        clearAttachments()
        scrollToBottom()
        persistCurrentConversation()

        // Show the "thinking" bubble immediately — classification (scheduling,
        // then device-command) each need a network round-trip, and previously
        // nothing appeared on screen until BOTH had finished, which is why
        // replies felt like they took 3-4 seconds to even start. Now the user
        // sees feedback right away, and whichever path actually handles the
        // message updates this same bubble instead of adding a new one.
        adapter.addMessage(ChatMessage("…", isUser = false))
        scrollToBottom()
        val thinkingIndex = messages.size - 1
        // Replies arrive asynchronously. If the user switches chats / starts a new one before
        // a reply lands, thinkingIndex would point at the WRONG message (or past the end of the
        // list and crash). Every async update below is therefore tied to this conversation id.
        val convId = currentConversationId

        // Compound commands ("open X and do Y", "control my screen...") are
        // checked FIRST, before the simple device-command patterns — a plain
        // Intent can only ever do ONE simple thing (open an app), so if the
        // message chains on a second action, DeviceCommandHandler's local
        // "open app" pattern would otherwise match first, silently open the
        // app, and drop everything after "and". Compound commands need the
        // full autonomous screen-control loop instead.
        if (isAutonomousControlRequest(text)) {
            // A compound command can ALSO be scheduled ("at exactly 12:00 AM,
            // open Messenger and say Happy Birthday to Wazi") — check for a
            // time first; if there's one, schedule the autonomous task for
            // later instead of running it immediately.
            ScheduledActionHandler.tryParseWithAiFallback(text) { scheduledCommand ->
                if (scheduledCommand != null) {
                    val confirmation = ScheduledActionHandler.scheduleAutonomousTask(
                        this, scheduledCommand.triggerAtMillis, text
                    )
                    replaceMessage(convId, thinkingIndex, confirmation)
                } else {
                    startAutonomousTask(text, thinkingIndex, convId)
                }
            }
            return
        }

        // Scheduled/delayed commands ("remind me to X at 5pm", "call mom
        // at 6:30") are checked next, since they use their own time-
        // parsing syntax that shouldn't fall through to plain commands.
        ScheduledActionHandler.tryParseWithAiFallback(text) { scheduledCommand ->
            if (scheduledCommand != null) {
                val confirmation = ScheduledActionHandler.schedule(this, scheduledCommand)
                replaceMessage(convId, thinkingIndex, confirmation)
                return@tryParseWithAiFallback
            }

            DeviceCommandHandler.tryHandleWithAiFallback(this, text) { commandResult ->
                if (commandResult != null && commandResult.handled) {
                    replaceMessage(convId, thinkingIndex, commandResult.responseText)
                    return@tryHandleWithAiFallback
                }

                sendToServer(text, attachmentNames, thinkingIndex, convId)
            }
        }
    }

    private fun isAutonomousControlRequest(text: String): Boolean {
        val t = text.lowercase()

        // Explicit autonomy phrases.
        val explicitTriggers = listOf(
            "control my screen", "control the screen", "take control",
            "do this for me on", "automate", "autonomously"
        )
        if (explicitTriggers.any { t.contains(it) }) return true

        // Any "open X and <anything else>" is a COMPOUND command — a plain
        // Intent can only ever do one simple thing (just open an app), so
        // if there's a second action chained on with "and"/"then", it
        // needs the full autonomous screen-control loop to actually carry
        // out that second part, regardless of which specific verb is used
        // ("say", "play", "tap", "send" — anything). Previously this only
        // matched a fixed short list of verbs, so most real compound
        // requests silently fell through as if they were simple app-opens.
        val compoundPattern = Regex("^(open|launch)\\s+.+?\\s+(and|then)\\s+.+")
        if (compoundPattern.containsMatchIn(t)) return true

        return false
    }

    private fun startAutonomousTask(goal: String, thinkingIndex: Int, convId: String) {
        if (!HmlAccessibilityService.isRunning()) {
            val message = if (HmlAccessibilityService.isEnabledInSettings(this)) {
                // Enabled but the service hasn't finished binding yet —
                // different, more accurate message than "not turned on".
                "⏳ Autonomous Control is enabled but still starting up. Give it a " +
                    "few seconds and try again."
            } else {
                "🔒 Autonomous Control isn't turned on yet. Go to Settings > " +
                    "Accessibility > HML Agent and enable it, then ask me again."
            }
            replaceMessage(convId, thinkingIndex, message)
            return
        }

        replaceMessage(convId, thinkingIndex, "🤖 Taking control to work on this...", persist = false)
        OverlayBubble.show(this, "Working on it...")
        showTaskCard()

        val runner = AutonomousTaskRunner(
            context = this,
            onStatusUpdate = { status ->
                // Status ticks aren't worth a disk write each — only the final result is saved.
                replaceMessage(convId, thinkingIndex, status, persist = false)
                OverlayBubble.show(this, status)
                updateTaskCard(status)
            },
            onFinished = { finalMessage ->
                replaceMessage(convId, thinkingIndex, finalMessage)
                OverlayBubble.hide(this)
                finishTaskCard()
            }
        )
        runner.start(goal)
    }

    private fun sendToServer(text: String, attachmentNames: List<String>, thinkingIndex: Int, convId: String) {
        // The user moved to another chat while the command check was running — don't send
        // this chat's history from the wrong conversation.
        if (convId != currentConversationId || thinkingIndex !in messages.indices) return

        // The full conversation so far (excluding the "…" placeholder at
        // thinkingIndex) so the backend can give the model real context
        // instead of treating every message as a fresh, memory-less
        // exchange. hml-agent-server needs to actually read this array
        // and pass it through as prior turns.
        val historyArray = JSONArray()
        for ((index, m) in messages.withIndex()) {
            if (index == thinkingIndex) continue
            historyArray.put(JSONObject().apply {
                put("role", if (m.isUser) "user" else "assistant")
                put("content", m.text)
            })
        }

        // Small persistent memory of who the user is. hml-agent-server needs to
        // fold this into the model's system prompt for it to actually change
        // what the AI says — sending it alone doesn't do that on its own.
        val profile = JSONObject().apply {
            put("name_en", UserProfileStore.getNameEn(this@MainActivity))
            put("name_bn", UserProfileStore.getNameBn(this@MainActivity))
            put("notes", UserProfileStore.getNotes(this@MainActivity))
        }

        val json = JSONObject().apply {
            put("message", text)
            // NOTE: the server at `serverUrl` only needs to read this if it wants to
            // acknowledge/process attachments — today it's sent as filenames only.
            // Wire up real file upload (e.g. multipart or base64 content) once the
            // backend has an endpoint that accepts it.
            put("attachments", JSONArray(attachmentNames))
            put("history", historyArray)
            put("profile", profile)
        }.toString()
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    replaceMessage(convId, thinkingIndex, "Couldn't reach HML Agent. Check your connection and try again.")
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()
                mainHandler.post {
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val answer = JSONObject(responseBody).optString("answer", "")
                            if (answer.isNotEmpty()) {
                                replaceMessage(convId, thinkingIndex, answer)
                            } else {
                                replaceMessage(convId, thinkingIndex, "HML Agent couldn't answer that right now. Try again shortly.")
                            }
                        } catch (e: Exception) {
                            replaceMessage(convId, thinkingIndex, "Something went wrong reading the response.")
                        }
                    } else {
                        replaceMessage(convId, thinkingIndex, "HML Agent is busy right now. Try again shortly.")
                    }
                }
            }
        })
    }

    /** Swaps the text of one agent bubble — but only if the user is still in the same
     * conversation and the bubble still exists. Without that check, a late reply after
     * "New chat" / opening another conversation hit IndexOutOfBounds (crash) or overwrote
     * an unrelated message. */
    private fun replaceMessage(conversationId: String, index: Int, newText: String, persist: Boolean = true) {
        if (isFinishing || isDestroyed) return
        if (conversationId != currentConversationId || index !in messages.indices) return
        messages[index] = ChatMessage(newText, isUser = false)
        adapter.notifyItemChanged(index)
        scrollToBottom()
        if (persist) persistCurrentConversation()
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

        /** Intent extra carrying a compound-command goal to run immediately when
         * the app is launched from a scheduled-autonomous-task notification. */
        const val EXTRA_PENDING_AUTONOMOUS_GOAL = "pending_autonomous_goal"
    }
}
