package com.hmlai.agent

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The dialogs for in-app updates: "Update available" -> progress -> installer. */
object UpdateUi {

    private var promptShowing = false

    /** Quiet startup check: only shows something if a newer build exists. */
    fun checkOnStart(activity: Activity) {
        UpdateManager.autoCheck(activity) { info -> showPrompt(activity, info) }
    }

    /** User asked ("check for updates"): always answers, even when already up to date. */
    fun checkNow(activity: Activity, onAnswer: (String) -> Unit) {
        UpdateManager.check { info, error ->
            when {
                info != null -> {
                    onAnswer("🔄 A newer version is available (build ${info.buildNumber}). You're on build ${UpdateManager.currentBuild()}.")
                    showPrompt(activity, info)
                }
                error != null -> onAnswer("⚠️ $error")
                else -> onAnswer("✅ You're on the latest version (build ${UpdateManager.currentBuild()}).")
            }
        }
    }

    /** Continue an update that was waiting for the "install unknown apps" permission. */
    fun resumePending(activity: Activity) {
        val info = UpdateManager.pendingInfo ?: return
        if (!UpdateManager.canInstallPackages(activity)) return
        UpdateManager.pendingInfo = null
        startInstall(activity, info)
    }

    private fun showPrompt(activity: Activity, info: UpdateManager.UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed || promptShowing) return
        promptShowing = true
        val notes = if (info.notes.isNotBlank()) "\n\n${info.notes.take(400)}" else ""
        MaterialAlertDialogBuilder(activity)
            .setTitle("Update HML Agent")
            .setMessage("A new version is ready (build ${info.buildNumber}).$notes")
            .setPositiveButton("Update") { _, _ ->
                promptShowing = false
                beginUpdate(activity, info)
            }
            .setNegativeButton("Later") { _, _ ->
                promptShowing = false
                UpdateManager.markDismissed(activity, info)
            }
            .setOnCancelListener {
                promptShowing = false
                UpdateManager.markDismissed(activity, info)
            }
            .show()
    }

    private fun beginUpdate(activity: Activity, info: UpdateManager.UpdateInfo) {
        if (!UpdateManager.canInstallPackages(activity)) {
            // One-time Android permission. We open the page and finish the update when the user returns.
            UpdateManager.pendingInfo = info
            Toast.makeText(activity, "Allow HML Agent to install updates, then come back.", Toast.LENGTH_LONG).show()
            UpdateManager.openInstallPermissionSettings(activity)
            return
        }
        startInstall(activity, info)
    }

    private fun startInstall(activity: Activity, info: UpdateManager.UpdateInfo) {
        val density = activity.resources.displayMetrics.density
        val label = TextView(activity).apply {
            text = "Downloading update…"
            textSize = 15f
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (22 * density).toInt()
            setPadding(pad, (16 * density).toInt(), pad, (8 * density).toInt())
            addView(label, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (14 * density).toInt()
            })
        }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Updating HML Agent")
            .setView(box)
            .setCancelable(false)
            .show()

        UpdateManager.downloadAndInstall(
            context = activity,
            info = info,
            onProgress = { percent ->
                if (percent >= 0) {
                    bar.isIndeterminate = false
                    bar.progress = percent
                    label.text = "Downloading update… $percent%"
                }
            },
            onFinished = { error ->
                if (error == null) {
                    // Android now shows (or silently applies) the install step.
                    label.text = "Installing… confirm if Android asks."
                    bar.isIndeterminate = true
                    box.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 4000)
                } else {
                    dialog.dismiss()
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}
