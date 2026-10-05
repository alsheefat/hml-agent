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
    private var recoveryPasses = 0
    private var verificationPasses = 0
    private val maxConsecutiveFailures = 3
    private val maxRecoveryPasses = 2

    fun start(goal: String) {
        if (!HmlAccessibilityService.isRunning()) {
            onFinished("Autonomous Control isn't turned on. Go to Settings > Accessibility and enable HML Agent, then try again.")
            return
        }
        currentStep = 0
        consecutiveFailures = 0
        recoveryPasses = 0
        verificationPasses = 0
        cancelled = false
        finished = false
        startedAt = System.currentTimeMillis()
        prepareStartingApp(goal)
    }

    private fun prepareStartingApp(goal: String) {
        val service = HmlAccessibilityService.instance ?: run {
            step(goal, emptyList()); return
        }
        val lower = goal.lowercase()
        val target = when {
            "youtube" in lower || "play " in lower || "watch " in lower || "song" in lower || "music" in lower -> "youtube"
            "instagram" in lower -> "instagram"
            "whatsapp" in lower -> "whatsapp"
            "telegram" in lower -> "telegram"
            "facebook" in lower -> "facebook"
            "messenger" in lower -> "messenger"
            else -> null
        }
        if (target != null) {
            onStatusUpdate("Opening $target")
            service.launchAppByName(target)
            mainHandler.postDelayed({ if (!cancelled && !finished) step(goal, listOf("Opened $target as the mission starting point.")) }, 1200)
        } else {
            step(goal, emptyList())
        }
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
        val nextHistory = (history + observation).takeLast(8)
        if (!succeeded) {
            recoveryPasses++
            if (consecutiveFailures >= maxConsecutiveFailures || recoveryPasses > maxRecoveryPasses) {
                finishOnce("I tried a few recovery paths but couldn't find the right element safely, so I stopped rather than keep guessing.")
                return
            }
            onStatusUpdate("Recovering · re-reading the screen")
            mainHandler.postDelayed({ step(goal, nextHistory + "RECOVERY: The previous action failed. Re-observe the current screen and choose a different safe route; do not repeat the same target blindly.") }, 350)
            return
        }
        recoveryPasses = 0
        mainHandler.postDelayed({ step(goal, nextHistory) }, delayMs)
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
            put("agent_mode", "adaptive_recovery")
            put("step", currentStep)
            put("max_steps", maxSteps)
            put("recovery_passes", recoveryPasses)
            put("verification_passes", verificationPasses)
            put("must_verify_completion", true)
            put("completion_rule", "Do not mark the mission done merely because an intermediate action succeeded. Continue until the user's original goal is visibly achieved on the current screen. For YouTube requests such as playing/watching a latest video, opening YouTube and typing a search query is NOT completion: inspect results, choose the intended latest/relevant result, open it, and verify playback before done.")
            put("device_context", DeviceAwareness.snapshot(context))
            put("semantic_context", CommandSemantics.parse(goal))
            put("execution_rules", "Separate entities from modifiers (contact vs carrier, person vs platform, content vs platform). Never include modifiers in the entity name. For search boxes, prefer an IME/search action over visually tapping the software keyboard. For Send actions, use a real clickable node or content description when available, then verify the message appeared before completion.")
            if (screenshotBase64 != null) put("screenshot", screenshotBase64)
        }.toString()

        val request = Request.Builder()
            .url(serverUrl)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    if (cancelled || finished) return@post
                    if (currentStep < maxSteps && recoveryPasses < maxRecoveryPasses) {
                        recoveryPasses++
                        onStatusUpdate("Connection interrupted · retrying safely")
                        mainHandler.postDelayed({
                            if (!cancelled && !finished) step(goal, history + "NETWORK RECOVERY: Planner request failed; retry once the connection is available.")
                        }, 900L * recoveryPasses)
                    } else {
                        finishOnce("Couldn't reach HML Agent to plan the next step. Check your connection.")
                    }
                }
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
                            "ime_action", "search", "submit_search", "press_enter" -> {
                                onStatusUpdate("Submitting the current search")
                                val ok = service.performImeAction()
                                recordOutcomeAndContinue(goal, history, if (ok) "Submitted the active field with the Android IME action" else "IME action was unavailable — choose a visible app control instead", ok, 1200)
                            }
                            "launch_app", "open_app", "open" -> {
                                val app = target.ifBlank { reasoning }
                                onStatusUpdate("Opening · $app")
                                val ok = service.launchAppByName(app)
                                recordOutcomeAndContinue(goal, history, if (ok) "Opened $app" else "Couldn't open $app", ok, 1200)
                            }
                            "wait", "sleep" -> {
                                val delay = target.toLongOrNull()?.coerceIn(300L, 5000L) ?: 1000L
                                onStatusUpdate("Waiting · ${delay}ms")
                                mainHandler.postDelayed({
                                    if (!cancelled && !finished) step(goal, history + "Waited ${delay}ms")
                                }, delay)
                            }
                            "tap" -> {
                                val tapTarget = target.lowercase()
                                if (tapTarget.contains("search") || tapTarget == "done" || tapTarget == "enter") {
                                    onStatusUpdate("Submitting the current search")
                                    val imeOk = service.performImeAction()
                                    if (imeOk) {
                                        recordOutcomeAndContinue(goal, history, "Used the Android IME action instead of tapping the software keyboard", true, 1200)
                                    } else {
                                        executeTap(goal, history, target, reasoning, service)
                                    }
                                } else if (isSensitiveTarget(target)) {
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
                            "verify", "observe" -> {
                                onStatusUpdate("Verifying the result")
                                mainHandler.postDelayed({
                                    if (!cancelled && !finished) step(goal, history + "Verification pass requested by planner")
                                }, 500)
                            }
                            "done" -> {
                                if (verificationPasses < 2) {
                                    verificationPasses++
                                    onStatusUpdate("Verifying completion · pass $verificationPasses/2")
                                    mainHandler.postDelayed({
                                        if (!cancelled && !finished) {
                                            step(goal, history + "PLANNER CLAIMED DONE: do not stop. Visually verify that the ORIGINAL USER GOAL is actually complete. If a YouTube search is visible, continue to the intended video and verify playback.")
                                        }
                                    }, 600)
                                } else {
                                    finishOnce(reasoning.ifEmpty { "Done." })
                                }
                            }
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
