package com.simtether.shared

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Number → contact name resolution via ContactsContract.PhoneLookup.
 * Cached in memory; [names] is observable so the UI recomposes when a
 * lookup completes. Requires READ_CONTACTS on the client.
 */
object ContactLookup {
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Returns cached name or null; kicks off a background lookup. */
    fun nameFor(number: String): String? {
        _names.value[number]?.let { return it }
        val ctx = appContext ?: return null
        Thread {
            resolve(ctx, number)?.let { name ->
                _names.value = _names.value + (number to name)
            }
        }.start()
        return null
    }

    /** Cache read only — safe on any thread, never queries. */
    fun cachedName(number: String): String? = _names.value[number]

    /**
     * Async resolve with a callback (worker thread) — for call paths
     * that must not block the main thread on a ContentProvider query.
     */
    fun resolveAsync(number: String, onDone: (String?) -> Unit) {
        _names.value[number]?.let { onDone(it); return }
        val ctx = appContext ?: run { onDone(null); return }
        Thread {
            onDone(resolve(ctx, number)?.also { name ->
                _names.value = _names.value + (number to name)
            })
        }.start()
    }

    /** Blocking variant for call-time resolution — query is indexed.
     *  Worker threads only, never the UI thread. */
    fun resolveBlocking(number: String): String? {
        _names.value[number]?.let { return it }
        val ctx = appContext ?: return null
        return resolve(ctx, number)?.also { name ->
            _names.value = _names.value + (number to name)
        }
    }

    private fun resolve(context: Context, number: String): String? = runCatching {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        context.contentResolver.query(
            uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.onFailure { Log.w("SimTether.Contacts", "lookup failed", it) }.getOrNull()

    data class Contact(val name: String, val number: String)

    /** All contacts with phone numbers — for the Contacts tab. */
    fun all(context: Context): List<Contact> = runCatching {
        val out = LinkedHashMap<String, Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                out.putIfAbsent("$name|$number", Contact(name, number))
            }
        }
        out.values.toList()
    }.getOrDefault(emptyList())
}
