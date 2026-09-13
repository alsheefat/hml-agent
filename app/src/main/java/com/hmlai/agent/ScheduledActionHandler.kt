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
    val actionType: String,   // "reminder" or "call"
    val payload: String,      // reminder text, or contact name for a call
    val originalText: String
)

object ScheduledActionHandler {

    /** Tries to parse a message as a scheduled/delayed command
     * ("remind me to X at 5pm", "call mom at 6:30 tomorrow").
     * Returns null if this doesn't look like a scheduling request. */
    fun tryParse(rawText: String): ScheduledCommand? {
        val text = rawText.trim().lowercase()

        // "remind me to <thing> at <time>"
        val reminderMatch = Regex("^remind me to (.+?) at (.+)$").find(text)
        if (reminderMatch != null) {
            val what = reminderMatch.groupValues[1].trim()
            val whenText = reminderMatch.groupValues[2].trim()
            val triggerAt = parseTimeExpression(whenText) ?: return null
            return ScheduledCommand(triggerAt, "reminder", what, rawText)
        }

        // "call <contact> at <time>"
        val callMatch = Regex("^call (.+?) at (.+)$").find(text)
        if (callMatch != null) {
            val contact = callMatch.groupValues[1].trim()
            val whenText = callMatch.groupValues[2].trim()
            val triggerAt = parseTimeExpression(whenText) ?: return null
            return ScheduledCommand(triggerAt, "call", contact, rawText)
        }

        return null
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

        classifyViaServer(rawText) { classification ->
            if (classification == null || !classification.isCommand || !classification.isScheduled) {
                callback(null)
                return@classifyViaServer
            }
            if (classification.type != "reminder" && classification.type != "call") {
                callback(null)
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

    private fun classifyViaServer(text: String, callback: (ScheduleClassification?) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

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

        val requestCode = command.triggerAtMillis.toInt()
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
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                command.triggerAtMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            return "I don't have permission to schedule exact alarms. Please grant it in app settings."
        }

        val calendar = Calendar.getInstance().apply { timeInMillis = command.triggerAtMillis }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val timeLabel = String.format("%02d:%02d", hour, minute)

        return when (command.actionType) {
            "reminder" -> "⏰ Got it — I'll remind you to \"${command.payload}\" at $timeLabel."
            "call" -> "⏰ Scheduled — I'll call ${command.payload} at $timeLabel."
            else -> "⏰ Scheduled for $timeLabel."
        }
    }
}
