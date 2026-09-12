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
            "reminder" -> showReminderNotification(context, payload)
            "call" -> placeScheduledCall(context, payload)
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
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showReminderNotification(context, "Tried to call $contactName but Contacts permission isn't granted.")
            return
        }

        val resolver = context.contentResolver
        var phoneNumber: String? = null
        val cursor = resolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$contactName%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                phoneNumber = it.getString(index)
            }
        }

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
