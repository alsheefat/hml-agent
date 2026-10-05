package com.hmlai.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class ScheduledActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val actionType = intent.getStringExtra("actionType") ?: return
        val payload = intent.getStringExtra("payload") ?: return

        when (actionType) {
            "alarm" -> fireAlarm(context, payload)
            "reminder" -> showReminderNotification(context, payload)
            "call" -> placeScheduledCall(context, payload)
            "autonomous" -> launchScheduledAutonomousTask(context, payload, intent.getStringExtra("taskId"))
        }
    }


    private fun fireAlarm(context: Context, label: String) {
        val channelId = "hml_agent_alarms_v22"
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            val audio = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(channelId, "HML Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(sound, audio)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 350, 700, 350, 1100)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("alarmLabel", label)
            putExtra("alarmNotificationId", label.hashCode())
        }
        val requestCode = label.hashCode()
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, requestCode, launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("HML Alarm")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            notificationManager.notify(requestCode, notification)
        }

        // Full-screen alarm notifications are the Android-supported route for an alarm UI.
        try { context.startActivity(launchIntent) } catch (_: Exception) { }
    }

    /** Scheduled autonomous tasks ("at 12:00 AM, open Messenger and say
     * Happy Birthday to Wazi") can't run silently in the background —
     * Accessibility Service gestures need a live, unlocked, foreground
     * window to actually target, which a BroadcastReceiver with the
     * screen off can't provide. Instead, this fires a high-priority
     * notification that opens the app straight into running the task —
     * MainActivity picks up the pending goal from the intent extra and
     * starts AutonomousTaskRunner automatically once it's in the
     * foreground. Not fully silent, but it's the reliable version of
     * this given Android's real constraints on background gesture
     * automation. */
    private fun launchScheduledAutonomousTask(context: Context, goal: String, taskId: String?) {
        if (!taskId.isNullOrBlank()) ScheduledTaskStore.markRunning(context, taskId)
        val channelId = "hml_agent_autonomous"
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Scheduled tasks",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_PENDING_AUTONOMOUS_GOAL, goal)
            if (!taskId.isNullOrBlank()) putExtra(MainActivity.EXTRA_PENDING_AUTONOMOUS_ID, taskId)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, goal.hashCode(), launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("HML Agent — scheduled task")
            .setContentText("Tap to run: $goal")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            notificationManager.notify(goal.hashCode(), notification)
        }

        // Also try to open it immediately if HML Agent is already the
        // foreground/recently-used app — otherwise the notification above
        // is the reliable path (Android restricts apps from just popping
        // themselves open from the background on modern versions).
        try {
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            // Expected to fail/be ignored on many devices when not already
            // foreground — the notification's fullScreenIntent is the
            // dependable fallback.
        }
    }

    private fun showReminderNotification(context: Context, text: String) {
        val channelId = "hml_agent_reminders"

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("HML Agent reminder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        }
    }

    private fun placeScheduledCall(context: Context, contactName: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showReminderNotification(context, "Tried to call $contactName but Call permission isn't granted.")
            return
        }

        val phoneNumber = ContactResolver.findPhoneNumberForContact(context, contactName)
        if (phoneNumber == null) {
            showReminderNotification(context, "Tried to call $contactName but couldn't find that contact.")
            return
        }

        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val uri = Uri.fromParts("tel", phoneNumber, null)
            @Suppress("MissingPermission")
            telecomManager.placeCall(uri, null)
        } catch (e: Exception) {
            showReminderNotification(context, "Tried to call $contactName but something went wrong.")
        }
    }
}
