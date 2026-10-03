package com.hmlai.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ResolvedContact(val name: String, val phoneNumber: String)

sealed class ContactResolution {
    data class Found(val contact: ResolvedContact) : ContactResolution()
    data class Ambiguous(val query: String, val matches: List<ResolvedContact>) : ContactResolution()
    data class NotFound(val searchedName: String) : ContactResolution()
    object PermissionDenied : ContactResolution()
}

/**
 * Resolves a spoken/typed contact reference ("Abbu", "dad", "MD Rafiq")
 * to an actual phone number, understanding relationship words in both
 * English and Bangla — not just an exact/partial name match.
 */
object ContactResolver {

    // Relationship-word aliases: saying any word in a group should find
    // the same saved contact, regardless of which language/word the
    // contact itself is saved under. Bangla and Banglish included since
    // that's how this app's users actually refer to family.
    private val relationshipAliases: Map<String, List<String>> = mapOf(
        "dad" to listOf("dad", "father", "papa", "baba", "abbu", "abba", "daddy"),
        "mom" to listOf("mom", "mother", "mama", "maa", "amma", "ammu", "mummy", "ma"),
        "wife" to listOf("wife", "biwi", "bou"),
        "husband" to listOf("husband", "husband", "hubby"),
        "brother" to listOf("brother", "bro", "bhai", "bhaiya"),
        "sister" to listOf("sister", "sis", "apu", "didi", "behen"),
        "boss" to listOf("boss", "manager", "sir", "madam"),
        "office" to listOf("office", "work", "workplace")
    )

    fun resolve(context: Context, query: String): ContactResolution {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return ContactResolution.NotFound(query)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ContactResolution.PermissionDenied
        }

        // Exact name match first.
        val exactMatches = searchContacts(context, cleaned, exact = true)
        if (exactMatches.size == 1) return ContactResolution.Found(exactMatches[0])
        if (exactMatches.size > 1) return ContactResolution.Ambiguous(cleaned, exactMatches)

        // Partial (contains) match.
        val partialMatches = searchContacts(context, cleaned, exact = false)
        if (partialMatches.size == 1) return ContactResolution.Found(partialMatches[0])
        if (partialMatches.size > 1) return ContactResolution.Ambiguous(cleaned, partialMatches)

        // Relationship-word fallback ("Abbu" -> try "dad", "papa", "baba"...).
        val lower = cleaned.lowercase()
        val aliasGroup = relationshipAliases.entries
            .firstOrNull { (key, aliases) -> key == lower || aliases.any { it == lower } }
            ?.value
        if (aliasGroup != null) {
            for (term in aliasGroup) {
                val matches = searchContacts(context, term, exact = false)
                if (matches.size == 1) return ContactResolution.Found(matches[0])
                if (matches.size > 1) return ContactResolution.Ambiguous(cleaned, matches)
            }
        }

        return ContactResolution.NotFound(cleaned)
    }

    /** Convenience wrapper for callers that just want a single phone
     * number and are fine treating "ambiguous" as "not found" (they'll
     * report a generic failure rather than offering a picker). Existing
     * call sites use this; new UI can call resolve() directly for a
     * proper disambiguation prompt. */
    fun findPhoneNumberForContact(context: Context, name: String): String? {
        return when (val result = resolve(context, name)) {
            is ContactResolution.Found -> result.contact.phoneNumber
            else -> null
        }
    }

    private fun searchContacts(context: Context, name: String, exact: Boolean): List<ResolvedContact> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = if (exact) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        } else {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        }
        val selectionArgs = arrayOf(if (exact) name else "%$name%")

        val results = mutableListOf<ResolvedContact>()
        val seenNumbers = mutableSetOf<String>()

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIdx >= 0 && numIdx >= 0) {
                    while (cursor.moveToNext()) {
                        val displayName = cursor.getString(nameIdx) ?: continue
                        val number = cursor.getString(numIdx) ?: continue
                        // Same contact can have multiple numbers (mobile/home/work);
                        // only keep the first one per unique number to avoid
                        // "ambiguous" firing just because someone has 2 numbers
                        // saved under the same name.
                        if (seenNumbers.add(number)) {
                            results.add(ResolvedContact(displayName, number))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Treat as no results — caller falls through to the next tier.
        }

        // De-duplicate by display name too (a contact with 2 numbers
        // shouldn't count as 2 different "matches" for ambiguity purposes
        // — only genuinely different people should).
        return results.distinctBy { it.name.lowercase() }
    }
}
