package dev.jiadroid.follow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Look up a contact number and open the phone dialer / place a call. */
object ContactDialer {
    fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasCallPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Search the phone book by display name. Tries the spoken label and common
     * relationship aliases (Mom / Mother / Mum, …).
     */
    fun findInContacts(context: Context, who: String): String? {
        if (!hasContactsPermission(context)) return null
        val candidates = contactNameCandidates(who)
        for (name in candidates) {
            findExactOrPrefix(context, name)?.let { return it }
        }
        for (name in candidates) {
            findContains(context, name)?.let { return it }
        }
        return null
    }

    /**
     * Place the call when CALL_PHONE is granted; otherwise open the dialer with
     * the number filled in so the user can confirm.
     */
    fun placeCall(context: Context, number: String): Boolean {
        val tel = normalizePhoneNumber(number) ?: return false
        val uri = Uri.parse("tel:$tel")
        return try {
            if (hasCallPermission(context)) {
                context.startActivity(
                    Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun contactNameCandidates(who: String): List<String> {
        val id = canonicalContactId(who)
        val label = canonicalContactLabel(who)
        val aliases = CONTACT_NAME_VARIANTS[id].orEmpty()
        return (listOf(label, who.trim().replaceFirstChar { it.titlecase() }) + aliases)
            .distinctBy { it.lowercase() }
            .filter { it.isNotBlank() }
    }

    private fun findExactOrPrefix(context: Context, name: String): String? {
        val resolver = context.contentResolver
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf(name),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        ).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val number = cursor.getString(1)
                return normalizePhoneNumber(number.orEmpty())
            }
        }
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("$name%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        ).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val number = cursor.getString(1)
                return normalizePhoneNumber(number.orEmpty())
            }
        }
        return null
    }

    private fun findContains(context: Context, name: String): String? {
        if (name.length < 2) return null
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        ).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val number = cursor.getString(1)
                return normalizePhoneNumber(number.orEmpty())
            }
        }
        return null
    }

    private val CONTACT_NAME_VARIANTS = mapOf(
        "mom" to listOf("Mom", "Mum", "Mama", "Mommy", "Mother"),
        "dad" to listOf("Dad", "Daddy", "Papa", "Father", "Pa", "Pop"),
        "grandma" to listOf("Grandma", "Grandmother", "Nana", "Granny", "Gran"),
        "grandpa" to listOf("Grandpa", "Grandfather", "Granddad", "Grandad", "Gramps"),
        "sister" to listOf("Sister", "Sis"),
        "brother" to listOf("Brother", "Bro"),
    )
}
