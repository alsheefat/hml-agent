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
 * Agent Mode 2.0 runner.
 *
 * The server still chooses ONE concrete next action at a time, preserving the
 * existing backend protocol. The client now adds a bounded mission lifetime,
 * cancellation, action verification, retry limits, sensitive-action confirmation,
 * and bounded observation history so the agent fails safely instead of guessing.
 */
class AutonomousTaskRunner(
    private val context: Context,
    private val onStatusUpdate: (String) -> Unit,
    private val onConfirmationRequired: (action: String, target: String, resume: () -> Unit) -> Unit,
    private val onFinished: (String) -> Unit
) {
    private val serverUrl = "https://hml-agent-server.onrender.com/screen-action"
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val maxSteps = 15
    private val maxMissionMs = 120_000L
    private var currentStep = 0
    private var startedAt = 0L
    private var cancelled = false
    private var finished = false

    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 3

    fun start(goal: String) {
        if (!HmlAccessibilityService.isRunning()) {
            onFinished("Autonomous Control isn't turned on. Go to Settings > Accessibility and enable HML Agent, then try again.")
            return
        }
        currentStep = 0
        consecutiveFailures = 0
        cancelled = false
        finished = false
        startedAt = System.currentTimeMillis()
        step(goal, emptyList())
    }

    fun cancel() {
        cancelled = true
        mainHandler.removeCallbacksAndMessages(null)
        finishOnce("Task stopped. I left the current screen unchanged after stopping.")
    }

    private fun step(goal: String, history: List<String>) {
        if (cancelled || finished) return
        if (System.currentTimeMillis() - startedAt > maxMissionMs) {
            finishOnce("I stopped after two minutes to avoid an uncontrolled automation loop.")
            return
        }
        if (currentStep >= maxSteps) {
            finishOnce("Stopped after $maxSteps steps to keep the task bounded and safe.")
            return
        }
        currentStep++

        val service = HmlAccessibilityService.instance
        if (service == null) {
            finishOnce("Autonomous Control turned off mid-task. Please re-enable it and try again.")
            return
        }

        val screenText = service.describeCurrentScreen()
        onStatusUpdate("Reading the screen · step $currentStep/$maxSteps")

        service.captureScreenshotBase64 { screenshotBase64 ->
            if (cancelled || finished) return@captureScreenshotBase64
            sendStepRequest(goal, history.takeLast(8), screenText, screenshotBase64, service)
        }
    }

    private fun attemptWithRetry(attempt: () -> Boolean, onOutcome: (Boolean, Boolean) -> Unit) {
        if (cancelled || finished) return
        if (attempt()) {
            onOutcome(true, false)
            return
        }
        mainHandler.postDelayed({
            if (!cancelled && !finished) onOutcome(attempt(), true)
        }, 500)
    }

    private fun recordOutcomeAndContinue(
        goal: String,
        history: List<String>,
        observation: String,
        succeeded: Boolean,
        delayMs: Long
    ) {
        if (cancelled || finished) return
        consecutiveFailures = if (succeeded) 0 else consecutiveFailures + 1
        if (consecutiveFailures >= maxConsecutiveFailures) {
            finishOnce("I tried a few times but couldn't find the right element safely, so I stopped rather than keep guessing.")
            return
        }
        mainHandler.postDelayed({ step(goal, (history + observation).takeLast(8)) }, delayMs)
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
            put("screen", screenText.take(20_000))
            put("history", history.joinToString("\n").take(8_000))
            if (screenshotBase64 != null) put("screenshot", screenshotBase64)
        }.toString()

        val request = Request.Builder()
            .url(serverUrl)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { finishOnce("Couldn't reach HML Agent to plan the next step. Check your connection.") }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()
                mainHandler.post {
                    if (cancelled || finished) return@post
                    if (!response.isSuccessful || responseBody == null) {
                        finishOnce("HML Agent couldn't safely plan the next step right now.")
                        return@post
                    }
                    try {
                        val result = JSONObject(responseBody)
                        val action = result.optString("action", "").lowercase()
                        val target = result.optString("target", "")
                        val reasoning = result.optString("reasoning", "")

                        when (action) {
                            "tap" -> {
                                if (isSensitiveTarget(target)) {
                                    onStatusUpdate("Waiting for confirmation · $target")
                                    onConfirmationRequired(action, target) {
                                        if (!cancelled && !finished) executeTap(goal, history, target, reasoning, service)
                                    }
                                } else {
                                    executeTap(goal, history, target, reasoning, service)
                                }
                            }
                            "type" -> {
                                if (isSensitiveTarget(target)) {
                                    onStatusUpdate("Waiting for confirmation before entering sensitive text")
                                    onConfirmationRequired(action, "the requested text") {
                                        if (!cancelled && !finished) executeType(goal, history, target, reasoning, service)
                                    }
                                } else {
                                    executeType(goal, history, target, reasoning, service)
                                }
                            }
                            "scroll_down" -> {
                                onStatusUpdate("Scrolling down")
                                val ok = service.scrollDown()
                                recordOutcomeAndContinue(goal, history, "Scrolled down${if (!ok) " but the gesture was rejected" else ""}", ok, 700)
                            }
                            "scroll_up" -> {
                                onStatusUpdate("Scrolling up")
                                val ok = service.scrollUp()
                                recordOutcomeAndContinue(goal, history, "Scrolled up${if (!ok) " but the gesture was rejected" else ""}", ok, 700)
                            }
                            "back" -> {
                                onStatusUpdate("Going back")
                                val ok = service.goBack()
                                recordOutcomeAndContinue(goal, history, "Went back${if (!ok) " but Android rejected the action" else ""}", ok, 700)
                            }
                            "done" -> finishOnce(reasoning.ifEmpty { "Done." })
                            else -> finishOnce("I wasn't sure how to continue this task safely, so I stopped.")
                        }
                    } catch (_: Exception) {
                        finishOnce("Something went wrong understanding the next step, so I stopped safely.")
                    }
                }
            }
        })
    }

    private fun executeTap(goal: String, history: List<String>, target: String, reasoning: String, service: HmlAccessibilityService) {
        onStatusUpdate("Tapping · $target")
        attemptWithRetry(
            attempt = { service.tapByText(target) },
            onOutcome = { succeeded, usedRetry ->
                val observation = if (succeeded) {
                    "Tapped \"$target\"${if (usedRetry) " on retry" else ""}: $reasoning"
                } else {
                    "Failed to tap \"$target\" after retry — choose a different target"
                }
                recordOutcomeAndContinue(goal, history, observation, succeeded, 900)
            }
        )
    }

    private fun executeType(goal: String, history: List<String>, target: String, reasoning: String, service: HmlAccessibilityService) {
        onStatusUpdate("Entering text")
        attemptWithRetry(
            attempt = { service.typeIntoActiveField(target) },
            onOutcome = { succeeded, usedRetry ->
                val observation = if (succeeded) {
                    "Entered text${if (usedRetry) " on retry" else ""}: $reasoning"
                } else {
                    "Failed to enter text after retry — tap the field first"
                }
                recordOutcomeAndContinue(goal, history, observation, succeeded, 650)
            }
        )
    }

    /**
     * Prevents the agent from silently performing common high-impact actions.
     * The user explicitly approves these individual steps before execution.
     */
    private fun isSensitiveTarget(target: String): Boolean {
        val t = target.lowercase()
        val sensitive = listOf(
            "send", "submit", "post", "publish", "delete", "remove", "buy",
            "purchase", "pay", "transfer", "call", "confirm", "place order",
            "accept", "allow", "block", "report"
        )
        return sensitive.any { t.contains(it) }
    }

    private fun finishOnce(message: String) {
        if (finished) return
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        onFinished(message)
    }
}
