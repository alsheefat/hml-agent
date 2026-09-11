package com.hmlai.agent

import android.Manifest
import android.content.Context
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
}
