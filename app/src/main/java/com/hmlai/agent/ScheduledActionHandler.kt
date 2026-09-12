package com.hmlai.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
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
