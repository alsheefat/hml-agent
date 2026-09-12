package com.hmlai.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/**
 * Result of trying to parse+run a message as a local device command.
 * handled=true means the message was a device command (whether it
 * succeeded or failed) and should NOT be sent to the AI server.
 * handled=false means it's a normal chat message.
 */
data class CommandResult(
    val handled: Boolean,
    val responseText: String = ""
)

object DeviceCommandHandler {

    private var flashlightOn = false

    fun tryHandle(context: Context, rawText: String): CommandResult {
        val text = rawText.trim().lowercase()

        // --- Flashlight ---
        if (isFlashOnCommand(text)) {
            return CommandResult(true, setFlashlight(context, true))
        }
        if (isFlashOffCommand(text)) {
            return CommandResult(true, setFlashlight(context, false))
        }

        // --- Phone call ---
        val callTarget = extractCallTarget(text)
        if (callTarget != null) {
            return CommandResult(true, placeCall(context, callTarget))
        }

        // --- Open a website (checked before generic app-open since
        // "open youtube.com" should open a URL, not search for an app
        // literally named "youtube.com") ---
        val websiteQuery = extractWebsiteTarget(text)
        if (websiteQuery != null) {
            return CommandResult(true, openWebsite(context, websiteQuery))
        }

        // --- Open an app by name ---
        val appToOpen = extractOpenAppTarget(text)
        if (appToOpen != null) {
            return CommandResult(true, openApp(context, appToOpen))
        }

        // --- Play / search on YouTube ---
        val youtubeQuery = extractYoutubeQuery(text)
        if (youtubeQuery != null) {
            return CommandResult(true, openYoutubeSearch(context, youtubeQuery))
        }

        // --- Play music (Spotify if installed, else YouTube Music) ---
        val musicQuery = extractMusicQuery(text)
        if (musicQuery != null) {
            return CommandResult(true, playMusic(context, musicQuery))
        }

        // --- Send a text message ---
        val smsTarget = extractSmsTarget(text)
        if (smsTarget != null) {
            return CommandResult(true, openSms(context, smsTarget.first, smsTarget.second))
        }

        // --- Web search (Google) ---
        val searchQuery = extractGoogleSearchQuery(text)
        if (searchQuery != null) {
            return CommandResult(true, openGoogleSearch(context, searchQuery))
        }

        return CommandResult(false)
    }

    // ============================================================
    // FLASHLIGHT
    // ============================================================

    private fun isFlashOnCommand(text: String): Boolean {
        val patterns = listOf(
            "turn on my flash", "turn on flash", "flash on", "flashlight on",
            "turn on the flashlight", "flash chalu koro", "flash on koro"
        )
        return patterns.any { text.contains(it) }
    }

    private fun isFlashOffCommand(text: String): Boolean {
        val patterns = listOf(
            "turn off my flash", "turn off flash", "flash off", "flashlight off",
            "turn off the flashlight", "flash bondho koro", "flash off koro"
        )
        return patterns.any { text.contains(it) }
    }

    private fun setFlashlight(context: Context, turnOn: Boolean): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "I need Camera permission to control the flashlight. Please grant it in app settings."
        }

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }

            if (cameraId == null) {
                "This device doesn't seem to have a flashlight."
            } else {
                cameraManager.setTorchMode(cameraId, turnOn)
                flashlightOn = turnOn
                if (turnOn) "🔦 Flashlight turned on." else "🔦 Flashlight turned off."
            }
        } catch (e: Exception) {
            "Couldn't control the flashlight: ${e.message}"
        }
    }

    // ============================================================
    // PHONE CALL
    // ============================================================

    /** Returns the contact name to call if this message is a call command,
     * or null if it isn't one. */
    private fun extractCallTarget(text: String): String? {
        val patterns = listOf(
            Regex("^call\\s+(.+)$"),
            Regex("^phone\\s+(.+)$"),
            Regex("^dial\\s+(.+)$"),
            Regex("^(.+)\\s+ke call koro$"),
            Regex("^(.+)\\s+k call koro$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }

    private fun placeCall(context: Context, contactName: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "I need Contacts permission to find \"$contactName\". Please grant it in app settings."
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "I need Phone Call permission to call \"$contactName\". Please grant it in app settings."
        }

        val phoneNumber = findPhoneNumberForContact(context, contactName)
            ?: return "I couldn't find a contact named \"$contactName\" on this phone."

        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val uri = Uri.fromParts("tel", phoneNumber, null)
            @Suppress("MissingPermission")
            telecomManager.placeCall(uri, null)
            "📞 Calling $contactName ($phoneNumber)..."
        } catch (e: Exception) {
            "Couldn't place the call: ${e.message}"
        }
    }

    private fun findPhoneNumberForContact(context: Context, name: String): String? {
        val resolver = context.contentResolver
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )

            if (cursor != null && cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                return cursor.getString(numberIndex)
            }
        } catch (e: Exception) {
            // fall through to return null
        } finally {
            cursor?.close()
        }
        return null
    }

    // ============================================================
    // OPEN APP BY NAME
    // ============================================================

    private fun extractOpenAppTarget(text: String): String? {
        val patterns = listOf(
            Regex("^open\\s+(.+)$"),
            Regex("^launch\\s+(.+)$"),
            Regex("^start\\s+(.+)\\s+app$"),
            Regex("^(.+)\\s+kholo$"),
            Regex("^(.+)\\s+chalu koro$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                // Avoid swallowing other command types that also start with
                // "open" (e.g. "open a search for X", handled elsewhere).
                if (candidate.isNotEmpty() && !candidate.startsWith("search")
                    && !candidate.startsWith("a search")
                ) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun openApp(context: Context, appName: String): String {
        val packageManager = context.packageManager
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

        val match = installedApps.firstOrNull { appInfo ->
            val label = packageManager.getApplicationLabel(appInfo).toString().lowercase()
            label.contains(appName) || appName.contains(label)
        }

        if (match == null) {
            return "I couldn't find an app called \"$appName\" on this phone."
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(match.packageName)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            val label = packageManager.getApplicationLabel(match).toString()
            "📱 Opening $label..."
        } else {
            "Found \"$appName\" but couldn't open it."
        }
    }

    // ============================================================
    // YOUTUBE
    // ============================================================

    private fun extractYoutubeQuery(text: String): String? {
        val patterns = listOf(
            Regex("^play\\s+(.+)\\s+on youtube$"),
            Regex("^search\\s+(.+)\\s+on youtube$"),
            Regex("^youtube\\s+(.+)$"),
            Regex("^(.+)\\s+youtube e cholao$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.groupValues[1].trim()
        }
        return null
    }

    private fun openYoutubeSearch(context: Context, query: String): String {
        return try {
            val encoded = Uri.encode(query)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:search?query=$encoded"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // YouTube app not installed — fall back to browser.
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=$encoded")
                )
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(webIntent)
            }
            "▶️ Searching YouTube for \"$query\"..."
        } catch (e: Exception) {
            "Couldn't open YouTube: ${e.message}"
        }
    }

    // ============================================================
    // MUSIC (Spotify if installed, else YouTube Music, else YouTube)
    // ============================================================

    private fun extractMusicQuery(text: String): String? {
        val patterns = listOf(
            Regex("^play\\s+(.+)\\s+song$"),
            Regex("^play music\\s+(.+)$"),
            Regex("^play\\s+(.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                if (candidate.isNotEmpty() && !candidate.endsWith("on youtube")) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun playMusic(context: Context, query: String): String {
        val packageManager = context.packageManager
        val encoded = Uri.encode(query)

        // Try Spotify first.
        val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded"))
        if (spotifyIntent.resolveActivity(packageManager) != null) {
            spotifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(spotifyIntent)
            return "🎵 Searching Spotify for \"$query\"..."
        }

        // Fall back to YouTube Music if installed.
        val ytMusicIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded"))
        ytMusicIntent.setPackage("com.google.android.apps.youtube.music")
        if (ytMusicIntent.resolveActivity(packageManager) != null) {
            ytMusicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(ytMusicIntent)
            return "🎵 Searching YouTube Music for \"$query\"..."
        }

        // Last resort: plain YouTube search.
        return openYoutubeSearch(context, query)
    }

    // ============================================================
    // SEND SMS
    // ============================================================

    private fun extractSmsTarget(text: String): Pair<String, String>? {
        val patterns = listOf(
            Regex("^text\\s+(\\w+)\\s+saying\\s+(.+)$"),
            Regex("^text\\s+(\\w+)\\s+(.+)$"),
            Regex("^message\\s+(\\w+)\\s+saying\\s+(.+)$"),
            Regex("^send\\s+(\\w+)\\s+a message\\s+(.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return Pair(match.groupValues[1].trim(), match.groupValues[2].trim())
            }
        }
        return null
    }

    private fun openSms(context: Context, contactName: String, message: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "I need SMS permission to text \"$contactName\". Please grant it in app settings."
        }

        val phoneNumber = findPhoneNumberForContact(context, contactName)
            ?: return "I couldn't find a contact named \"$contactName\" to text."

        return try {
            val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            "💬 Texted $contactName: \"$message\""
        } catch (e: Exception) {
            "Couldn't send the text: ${e.message}"
        }
    }

    // ============================================================
    // OPEN WEBSITE
    // ============================================================

    private fun extractWebsiteTarget(text: String): String? {
        val patterns = listOf(
            Regex("^open\\s+website\\s+(.+)$"),
            Regex("^go to\\s+(.+\\.\\w{2,})$"),
            Regex("^open\\s+(\\S+\\.\\w{2,})$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.groupValues[1].trim()
        }
        return null
    }

    private fun openWebsite(context: Context, site: String): String {
        return try {
            var url = site
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "🌐 Opening $site..."
        } catch (e: Exception) {
            "Couldn't open that website: ${e.message}"
        }
    }

    // ============================================================
    // GOOGLE SEARCH
    // ============================================================

    private fun extractGoogleSearchQuery(text: String): String? {
        val patterns = listOf(
            Regex("^search\\s+(.+)\\s+on google$"),
            Regex("^google\\s+(.+)$"),
            Regex("^search for\\s+(.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.groupValues[1].trim()
        }
        return null
    }

    private fun openGoogleSearch(context: Context, query: String): String {
        return try {
            val encoded = Uri.encode(query)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "🔍 Searching Google for \"$query\"..."
        } catch (e: Exception) {
            "Couldn't search: ${e.message}"
        }
    }
}
