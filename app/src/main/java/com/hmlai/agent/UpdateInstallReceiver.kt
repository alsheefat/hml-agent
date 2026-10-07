package com.hmlai.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat

/**
 * 1) Gets the PackageInstaller's status for an in-app update. When Android needs the user's
 *    confirmation it hands us the confirmation screen to open.
 * 2) After the new version is installed (MY_PACKAGE_REPLACED) it re-opens HML Agent, if the
 *    update was started from inside the app a moment ago.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STATUS = "com.hmlai.agent.UPDATE_INSTALL_STATUS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STATUS -> handleStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (UpdateManager.wasInstallingRecently(context)) {
                    UpdateManager.clearInstalling(context)
                    try {
                        val launch = Intent(context, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(launch)
                    } catch (_: Exception) {
                        // Android may block starting an activity from the background; harmless.
                    }
                }
            }
        }
    }

    private fun handleStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    UpdateAutoApprover.arm()
                    try {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirm)
                    } catch (_: Exception) {
                        Toast.makeText(context, "Open the HML Agent update from your notifications to finish.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The process is normally replaced before this runs; nothing else to do.
                UpdateAutoApprover.disarm()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                UpdateManager.clearInstalling(context)
                Toast.makeText(context, "Update cancelled.", Toast.LENGTH_SHORT).show()
            }
            else -> {
                UpdateManager.clearInstalling(context)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Update failed."
                Toast.makeText(context, "Update failed: $message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
