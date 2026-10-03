package com.hmlai.agent

import android.content.Context
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
import java.util.concurrent.TimeUnit

/**
 * Drives a full on-screen automation task end-to-end:
 *   1. Read the current screen (via HmlAccessibilityService)
 *   2. Send the goal + screen content to the AI server's /screen-action route
 *   3. Get back ONE concrete next action (tap "X" / type "Y" / scroll / done)
 *   4. Execute it, then loop back to step 1
 * Stops when the AI says the task is complete, or after a safety cap on
 * steps so a misunderstanding can never loop forever.
 */
class AutonomousTaskRunner(
    private val context: Context,
    private val onStatusUpdate: (String) -> Unit,
    private val onFinished: (String) -> Unit
) {
    private val serverUrl = "https://hml-agent-server.onrender.com/screen-action"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val maxSteps = 12
    private var currentStep = 0

    // The old loop ignored the true/false result of tapByText/typeIntoActiveField and
    // always moved on as if the action worked. Now a failed action is retried once and
    // reported back to the planner as a real observation, and three failures in a row
    // stop the task instead of burning through every step blind.
    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 3

    fun start(goal: String) {
        if (!HmlAccessibilityService.isRunning()) {
            onFinished(
                "Autonomous Control isn't turned on. Go to Settings > Accessibility " +
                    "and enable HML Agent, then try again."
            )
            return
        }
        currentStep = 0
        consecutiveFailures = 0
        step(goal, emptyList())
    }

    private fun step(goal: String, history: List<String>) {
        if (currentStep >= maxSteps) {
            onFinished("Stopped after $maxSteps steps to be safe — the task may be more complex than I could finish automatically.")
            return
        }
        currentStep++

        val service = HmlAccessibilityService.instance
        if (service == null) {
            onFinished("Autonomous Control turned off mid-task. Please re-enable it and try again.")
            return
        }

        val screenText = service.describeCurrentScreen()
        onStatusUpdate("👀 Reading the screen (step $currentStep)...")

        // Capture a real screenshot alongside the text element list —
        // text alone can't distinguish icon-only buttons, images, or
        // visual layout (confirmed as a real gap by two independent
        // reference projects, PokeClaw and Mobilerun, which both send
        // screenshots + accessibility tree together rather than text
        // alone). Gracefully proceeds text-only if capture fails or
        // isn't supported on this Android version.
        service.captureScreenshotBase64 { screenshotBase64 ->
            sendStepRequest(goal, history, screenText, screenshotBase64, service)
        }
    }

    /** Runs an action; if it fails, waits briefly and tries exactly once more. */
    private fun attemptWithRetry(attempt: () -> Boolean, onOutcome: (Boolean, Boolean) -> Unit) {
        if (attempt()) {
            onOutcome(true, false)
            return
        }
        mainHandler.postDelayed({ onOutcome(attempt(), true) }, 500)
    }

    /** Feeds the real outcome back into history, and stops after repeated failures. */
    private fun recordOutcomeAndContinue(
        goal: String,
        history: List<String>,
        observation: String,
        succeeded: Boolean,
        delayMs: Long
    ) {
        consecutiveFailures = if (succeeded) 0 else consecutiveFailures + 1
        if (consecutiveFailures >= maxConsecutiveFailures) {
            onFinished(
                "I tried a few times but couldn't find the right element on screen to continue " +
                    "this task safely, so I stopped rather than keep guessing."
            )
            return
        }
        mainHandler.postDelayed({ step(goal, history + observation) }, delayMs)
    }

    private fun sendStepRequest(
        goal: String,
        history: List<String>,
        screenText: String,
        screenshotBase64: String?,
        service: HmlAccessibilityService
    ) {
        val json = JSONObject().apply {
            put("goal", goal)
            put("screen", screenText)
            put("history", history.joinToString("\n"))
            if (screenshotBase64 != null) {
                put("screenshot", screenshotBase64)
            }
        }.toString()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(serverUrl).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    onFinished("Couldn't reach HML Agent to plan the next step. Check your connection.")
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()
                mainHandler.post {
                    if (!response.isSuccessful || responseBody == null) {
                        onFinished("HML Agent couldn't plan the next step right now.")
                        return@post
                    }
                    try {
                        val result = JSONObject(responseBody)
                        val action = result.optString("action", "")
                        val target = result.optString("target", "")
                        val reasoning = result.optString("reasoning", "")

                        when (action) {
                            "tap" -> {
                                onStatusUpdate("👆 Tapping \"$target\"...")
                                attemptWithRetry(
                                    attempt = { service.tapByText(target) },
                                    onOutcome = { succeeded, usedRetry ->
                                        val observation = if (succeeded) {
                                            "Tapped \"$target\"${if (usedRetry) " (succeeded on retry)" else ""}: $reasoning"
                                        } else {
                                            "FAILED to tap \"$target\" (element not found on screen, even after retry) — try a different target or approach"
                                        }
                                        recordOutcomeAndContinue(goal, history, observation, succeeded, 900)
                                    }
                                )
                            }
                            "type" -> {
                                onStatusUpdate("⌨️ Typing \"$target\"...")
                                attemptWithRetry(
                                    attempt = { service.typeIntoActiveField(target) },
                                    onOutcome = { succeeded, usedRetry ->
                                        val observation = if (succeeded) {
                                            "Typed \"$target\"${if (usedRetry) " (succeeded on retry)" else ""}: $reasoning"
                                        } else {
                                            "FAILED to type \"$target\" (no editable field currently focused, even after retry) — try tapping the field first"
                                        }
                                        recordOutcomeAndContinue(goal, history, observation, succeeded, 600)
                                    }
                                )
                            }
                            "scroll_down" -> {
                                onStatusUpdate("⬇️ Scrolling down...")
                                service.scrollDown()
                                mainHandler.postDelayed({
                                    step(goal, history + "Scrolled down")
                                }, 700)
                            }
                            "scroll_up" -> {
                                onStatusUpdate("⬆️ Scrolling up...")
                                service.scrollUp()
                                mainHandler.postDelayed({
                                    step(goal, history + "Scrolled up")
                                }, 700)
                            }
                            "back" -> {
                                onStatusUpdate("↩️ Going back...")
                                service.goBack()
                                mainHandler.postDelayed({
                                    step(goal, history + "Went back")
                                }, 700)
                            }
                            "done" -> {
                                onFinished(reasoning.ifEmpty { "✅ Done." })
                            }
                            else -> {
                                onFinished("I wasn't sure how to continue this task safely, so I stopped.")
                            }
                        }
                    } catch (e: Exception) {
                        onFinished("Something went wrong understanding the next step.")
                    }
                }
            }
        })
    }
}
