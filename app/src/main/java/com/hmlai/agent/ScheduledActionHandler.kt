package com.hmlai.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ScheduledCommand(
    val triggerAtMillis: Long,
    val actionType: String,   // "alarm", "reminder", "call", or "autonomous"
    val payload: String,      // reminder text, or contact name for a call
    val originalText: String
)

object ScheduledActionHandler {

    /** Tries to parse a message as a scheduled/delayed command
     * ("remind me to X at 5pm", "call mom at 6:30 tomorrow").
     * Returns null if this doesn't look like a scheduling request. */
    fun tryParse(rawText: String): ScheduledCommand? {
        val raw = rawText.trim()
        val text = raw.lowercase()

        // Real alarm: "set alarm at 2:29 and name as Messenger Issue",
        // "set an alarm for 2:29 called Messenger Issue". Keep the user's
        // alarm label instead of lowercasing it.
        val alarmMatch = Regex(
            "^set\\s+(?:an\\s+)?alarm\\s+(?:at|for)\\s+(.+?)(?:\\s+(?:and\\s+)?(?:name|called|named)\\s+(?:as\\s+)?(.+))$",
            RegexOption.IGNORE_CASE
        ).find(raw)
        if (alarmMatch != null) {
            val whenText = alarmMatch.groupValues[1].trim()
            val label = alarmMatch.groupValues[2].trim().trim('\"', '\'')
            val triggerAt = parseTimeExpression(whenText) ?: return null
            return ScheduledCommand(triggerAt, "alarm", label.ifBlank { "HML Alarm" }, raw)
        }

        // Short alarm form: "alarm at 2:29" / "set alarm for 2:29".
        val shortAlarmMatch = Regex(
            "^(?:set\\s+)?alarm\\s+(?:at|for)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(raw)
        if (shortAlarmMatch != null) {
            val triggerAt = parseTimeExpression(shortAlarmMatch.groupValues[1].trim()) ?: return null
            return ScheduledCommand(triggerAt, "alarm", "HML Alarm", raw)
        }

        // "remind me to <thing> at <time>"
        val reminderMatch = Regex("^remind me to (.+?) at (.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (reminderMatch != null) {
            val what = reminderMatch.groupValues[1].trim()
            val whenText = reminderMatch.groupValues[2].trim()
            val triggerAt = parseTimeExpression(whenText) ?: return null
            return ScheduledCommand(triggerAt, "reminder", what, rawText)
        }

        // "call <contact> at <time>"
        val callMatch = Regex("^call (.+?) at (.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (callMatch != null) {
            val contact = callMatch.groupValues[1].trim()
            val whenText = callMatch.groupValues[2].trim()
            val triggerAt = parseTimeExpression(whenText) ?: return null
            return ScheduledCommand(triggerAt, "call", contact, rawText)
        }

        return null
    }

    /** Detects scheduled autonomous goals such as:
     * "send ThunderBlitz Group ... on Instagram at 3 pm" or
     * "play Blinding Lights at 6:30". The complete original sentence is
     * preserved as the goal so the normal agent can execute it later. */
    fun tryParseAutonomous(rawText: String): ScheduledCommand? {
        val text = rawText.trim()
        val lower = text.lowercase()
        val timeRegex = Regex("\\b(?:at|for)\\s+(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)\\b(?:\\s+(tomorrow))?", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(lower) ?: return null

        val actionWords = listOf(
            "send", "message", "text", "tell", "reply", "dm", "post",
            "play", "watch", "open", "search", "find", "scroll", "tap",
            "like", "share", "follow", "upload", "download", "call"
        )
        if (actionWords.none { lower.contains(Regex("\\b${Regex.escape(it)}\\b")) }) return null

        val timePart = match.groupValues[1] + if (match.groupValues.getOrNull(2).isNullOrBlank()) "" else " tomorrow"
        val trigger = parseTimeExpression(timePart) ?: return null
        return ScheduledCommand(trigger, "autonomous", text, rawText)
    }

    /** Same as tryParse, but if the fast local patterns don't match, asks
     * the server to understand the scheduling intent — covers natural
     * phrasing variation and Bangla ("Abbu ke 6 tay call koro", "amake
     * 9 tay reminder dao") the same way the fast path only covers
     * "remind me to X at Y" / "call X at Y" literally. */
    fun tryParseWithAiFallback(rawText: String, callback: (ScheduledCommand?) -> Unit) {
        val localResult = tryParse(rawText)
        if (localResult != null) {
            callback(localResult)
            return
        }
        val autonomousLocal = tryParseAutonomous(rawText)
        if (autonomousLocal != null) {
            callback(autonomousLocal)
            return
        }

        classifyViaServer(rawText) { classification ->
            if (classification == null || !classification.isCommand || !classification.isScheduled) {
                callback(null)
                return@classifyViaServer
            }
            if (classification.type != "reminder" && classification.type != "call" && classification.type != "alarm") {
                val autonomous = tryParseAutonomous(rawText)
                callback(autonomous)
                return@classifyViaServer
            }

            val whenText = classification.time + (if (classification.tomorrow) " tomorrow" else "")
            val triggerAt = parseTimeExpression(whenText)
            if (triggerAt == null) {
                callback(null)
                return@classifyViaServer
            }

            callback(ScheduledCommand(triggerAt, classification.type, classification.target, rawText))
        }
    }

    private data class ScheduleClassification(
        val isCommand: Boolean,
        val type: String,
        val target: String,
        val isScheduled: Boolean,
        val time: String,
        val tomorrow: Boolean
    )

    // One shared client instead of a new connection/thread pool per message.
    private val classifyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun classifyViaServer(text: String, callback: (ScheduleClassification?) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val client = classifyClient

        val json = JSONObject().put("message", text).toString()
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://hml-agent-server.onrender.com/classify-command")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(null) }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()
                mainHandler.post {
                    if (!response.isSuccessful || responseBody == null) {
                        callback(null)
                        return@post
                    }
                    try {
                        val result = JSONObject(responseBody)
                        callback(
                            ScheduleClassification(
                                isCommand = result.optBoolean("is_command", false),
                                type = result.optString("type", "none"),
                                target = result.optString("target", ""),
                                isScheduled = result.optBoolean("is_scheduled", false),
                                time = result.optString("time", ""),
                                tomorrow = result.optBoolean("tomorrow", false)
                            )
                        )
                    } catch (e: Exception) {
                        callback(null)
                    }
                }
            }
        })
    }

    /** Parses simple time expressions like "5pm", "5:30pm", "17:00",
     * optionally with "tomorrow". Returns epoch millis for the next
     * occurrence of that time, or null if it can't be parsed. */
    private fun parseTimeExpression(whenText: String): Long? {
        val tomorrow = whenText.contains("tomorrow")
        val cleaned = whenText.replace("tomorrow", "").trim()

        val pattern = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
        val matcher = pattern.matcher(cleaned)
        if (!matcher.find()) return null

        var hour = matcher.group(1)?.toIntOrNull() ?: return null
        val minute = matcher.group(2)?.toIntOrNull() ?: 0
        val meridiem = matcher.group(3)

        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0

        if (hour !in 0..23 || minute !in 0..59) return null

        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        if (tomorrow || calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return calendar.timeInMillis
    }

    /** Registers a real system alarm so this fires even if the app is
     * closed. Returns a human-readable confirmation string. */
    fun schedule(context: Context, command: ScheduledCommand): String {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ScheduledActionReceiver::class.java).apply {
            putExtra("actionType", command.actionType)
            putExtra("payload", command.payload)
        }

        // Time + text, not time alone: two reminders set for the same minute used to share a
        // request code, so the second silently replaced the first.
        val requestCode = (command.triggerAtMillis.toString() + command.actionType + command.payload).hashCode()
        if (command.actionType == "autonomous") intent.putExtra("taskId", requestCode.toString())
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                return "I need \"Schedule exact alarms\" permission to set reminders. Please grant it in app settings."
            }
            if (command.actionType == "alarm") {
                val showIntent = PendingIntent.getActivity(
                    context,
                    requestCode + 1,
                    Intent(context, AlarmActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(command.triggerAtMillis, showIntent),
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    command.triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            return "I don't have permission to schedule exact alarms. Please grant it in app settings."
        }

        val calendar = Calendar.getInstance().apply { timeInMillis = command.triggerAtMillis }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val timeLabel = String.format("%02d:%02d", hour, minute)

        return when (command.actionType) {
            "alarm" -> "Alarm set — \"${command.payload}\" at $timeLabel."
            "reminder" -> "Reminder set — \"${command.payload}\" at $timeLabel."
            "call" -> "⏰ Scheduled — I'll call ${command.payload} at $timeLabel."
            "autonomous" -> {
                val id = requestCode.toString()
                ScheduledTaskStore.add(context, PendingTask(id, command.payload, command.triggerAtMillis))
                "⏰ Scheduled for $timeLabel — I'll execute this task then: \"${command.payload}\""
            }
            else -> "⏰ Scheduled for $timeLabel."
        }
    }

    /** Schedules a full autonomous on-screen task ("open Messenger and say
     * Happy Birthday to Wazi") to run at a specific time. Because
     * Accessibility Service gestures need a live foreground window,
     * this can't run fully silently in the background — at the
     * scheduled time, ScheduledActionReceiver shows a notification that
     * launches the app straight into the task. See that class for the
     * full explanation of why. */
    fun scheduleAutonomousTask(context: Context, triggerAtMillis: Long, goal: String): String {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ScheduledActionReceiver::class.java).apply {
            putExtra("actionType", "autonomous")
            putExtra("payload", goal)
        }

        val requestCode = (triggerAtMillis.toString() + "autonomous" + goal).hashCode()
        intent.putExtra("taskId", requestCode.toString())
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                return "I need \"Schedule exact alarms\" permission for this. Please grant it in app settings."
            }
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            return "I don't have permission to schedule exact alarms. Please grant it in app settings."
        }

        val calendar = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val timeLabel = String.format("%02d:%02d", hour, minute)

        val taskId = requestCode.toString()
        ScheduledTaskStore.add(context, PendingTask(taskId, goal, triggerAtMillis))
        return "⏰ Scheduled for $timeLabel — I'll execute: \"$goal\". " +
            "Autonomous Control must be enabled in Accessibility settings."
    }
}
