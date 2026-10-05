package com.hmlai.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.core.view.WindowCompat
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
import java.util.regex.Pattern
import java.util.Calendar
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
    private lateinit var composerBlur: BlurBehindView
    private lateinit var taskCard: View
    private lateinit var taskLive: TextView
    private lateinit var taskStatus: TextView
    private lateinit var taskStopButton: View
    private var activeTaskRunner: AutonomousTaskRunner? = null
    private var activeMissionGoal: String? = null

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

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceDialog: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        UserProfileStore.seedDefaultsIfEmpty(this)

        drawerLayout = findViewById(R.id.drawerLayout)
        messageList = findViewById(R.id.messageList)
        adapter = ChatAdapter(messages)
        messageList.layoutManager = LinearLayoutManager(this)
        messageList.adapter = adapter
        messageList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (::composerBlur.isInitialized) composerBlur.invalidate()
            }
        })

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
        composerBlur = findViewById(R.id.composerBlur)
        homeContentView.setOnScrollChangeListener { _, _, _, _, _ -> composerBlur.invalidate() }
        taskCard = findViewById(R.id.taskCard)
        taskLive = findViewById(R.id.taskLive)
        taskStatus = findViewById(R.id.taskStatus)
        taskStopButton = findViewById(R.id.taskStopButton)
        taskStopButton.setOnClickListener {
            activeTaskRunner?.cancel()
            activeTaskRunner = null
            taskLive.text = getString(R.string.task_stopped)
            taskStatus.text = getString(R.string.task_stopped_desc)
            setTaskSteps(now = 4, allDone = false)
            mainHandler.postDelayed({ if (!isDestroyed) taskCard.visibility = View.GONE }, 1200)
        }

        attachmentsScroll = findViewById(R.id.attachmentsScroll)
        attachmentsPreview = findViewById(R.id.attachmentsPreview)

        setupDrawer(menuButton, drawerNewChat)
        setupAccountRow(accountRow)
        setupCommandChips()
        updateHomeVisibility()
        composerBlur.setSource(homeContentView)
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
        // Glass composer picks up the focus border from the concept.
        input.setOnFocusChangeListener { _, hasFocus -> composerWrap.isActivated = hasFocus }

        // Stage 19: take control of system/IME insets explicitly. Android 15 can keep the
        // window edge-to-edge even when adjustResize is requested, so the old approach could
        // leave the composer underneath the keyboard on some keyboards/OEM builds.
        val topBar = findViewById<View>(R.id.topBar)
        val composerContainer = findViewById<View>(R.id.composerContainer)
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            topBar.setPadding(topBar.paddingLeft, bars.top + 6, topBar.paddingRight, topBar.paddingBottom)
            composerContainer.setPadding(
                composerContainer.paddingLeft,
                composerContainer.paddingTop,
                composerContainer.paddingRight,
                6 + bottom
            )
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
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO
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
        val pendingId = intent.getStringExtra(EXTRA_PENDING_AUTONOMOUS_ID)
        intent.removeExtra(EXTRA_PENDING_AUTONOMOUS_GOAL)
        intent.removeExtra(EXTRA_PENDING_AUTONOMOUS_ID)
        pendingGoal?.let { goal ->
            adapter.addMessage(ChatMessage("⏰ Running scheduled task: $goal", isUser = false))
            forceHome = false
            updateHomeVisibility()
            scrollToBottom()
            adapter.addMessage(ChatMessage("…", isUser = false))
            scrollToBottom()
            startAutonomousTask(goal, messages.size - 1, currentConversationId, pendingId)
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

        val pinLabel = popupView.findViewById<TextView>(R.id.optionPinText)
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
        val dialog = android.app.Dialog(this)
        val root = premiumDialogRoot(320)

        val eyebrow = dialogText("CONVERSATION NAME", 9f, R.color.text_muted, true)
        root.addView(eyebrow, lp(-1, 20))
        val title = dialogText(getString(R.string.rename_conversation), 20f, R.color.text_primary, true)
        root.addView(title, lp(-1, 32))
        val subtitle = dialogText("Give this chat a name you will recognize later.", 11f, R.color.text_secondary, false)
        subtitle.setPadding(0, 0, 0, dp(12))
        root.addView(subtitle, lp(-1, -2))

        val editText = EditText(this).apply {
            setText(conversation.title)
            setSelection(text.length)
            setSingleLine(true)
            hint = getString(R.string.rename_conversation)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.rename_field_bg)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            textSize = 14f
            setPadding(dp(14), 0, dp(14), 0)
        }
        root.addView(editText, lp(-1, 48))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        val cancel = dialogButton("Cancel", false) { dialog.dismiss() }
        val save = dialogButton(getString(R.string.save), true) {
            val newTitle = editText.text.toString().trim()
            if (newTitle.isNotEmpty()) {
                ConversationStore.rename(this, conversation.id, newTitle)
                refreshHistory()
            }
            dialog.dismiss()
        }
        buttons.addView(cancel, lpWrap(88, 42))
        buttons.addView(save, lpWrap(82, 42, 8))
        root.addView(buttons, lp(-1, 58))

        dialog.setContentView(root)
        styleDialogWindow(dialog, 320)
        dialog.show()
        editText.requestFocus()
    }


    private fun showGuestPanelDialog(statusText: String) {
        val dialog = android.app.Dialog(this)
        val root = premiumDialogRoot(320)

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_person)
            imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.blue_glow))
            background = circleBg("#102338", "#24577D")
            setPadding(dp(15), dp(15), dp(15), dp(15))
        }
        val iconRow = LinearLayout(this).apply { gravity = Gravity.CENTER; addView(icon, lp(54, 54)) }
        root.addView(iconRow, lp(-1, 66))
        root.addView(dialogText("GUEST MODE", 9f, R.color.blue_glow, true).apply { gravity = Gravity.CENTER }, lp(-1, 20))
        root.addView(dialogText("Guest", 21f, R.color.text_primary, true).apply { gravity = Gravity.CENTER }, lp(-1, 34))
        root.addView(dialogText(statusText, 11f, R.color.text_secondary, false).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(14)) }, lp(-1, -2))

        val enable = dialogButton(getString(R.string.enable_autonomous_control), true) {
            dialog.dismiss()
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        root.addView(enable, lp(-1, 46))
        val logout = dialogButton(getString(R.string.log_out), false) {
            dialog.dismiss()
            val confirm = android.app.Dialog(this)
            val croot = premiumDialogRoot(300)
            croot.addView(dialogText("SIGN OUT", 9f, R.color.color_error, true), lp(-1, 20))
            croot.addView(dialogText("Leave guest session?", 19f, R.color.text_primary, true), lp(-1, 32))
            croot.addView(dialogText(getString(R.string.log_out_confirm), 11f, R.color.text_secondary, false), lp(-1, -2))
            val row = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(14), 0, 0) }
            row.addView(dialogButton("Cancel", false) { confirm.dismiss() }, lpWrap(88, 42))
            row.addView(dialogButton(getString(R.string.log_out), true) {
                SessionManager.clear(this)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }, lpWrap(82, 42, 8))
            croot.addView(row, lp(-1, 58))
            confirm.setContentView(croot)
            styleDialogWindow(confirm, 300)
            confirm.show()
        }
        root.addView(logout, lp(-1, 44).apply { topMargin = dp(7) })
        root.addView(dialogButton("Close", false) { dialog.dismiss() }, lp(-1, 42).apply { topMargin = dp(2) })

        dialog.setContentView(root)
        styleDialogWindow(dialog, 320)
        dialog.show()
    }

    private fun startPremiumVoiceDialog(): Pair<android.app.Dialog, TextView> {
        val dialog = android.app.Dialog(this)
        val root = premiumDialogRoot(320)
        val stage = FrameLayout(this)
        val halo = View(this).apply { background = circleBg("#10263B", "#22547A") }
        stage.addView(halo, frameLp(118, 118, Gravity.CENTER))
        val core = ImageView(this).apply {
            setImageResource(R.drawable.ic_voice_wave)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = circleBg("#167BDB", "#2C9BFF")
            setPadding(dp(30), dp(30), dp(30), dp(30))
        }
        stage.addView(core, frameLp(72, 72, Gravity.CENTER))
        root.addView(stage, lp(-1, 128))
        root.addView(dialogText("HML VOICE", 9f, R.color.blue_glow, true).apply { gravity = Gravity.CENTER }, lp(-1, 20))
        root.addView(dialogText(getString(R.string.voice_input), 21f, R.color.text_primary, true).apply { gravity = Gravity.CENTER }, lp(-1, 34))
        val message = dialogText(getString(R.string.voice_listening), 12f, R.color.text_secondary, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(message, lp(-1, -2))
        root.addView(dialogButton(getString(R.string.voice_done), true) { stopVoiceInput(); dialog.dismiss() }, lp(-1, 46))
        root.addView(dialogButton(getString(R.string.voice_cancel), false) { stopVoiceInput(); dialog.dismiss() }, lp(-1, 42).apply { topMargin = dp(7) })
        dialog.setContentView(root)
        styleDialogWindow(dialog, 320)
        dialog.show()
        core.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.86f).setDuration(900).withEndAction {
            core.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(900).start()
        }.start()
        return dialog to message
    }

    private fun premiumDialogRoot(widthDp: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(18))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(Color.parseColor("#0D141C"))
            setStroke(dp(1), Color.parseColor("#22384D"))
        }
        elevation = dp(18).toFloat()
    }

    private fun dialogText(textValue: String, size: Float, colorRes: Int, bold: Boolean): TextView = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
        if (bold) typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        includeFontPadding = false
    }

    private fun dialogButton(label: String, primary: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(if (primary) Color.WHITE else ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(13).toFloat()
            setColor(Color.parseColor(if (primary) "#176FC1" else "#111B25"))
            setStroke(dp(1), Color.parseColor(if (primary) "#2A83D8" else "#243444"))
        }
        setOnClickListener { action() }
    }

    private fun circleBg(fill: String, stroke: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(fill))
        setStroke(dp(1), Color.parseColor(stroke))
    }

    private fun styleDialogWindow(dialog: android.app.Dialog, widthDp: Int) {
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes = dialog.window?.attributes?.apply { dimAmount = 0.62f }
        dialog.setOnShowListener {
            dialog.window?.setLayout(dp(widthDp), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun lp(width: Int, height: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(if (width < 0) LinearLayout.LayoutParams.MATCH_PARENT else dp(width), if (height < 0) LinearLayout.LayoutParams.WRAP_CONTENT else dp(height))
    private fun lpWrap(width: Int, height: Int, marginStart: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(dp(width), dp(height)).apply { leftMargin = dp(marginStart) }
    private fun frameLp(width: Int, height: Int, gravity: Int): FrameLayout.LayoutParams = FrameLayout.LayoutParams(dp(width), dp(height)).apply { this.gravity = gravity }

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
        chatTitle.text = if (isTemporaryChat) getString(R.string.temporary_chat_title)
            else messages.firstOrNull { it.isUser }?.text?.take(40)
                ?: getString(R.string.new_conversation)
        if (::composerBlur.isInitialized) {
            composerBlur.setSource(if (showHome) homeContentView else messageList)
        }
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
            showGuestPanelDialog(statusText)
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
        activeTaskRunner?.cancel()
        activeTaskRunner = null
        activeMissionGoal = null
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
        temporaryChatButton.isActivated = isTemporaryChat
        temporaryChatButton.alpha = if (isTemporaryChat) 1f else 0.68f
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

    /** HML-owned voice UI. Android's SpeechRecognizer supplies recognition, but
     * the user never gets thrown into Google's separate voice screen. */
    private fun startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "HML needs microphone permission for voice commands.", Toast.LENGTH_SHORT).show()
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, getString(R.string.no_speech_app), Toast.LENGTH_SHORT).show()
            return
        }

        val (dialog, message) = startPremiumVoiceDialog()
        voiceDialog = dialog
        dialog.setOnDismissListener { speechRecognizer?.cancel(); speechRecognizer?.destroy(); speechRecognizer = null; voiceDialog = null }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { message.text = getString(R.string.voice_listening) }
                override fun onBeginningOfSpeech() { message.text = getString(R.string.voice_hearing) }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { message.text = getString(R.string.voice_processing) }
                override fun onError(error: Int) { message.text = getString(R.string.voice_error) }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        dialog.dismiss()
                        input.setText(spoken)
                        input.setSelection(input.text?.length ?: 0)
                        handleUserInput(spoken.trim())
                        input.setText("")
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) message.text = partial
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer.startListening(intent)
        }
    }

    private fun stopVoiceInput() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
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
        val attachmentUris = pendingAttachments.toList()
        val attachmentNames = attachmentUris.map { queryFileName(it) }
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

        // Mission control is deliberately local and instant. HML should never need
        // a network round-trip to understand “stop”, and a follow-up must stay tied
        // to the previous mission instead of becoming a disconnected chat turn.
        val normalized = text.trim().lowercase()
        if (activeTaskRunner != null && normalized.matches(Regex("^(stop|cancel|abort|never mind|nevermind)$"))) {
            activeTaskRunner?.cancel()
            activeTaskRunner = null
            activeMissionGoal = null
            replaceMessage(convId, thinkingIndex, "Stopped. I left the current screen unchanged.")
            return
        }

        if (activeTaskRunner != null && normalized.matches(Regex("^(change|instead|actually)\\s+.+"))) {
            activeTaskRunner?.cancel()
            activeTaskRunner = null
            activeMissionGoal = null
            startAutonomousTask("Continue with this corrected goal: $text", thinkingIndex, convId)
            return
        }

        if (isContextualMissionFollowUp(normalized)) {
            val previous = activeMissionGoal ?: SkillStore.getLastGoal(this)
            if (!previous.isNullOrBlank()) {
                startAutonomousTask("Previous goal: $previous\nUser follow-up/correction: $text", thinkingIndex, convId)
                return
            }
        }

        if (handleMemoryCommand(text, convId, thinkingIndex)) return

        if (handleSkillCommand(text, convId, thinkingIndex)) return

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

                sendToServer(text, attachmentUris, attachmentNames, thinkingIndex, convId)
            }
        }
    }

    private fun isAutonomousControlRequest(text: String): Boolean {
        val t = text.trim().lowercase()

        val explicitTriggers = listOf(
            "control my screen", "control the screen", "take control",
            "do this for me on", "automate", "autonomously"
        )
        if (explicitTriggers.any { t.contains(it) }) return true

        // Real-world goals that require opening an app and then continuing inside it.
        val actionTriggers = listOf(
            "play ", "watch ", "send ", "message ", "text ", "tell ", "reply ",
            "dm ", "post ", "search youtube", "find on youtube", "scroll to ",
            "tap ", "share ", "follow ", "upload ", "download "
        )
        if (actionTriggers.any { t.contains(it) }) return true

        val appTargets = listOf("instagram", "whatsapp", "telegram", "facebook", "youtube", "messenger")
        val appActionWords = listOf("send", "message", "open", "play", "watch", "search", "find", "post", "reply", "tell")
        if (appTargets.any { t.contains(it) } && appActionWords.any { t.contains(it) }) return true

        val compoundPattern = Regex("^(open|launch)\\s+.+?\\s+(and|then)\\s+.+")
        return compoundPattern.containsMatchIn(t)
    }

    private fun handleSkillCommand(text: String, convId: String, thinkingIndex: Int): Boolean {
        val normalized = text.trim()
        if (Regex("(?i)^(do it again|repeat (the )?(last|previous) task|repeat that)$").matches(normalized)) {
            val goal = SkillStore.getLastGoal(this)
            if (goal.isNullOrBlank()) {
                replaceMessage(convId, thinkingIndex, "There is no completed autonomous task to repeat yet.")
            } else {
                replaceMessage(convId, thinkingIndex, "Repeating the last task: $goal", persist = false)
                startAutonomousTask(goal, thinkingIndex, convId)
            }
            return true
        }

        val save = Regex("(?i)^save (?:this )?task as (.+)$").find(normalized)
        if (save != null) {
            val goal = SkillStore.getLastGoal(this)
            val name = save.groupValues[1].trim()
            if (goal.isNullOrBlank()) {
                replaceMessage(convId, thinkingIndex, "Finish an autonomous task first, then I can save it as a Skill.")
            } else {
                SkillStore.save(this, name, goal)
                replaceMessage(convId, thinkingIndex, "Saved as Skill: $name")
            }
            return true
        }

        val run = Regex("(?i)^(run|use) skill (.+)$").find(normalized)
        if (run != null) {
            val name = run.groupValues[2].trim()
            val goal = SkillStore.get(this, name)
            if (goal.isNullOrBlank()) {
                replaceMessage(convId, thinkingIndex, "I couldn't find a Skill named $name.")
            } else {
                replaceMessage(convId, thinkingIndex, "Running Skill: $name", persist = false)
                startAutonomousTask(goal, thinkingIndex, convId)
            }
            return true
        }
        return false
    }

    private fun startAutonomousTask(goal: String, thinkingIndex: Int, convId: String, scheduledTaskId: String? = null) {
        SkillStore.setLastGoal(this, goal)
        activeMissionGoal = goal
        if (!HmlAccessibilityService.isRunning()) {
            val message = if (HmlAccessibilityService.isEnabledInSettings(this)) {
                "⏳ Autonomous Control is enabled but still starting up. Give it a few seconds and try again."
            } else {
                "🔒 Autonomous Control isn't turned on yet. Go to Settings > Accessibility > HML Agent and enable it, then ask me again."
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
                replaceMessage(convId, thinkingIndex, status, persist = false)
                OverlayBubble.show(this, status)
                updateTaskCard(status)
            },
            onConfirmationRequired = { action, target, resume ->
                runOnUiThread {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.agent_confirmation_title)
                        .setMessage(getString(R.string.agent_confirmation_message, action, target))
                        .setNegativeButton(R.string.agent_confirmation_stop) { _, _ ->
                            activeTaskRunner?.cancel()
                        }
                        .setPositiveButton(R.string.agent_confirmation_allow) { _, _ ->
                            resume()
                        }
                        .setCancelable(false)
                        .show()
                }
            },
            onFinished = { finalMessage ->
                SkillStore.setLastGoal(this, goal)
                replaceMessage(convId, thinkingIndex, finalMessage)
                if (!scheduledTaskId.isNullOrBlank()) {
                    if (finalMessage.contains("couldn't", true) || finalMessage.contains("stopped", true) || finalMessage.contains("failed", true)) {
                        ScheduledTaskStore.markFailed(this, scheduledTaskId)
                    } else {
                        ScheduledTaskStore.markFinished(this, scheduledTaskId)
                    }
                }
                OverlayBubble.hide(this)
                activeTaskRunner = null
                activeMissionGoal = null
                finishTaskCard()
            }
        )
        activeTaskRunner = runner
        runner.start(goal)
    }

    private fun handleMemoryCommand(text: String, convId: String, thinkingIndex: Int): Boolean {
        val normalized = text.trim()
        val remember = Regex("(?i)^(?:remember that|remember this|save this to memory)\\s+(.+)$").find(normalized)
        if (remember != null) {
            val value = remember.groupValues[1].trim()
            val saved = MemoryStore.add(this, value)
            replaceMessage(convId, thinkingIndex, if (saved) "Saved to memory." else "I couldn't save that to memory.")
            return true
        }

        val forget = Regex("(?i)^(?:forget that|forget this|remove from memory)\\s+(.+)$").find(normalized)
        if (forget != null) {
            val removed = MemoryStore.remove(this, forget.groupValues[1])
            replaceMessage(convId, thinkingIndex, if (removed) "Removed from memory." else "I couldn't find a matching memory.")
            return true
        }

        if (Regex("(?i)^(what do you remember|show my memory|what do you know about me)\\??$").matches(normalized)) {
            val memories = MemoryStore.all(this)
            val response = if (memories.isEmpty()) "I don't have any explicit memories yet." else "Here’s what I remember:\n\n" + memories.take(12).joinToString("\n") { "• $it" }
            replaceMessage(convId, thinkingIndex, response)
            return true
        }
        return false
    }

    private fun sendToServer(
        text: String,
        attachmentUris: List<Uri>,
        attachmentNames: List<String>,
        thinkingIndex: Int,
        convId: String
    ) {
        if (convId != currentConversationId || thinkingIndex !in messages.indices) return

        val historyArray = JSONArray()
        for ((index, m) in messages.withIndex()) {
            if (index == thinkingIndex) continue
            historyArray.put(JSONObject().apply {
                put("role", if (m.isUser) "user" else "assistant")
                put("content", m.text)
            })
        }

        val profile = JSONObject().apply {
            put("name_en", UserProfileStore.getNameEn(this@MainActivity))
            put("name_bn", UserProfileStore.getNameBn(this@MainActivity))
            put("notes", UserProfileStore.getNotes(this@MainActivity))
        }

        // Real multimodal transport: send the actual bytes, not just the filename.
        // The backend can now pass `images`/`attachments` into Gemini's multimodal
        // content parts. Text-only servers can safely ignore these extra fields.
        val encoded = attachmentUris.mapNotNull { AttachmentEncoder.encode(contentResolver, it) }
        val attachmentArray = JSONArray()
        encoded.forEach { a ->
            attachmentArray.put(JSONObject().apply {
                put("name", a.name)
                put("mime_type", a.mimeType)
                put("data", a.dataBase64)
                if (!a.originalMimeType.isNullOrBlank()) put("original_mime_type", a.originalMimeType)
            })
        }

        val json = JSONObject().apply {
            put("message", text)
            put("attachments", attachmentArray)
            put("images", attachmentArray)
            put("attachment_names", JSONArray(attachmentNames))
            put("history", historyArray)
            put("profile", profile)
            put("agent_context", JSONObject().apply {
                put("mode", "natural_autonomous_agent")
                put("last_goal", SkillStore.getLastGoal(this@MainActivity) ?: "")
                put("active_goal", activeMissionGoal ?: "")
                put("memories", JSONArray(MemoryStore.all(this@MainActivity).take(20)))
                put("capabilities", JSONArray(listOf(
                    "conversation", "context_followups", "memory", "scheduled_tasks",
                    "screen_control", "accessibility", "screenshot_vision", "multimodal_attachments",
                    "youtube_first_for_music", "cross_app_missions", "recovery", "verification"
                )))
                put("behavior", "React naturally to emotion and context. Distinguish conversation from commands. Resolve it/that/this/the second one from context. For missions use understand -> plan -> act -> observe -> verify -> recover -> finish. Never invent success.")
            })
        }.toString()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(serverUrl).post(body).build()
        enqueueChatRequest(request, convId, thinkingIndex, attempt = 1)
    }

    private fun isContextualMissionFollowUp(text: String): Boolean {
        val shortFollowUps = listOf(
            "do it", "do that", "do this", "go ahead", "continue", "keep going",
            "the second one", "the first one", "the other one", "that one", "this one",
            "open it", "play it", "watch it", "send it", "try again", "retry",
            "no, the other one", "not that one"
        )
        if (shortFollowUps.contains(text)) return true
        return text.matches(Regex("^(no|nah|not that|actually|instead|the other|second|first)\\s+.+"))
    }

    private fun enqueueChatRequest(request: Request, convId: String, thinkingIndex: Int, attempt: Int) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (attempt < 2) {
                    mainHandler.postDelayed({ enqueueChatRequest(request, convId, thinkingIndex, attempt + 1) }, 800L)
                } else {
                    mainHandler.post {
                        replaceMessage(convId, thinkingIndex, "Couldn't reach HML Agent. Check your connection and try again.")
                    }
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()
                if ((!response.isSuccessful || responseBody == null) && response.code in 500..599 && attempt < 2) {
                    mainHandler.postDelayed({ enqueueChatRequest(request, convId, thinkingIndex, attempt + 1) }, 800L)
                    return
                }
                mainHandler.post {
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val answer = JSONObject(responseBody).optString("answer", "")
                            replaceMessage(
                                convId,
                                thinkingIndex,
                                if (answer.isNotEmpty()) answer else "HML Agent couldn't answer that right now. Try again shortly."
                            )
                        } catch (_: Exception) {
                            replaceMessage(convId, thinkingIndex, "Something went wrong reading the response.")
                        }
                    } else if (response.code == 429) {
                        replaceMessage(convId, thinkingIndex, "HML Agent is rate-limited right now. Give it a moment and try again.")
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
        const val EXTRA_PENDING_AUTONOMOUS_ID = "pending_autonomous_id"
    }
}
