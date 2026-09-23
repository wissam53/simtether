package com.simtether.bridge.sms

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Confirms SMS sent outside our SmsSender pipeline — e.g. Telecom's
 * canned reply on reject-with-message. `call.reject(true, text)`
 * returns before the radio submit, so the honest signal is the row
 * the system writes into the sent box: row within [CONFIRM_MS] → sent;
 * no row → unconfirmed (we can't distinguish failed vs not-written).
 */
object SentBoxWatcher {
    private const val TAG = "SimTether.SentBox"
    private const val CONFIRM_MS = 20_000L
    private const val DATE_SLOP_MS = 3_000L

    private data class Pending(
        val address: String,
        val body: String,
        val ref: String,
        val since: Long,
        val onDone: (sent: Boolean) -> Unit,
    )

    private val pending = CopyOnWriteArrayList<Pending>()
    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var observer: ContentObserver? = null

    /** Call once from the service. Idempotent. */
    fun start(context: Context) {
        if (observer != null) return
        app = context.applicationContext
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = sweep()
        }.also {
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI, true, it)
        }
    }

    /** Expect a system-sent SMS matching (address, body); resolves via
     *  [onDone] when the sent-box row appears or the window expires. */
    fun expect(address: String, body: String, ref: String,
               onDone: (sent: Boolean) -> Unit) {
        pending.add(Pending(address, body, ref,
            System.currentTimeMillis(), onDone))
        handler.postDelayed({ expire(ref) }, CONFIRM_MS)
        sweep()   // the row may already be there on a fast submit
    }

    private fun expire(ref: String) {
        pending.firstOrNull { it.ref == ref }?.let {
            pending.remove(it)
            Log.w(TAG, "no sent row for $ref — marking unconfirmed")
            it.onDone(false)
        }
    }

    private fun sweep() {
        val ctx = app ?: return
        if (pending.isEmpty()) return
        val rows = runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.Sent.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE),
                null, null, "date DESC"
            )?.use { c ->
                buildList {
                    while (c.moveToNext() && size < 10)
                        add(Triple(c.getString(0) ?: "",
                            c.getString(1) ?: "", c.getLong(2)))
                }
            }
        }.getOrNull() ?: return
        for (p in pending) {
            val hit = rows.any { (addr, body, date) ->
                date >= p.since - DATE_SLOP_MS &&
                    body == p.body &&
                    PhoneNumberUtils.compare(addr, p.address)
            }
            if (hit) {
                pending.remove(p)
                Log.d(TAG, "sent row confirmed ref=${p.ref}")
                p.onDone(true)
            }
        }
    }
}
