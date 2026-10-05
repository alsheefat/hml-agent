package com.hmlai.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmActivity : AppCompatActivity() {
    private var ringtone: Ringtone? = null
    private var notificationId: Int = 0
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        val label = intent.getStringExtra("alarmLabel") ?: "HML Alarm"
        notificationId = intent.getIntExtra("alarmNotificationId", label.hashCode())
        val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(32))
            setBackgroundColor(ContextCompat.getColor(this@AlarmActivity, R.color.surface_raised))
        }

        val eyebrow = TextView(this).apply {
            text = "HML ALARM"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@AlarmActivity, R.color.blue_glow))
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        }
        val time = TextView(this).apply {
            text = now
            textSize = 48f
            setTextColor(ContextCompat.getColor(this@AlarmActivity, R.color.text_primary))
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        val title = TextView(this).apply {
            text = label
            textSize = 21f
            setTextColor(ContextCompat.getColor(this@AlarmActivity, R.color.text_primary))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(28))
        }
        root.addView(eyebrow, LinearLayout.LayoutParams(-1, dp(28)))
        root.addView(time, LinearLayout.LayoutParams(-1, dp(70)))
        root.addView(title, LinearLayout.LayoutParams(-1, dp(62)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val snooze = Button(this).apply {
            text = "Snooze 10 min"
            setOnClickListener { snoozeAndClose(label) }
        }
        val dismiss = Button(this).apply {
            text = "Dismiss"
            setOnClickListener { stopAlarm(); finish() }
        }
        buttons.addView(snooze, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        buttons.addView(dismiss, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6) })
        root.addView(buttons, LinearLayout.LayoutParams(-1, dp(60)))
        setContentView(root)

        startAlarmSound()
    }

    private fun startAlarmSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(this, uri)
        ringtone?.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try { ringtone?.play() } catch (_: Exception) {}

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 700, 350, 700, 350, 1100)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun snoozeAndClose(label: String) {
        stopAlarm()
        val trigger = System.currentTimeMillis() + 10 * 60 * 1000L
        val requestCode = (trigger.toString() + "alarm" + label).hashCode()
        val receiverIntent = Intent(this, ScheduledActionReceiver::class.java).apply {
            putExtra("actionType", "alarm")
            putExtra("payload", label)
        }
        val pi = PendingIntent.getBroadcast(
            this, requestCode, receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            finish()
            return
        }
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(trigger, pi), pi
        )
        finish()
    }

    private fun stopAlarm() {
        try { ringtone?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(notificationId)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
