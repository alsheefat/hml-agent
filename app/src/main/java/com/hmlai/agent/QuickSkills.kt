package com.hmlai.agent

import android.content.Context
import android.content.Intent
import android.net.Uri

sealed class SkillOutcome {
    data class Success(val message: String) : SkillOutcome()

    /** [stopHere] = stop now and tell the user (don't hand over to the planner, e.g. a message may already be sent). */
    data class Failure(val reason: String, val stopHere: Boolean = false) : SkillOutcome()
}

class SkillEnv(
    val context: Context,
    val service: HmlAccessibilityService,
    val tools: UiTools,
    val status: (String) -> Unit
) {
    val screenHeight: Int get() = context.resources.displayMetrics.heightPixels

    /** Opens the first installed package from [packages]; returns which one, or null. */
    fun launchPackage(packages: List<String>, freshStart: Boolean): String? {
        val pm = context.packageManager
        for (pkg in packages) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            var flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (freshStart) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent.addFlags(flags)
            try {
                service.startActivity(intent)
                return pkg
            } catch (_: Exception) {
            }
        }
        return null
    }
}

interface QuickSkill {
    fun run(env: SkillEnv): SkillOutcome
}

/**
 * Built-in, deterministic skills for the most common phone tasks. They read the real screen and
 * act step by step with verification, instead of asking a remote planner to guess each tap.
 * If a skill can't finish, the normal planner takes over from the current screen.
 */
object QuickSkills {

    private const val APPS =
        "(whatsapp|whats app|messenger|facebook messenger|fb messenger|telegram|instagram|insta)"
    private val flags = setOf(RegexOption.IGNORE_CASE)

    private val sendMessageToSaying = Regex(
        """(?:send|write)\s+(?:a\s+)?(?:message|msg|text)\s+to\s+(.+?)\s+(?:in|on|via|using|through)\s+$APPS\s+(?:saying|that says|that|with|:)\s*["“'‘]?(.+?)["”'’]?""",
        flags
    )
    private val sendMessageToSayingApp = Regex(
        """(?:send|write)\s+(?:a\s+)?(?:message|msg|text)\s+to\s+(.+?)\s+(?:saying|that says|:)\s*["“'‘]?(.+?)["”'’]?\s+(?:in|on|via|using|through)\s+$APPS""",
        flags
    )
    private val sendTo = Regex(
        """(?:please\s+)?(?:send|text|message|dm)\s+(?:a\s+)?(?:message\s+|msg\s+|text\s+)?(?:saying\s+|that\s+says\s+)?["“'‘]?(.+?)["”'’]?\s+to\s+(.+?)\s+(?:in|on|via|using|through)\s+$APPS""",
        flags
    )
    private val messageSaying = Regex(
        """(?:message|text|dm|msg)\s+(.+?)\s+(?:in|on|via|using|through)\s+$APPS\s+(?:saying|that|with|:)\s*["“'‘]?(.+?)["”'’]?""",
        flags
    )
    private val messageSayingFirst = Regex(
        """(?:message|text|dm|msg)\s+(.+?)\s+(?:saying|that says|:)\s*["“'‘]?(.+?)["”'’]?\s+(?:in|on|via|using|through)\s+$APPS""",
        flags
    )
    private val tellOn = Regex(
        """tell\s+(\S+)\s+(.+?)\s+(?:in|on|via|using|through)\s+$APPS""",
        flags
    )
    private val playOnYoutube = Regex(
        """(?:please\s+)?(?:play|watch|open)\s+(.+?)\s+(?:on|in|from)\s+youtube""",
        flags
    )
    private val playAnything = Regex("""(?:please\s+)?(?:play|watch)\s+(.+)""", flags)
    private val otherPlatforms = listOf(
        "spotify", "gaana", "wynk", "netflix", "hotstar", "prime video", "amazon", "instagram",
        "facebook", "tiktok", "play store", "playstore", "game", "ludo", "pubg", "free fire"
    )

    private fun appFor(token: String): MsgApp? {
        val t = token.lowercase().trim()
        return when {
            t.contains("whats") -> MsgApp("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"))
            t.contains("messenger") -> MsgApp("Messenger", listOf("com.facebook.orca"))
            t.contains("telegram") -> MsgApp("Telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web"))
            t.contains("insta") -> MsgApp("Instagram", listOf("com.instagram.android"))
            else -> null
        }
    }

    private fun clean(s: String): String = s.trim().trim('"', '“', '”', '\'', '‘', '’').trim()

    /** Returns a skill for goals it fully understands, otherwise null (the planner handles it). */
    fun match(goalRaw: String): QuickSkill? {
        val goal = goalRaw.trim()
        if (goal.contains('\n')) return null // follow-ups / corrected goals go to the planner

        // ---- messaging ----
        sendMessageToSaying.matchEntire(goal)?.let { m ->
            val app = appFor(m.groupValues[2]) ?: return null
            return MessageSkill(app, person(m.groupValues[1]), clean(m.groupValues[3]))
        }
        sendMessageToSayingApp.matchEntire(goal)?.let { m ->
            val app = appFor(m.groupValues[3]) ?: return null
            return MessageSkill(app, person(m.groupValues[1]), clean(m.groupValues[2]))
        }
        sendTo.matchEntire(goal)?.let { m ->
            val app = appFor(m.groupValues[3]) ?: return null
            return MessageSkill(app, person(m.groupValues[2]), clean(m.groupValues[1]))
        }
        messageSaying.matchEntire(goal)?.let { m ->
            val app = appFor(m.groupValues[2]) ?: return null
            return MessageSkill(app, person(m.groupValues[1]), clean(m.groupValues[3]))
        }
        messageSayingFirst.matchEntire(goal)?.let { m ->
            val app = appFor(m.groupValues[3]) ?: return null
            return MessageSkill(app, person(m.groupValues[1]), clean(m.groupValues[2]))
        }
        tellOn.matchEntire(goal)?.let { m ->
            val app = appFor(m.groupValues[3]) ?: return null
            return MessageSkill(app, person(m.groupValues[1]), clean(m.groupValues[2]))
        }

        // ---- YouTube ----
        playOnYoutube.matchEntire(goal)?.let { m -> return YouTubeSkill(clean(m.groupValues[1])) }
        playAnything.matchEntire(goal)?.let { m ->
            val lower = goal.lowercase()
            if (otherPlatforms.none { lower.contains(it) }) return YouTubeSkill(clean(m.groupValues[1]))
        }
        return null
    }

    private fun person(raw: String): String = clean(raw).removePrefix("my ").removePrefix("My ").trim()
}

data class MsgApp(val label: String, val packages: List<String>)

// =====================================================================================
// YouTube: search -> pick the right video -> open it -> verify the player is showing
// =====================================================================================
class YouTubeSkill(private val query: String) : QuickSkill {

    private val youtube = "com.google.android.youtube"

    private class Candidate(val node: UiNode, val title: String, val channel: String, val ageMinutes: Long)

    override fun run(env: SkillEnv): SkillOutcome {
        val tools = env.tools
        val wantsLatest = Regex("""\b(latest|newest|recent|new)\b""", RegexOption.IGNORE_CASE).containsMatchIn(query)

        env.status("Searching YouTube for \"$query\"")
        // The results page is opened directly through a link, so nothing has to be typed on the
        // keyboard and no Search key has to be found.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (env.context.packageManager.getLaunchIntentForPackage(youtube) != null) intent.setPackage(youtube)
        try {
            env.service.startActivity(intent)
        } catch (e: Exception) {
            return SkillOutcome.Failure("Couldn't open YouTube")
        }

        tools.waitFor(12_000) { if (tools.activePackage() == youtube) true else null }
            ?: return SkillOutcome.Failure("The YouTube app didn't open")

        env.status("Looking for the video")
        tools.waitFor(15_000, 500) { candidates(tools.snapshot()).takeIf { it.isNotEmpty() } }
            ?: return SkillOutcome.Failure("No video results appeared")
        tools.sleep(1200) // let more results load so \"latest\" can compare them

        val chosen = pick(candidates(tools.snapshot()), wantsLatest)
            ?: return SkillOutcome.Failure("Couldn't pick a video from the results")

        env.status("Opening: ${chosen.title.take(60)}")
        tools.click(chosen.node)
        var playing = tools.waitFor(9_000, 500) { if (isPlayerScreen(tools.snapshot())) true else null }
        if (playing == null) {
            // One retry on a fresh copy of the results.
            val retry = pick(candidates(tools.snapshot()), wantsLatest)
            if (retry != null) {
                tools.click(retry.node)
                playing = tools.waitFor(8_000, 500) { if (isPlayerScreen(tools.snapshot())) true else null }
            }
        }
        return if (playing != null) {
            SkillOutcome.Success("▶️ Playing \"${chosen.title}\" on YouTube.")
        } else {
            SkillOutcome.Failure("The video didn't open")
        }
    }

    private fun candidates(nodes: List<UiNode>): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (node in nodes) {
            val d = node.desc
            if (d.length < 25) continue
            val l = d.lowercase()
            val looksLikeVideo = l.contains("play video") || (l.contains(" views") && l.contains(" ago") && l.contains(" - "))
            if (!looksLikeVideo) continue
            if (l.contains("play short") || l.contains("shorts") || l.contains("sponsored") || l.startsWith("ad ")) continue
            val title = d.substringBefore(" - ").trim()
            val channel = Regex("""go to channel (.+?) - """, RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)?.trim().orEmpty()
            out.add(Candidate(node, title, channel, ageMinutes(d)))
        }
        return out.sortedBy { it.node.bounds.top }.distinctBy { it.title }
    }

    private fun ageMinutes(desc: String): Long {
        val m = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE).find(desc)
            ?: return Long.MAX_VALUE
        val n = m.groupValues[1].toLongOrNull() ?: return Long.MAX_VALUE
        return when (m.groupValues[2].lowercase()) {
            "second" -> 0L
            "minute" -> n
            "hour" -> n * 60
            "day" -> n * 1_440
            "week" -> n * 10_080
            "month" -> n * 43_200
            else -> n * 525_600
        }
    }

    private fun pick(list: List<Candidate>, wantsLatest: Boolean): Candidate? {
        if (list.isEmpty()) return null
        if (!wantsLatest) return list.first()
        // "latest video": prefer videos from the channel that was named, then the newest of those.
        val stop = setOf("latest", "newest", "recent", "new", "video", "videos", "song", "official", "full")
        val entity = UiTools.norm(query).split(" ").filter { it.isNotEmpty() && it !in stop }.joinToString("")
        val matching = if (entity.isEmpty()) emptyList() else list.filter {
            val channel = UiTools.norm(it.channel).replace(" ", "")
            channel.isNotEmpty() && (channel.contains(entity) || (channel.length >= 4 && entity.contains(channel)))
        }
        val pool = if (matching.isNotEmpty()) matching else list
        return pool.take(6).minByOrNull { it.ageMinutes }
    }

    private fun isPlayerScreen(nodes: List<UiNode>): Boolean {
        val markers = setOf(
            "minimize", "pause video", "play video", "enter fullscreen", "enter full screen",
            "video player", "expand mini player", "autoplay is on", "autoplay is off"
        )
        return nodes.any {
            val d = it.desc.lowercase()
            val t = it.text.lowercase()
            d in markers || t == "subscribe" || t == "subscribed" || d == "subscribe"
        }
    }
}

// =====================================================================================
// Messaging: open app -> search the person -> open the chat -> type -> Send -> verify
// =====================================================================================
class MessageSkill(
    private val app: MsgApp,
    private val person: String,
    private val message: String
) : QuickSkill {

    override fun run(env: SkillEnv): SkillOutcome {
        val tools = env.tools
        if (person.isBlank() || message.isBlank()) return SkillOutcome.Failure("I need both a name and a message")

        env.status("Opening ${app.label}")
        env.launchPackage(app.packages, freshStart = true)
            ?: return SkillOutcome.Failure("${app.label} isn't installed")
        tools.waitFor(14_000) { if (tools.activePackage() in app.packages) true else null }
            ?: return SkillOutcome.Failure("${app.label} didn't open")
        tools.sleep(1000)

        // ---- 1) open the search box ----
        env.status("Searching ${app.label} for $person")
        var field: UiNode? = null
        var attempts = 0
        while (field == null && attempts < 3 && !tools.cancelled) {
            attempts++
            val entry = tools.waitFor(9_000) { findSearchEntry(tools.snapshot()) }
            if (entry == null) {
                if (tools.cancelled) break
                env.service.goBack() // probably inside a chat or a sub-screen
                tools.sleep(800)
                continue
            }
            tools.click(entry)
            field = tools.waitFor(5_000) {
                val nodes = tools.snapshot()
                nodes.firstOrNull { it.editable && it.searchable.contains("search") } ?: nodes.firstOrNull { it.editable }
            }
        }
        if (field == null) return SkillOutcome.Failure("Couldn't find the search box in ${app.label}")

        // ---- 2) type the name and pick the matching result ----
        if (!tools.setText(field, person)) return SkillOutcome.Failure("Couldn't type the name into search")
        tools.sleep(1300)
        env.status("Finding $person")
        val fieldBottom = field.bounds.bottom
        val target = UiTools.norm(person)
        val result = tools.waitFor(10_000, 500) { pickResult(tools.snapshot(), fieldBottom, target) }
            ?: return SkillOutcome.Failure("\"$person\" didn't show up in ${app.label}")
        tools.click(result)

        // ---- 3) the chat's message box ----
        env.status("Opening the chat")
        val composer = tools.waitFor(11_000, 400) { findComposer(tools.snapshot(), env.screenHeight) }
            ?: return SkillOutcome.Failure("The chat with \"$person\" didn't open")
        tools.sleep(500)

        env.status("Typing the message")
        if (!tools.setText(composer, message)) return SkillOutcome.Failure("Couldn't type the message")
        tools.sleep(500)

        // ---- 4) press Send and check it really went ----
        env.status("Sending")
        val send = tools.waitFor(5_000, 300) { findSend(tools.snapshot()) }
            ?: return SkillOutcome.Failure("Couldn't find the Send button")
        tools.click(send)
        if (confirmSent(env, composer)) {
            return SkillOutcome.Success("✅ Sent \"$message\" to $person on ${app.label}.")
        }
        // Maybe the tap missed; try once more with a fresh Send button.
        val send2 = tools.waitFor(2_000, 300) { findSend(tools.snapshot()) }
        if (send2 != null) {
            tools.click(send2)
            if (confirmSent(env, composer)) {
                return SkillOutcome.Success("✅ Sent \"$message\" to $person on ${app.label}.")
            }
        }
        return SkillOutcome.Failure(
            "I pressed Send in ${app.label} but couldn't confirm it went through. Please check the chat with $person.",
            stopHere = true
        )
    }

    private fun findSearchEntry(nodes: List<UiNode>): UiNode? {
        nodes.firstOrNull { it.editable && it.searchable.contains("search") }?.let { return it }
        val buttons = nodes.filter { !it.editable && it.searchable.contains("search") }
        return buttons.firstOrNull { it.clickable } ?: buttons.firstOrNull()
    }

    /** Best matching search result row: exact name > starts with > contains, topmost first. */
    private fun pickResult(nodes: List<UiNode>, fieldBottom: Int, target: String): UiNode? {
        var best: UiNode? = null
        var bestScore = 0
        for (node in nodes) {
            if (node.editable || node.bounds.top < fieldBottom - 5) continue
            val label = UiTools.norm(node.label)
            if (label.isEmpty() || !label.contains(target)) continue
            if (label.startsWith("search for") || label.startsWith("see all")) continue
            val score = when {
                label == target -> 3
                label.startsWith(target) -> 2
                else -> 1
            }
            if (score > bestScore || (score == bestScore && best != null && node.bounds.top < best.bounds.top)) {
                best = node
                bestScore = score
            }
        }
        return best
    }

    private fun findComposer(nodes: List<UiNode>, screenHeight: Int): UiNode? {
        return nodes.firstOrNull {
            it.editable && !it.searchable.contains("search") &&
                (it.searchable.contains("message") || it.bounds.top > screenHeight * 0.55f)
        }
    }

    private fun findSend(nodes: List<UiNode>): UiNode? {
        val sendWords = setOf("send", "send message")
        val matches = nodes.filter {
            !it.editable && (UiTools.norm(it.desc) in sendWords || UiTools.norm(it.text) in sendWords)
        }
        return matches.firstOrNull { it.clickable } ?: matches.firstOrNull()
    }

    /** Sent = the message box emptied, or the text now appears as a message bubble. */
    private fun confirmSent(env: SkillEnv, composer: UiNode): Boolean {
        val tools = env.tools
        return tools.waitFor(4_000, 400) {
            if (tools.contentOf(composer).isBlank()) {
                true
            } else {
                val wanted = UiTools.norm(message)
                val bubble = tools.snapshot().any { !it.editable && UiTools.norm(it.text) == wanted }
                if (bubble) true else null
            }
        } != null
    }
}
