package com.simtether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simtether.shared.CallLogEntry
import com.simtether.shared.CallLogStore
import com.simtether.shared.ChatMessage
import com.simtether.shared.ConversationStore
import com.simtether.shared.protocol.Protocol

/**
 * Demo/screenshot data — debug builds only (this receiver lives in
 * src/debug so it never ships). Loads synthetic SMS + call entries
 * into the in-memory stores; the encrypted files are never read or
 * written, so a force-stop restores real data.
 *
 * adb shell am broadcast -a com.simtether.SEED_DEMO
 * adb shell am broadcast -a com.simtether.CLEAR_DEMO
 */
class DemoSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SEED -> seed()
            ACTION_CLEAR -> android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun seed() {
        android.util.Log.i("DemoSeed", "seeding demo data")
        val now = System.currentTimeMillis()
        ConversationStore.seedDemo(listOf(
            ChatMessage(
                address = "+90 532 000 00 00",
                body = "Your verification code is 481-592. It expires in 10 minutes.",
                timestamp = now - 4 * 60_000,
                outgoing = false, read = false),
            ChatMessage(
                address = "TURKCELL",
                body = "Your package was delivered to the pickup point.",
                timestamp = now - 2 * 3600_000,
                outgoing = false, read = true),
            ChatMessage(
                address = "+44 7700 900123",
                body = "Reminder: dentist appointment tomorrow at 14:30.",
                timestamp = now - 26 * 3600_000,
                outgoing = false, read = true),
            ChatMessage(
                address = "+44 7700 900123",
                body = "Thanks, see you then!",
                timestamp = now - 26 * 3600_000 + 120_000,
                outgoing = true, status = "delivered", read = true),
        ))
        CallLogStore.seedDemo(listOf(
            CallLogEntry("demo-1", "+90 532 000 00 00", "Mom",
                Protocol.CallEvent.State.DISCONNECTED, now - 45 * 60_000,
                incoming = true, answered = false, seen = false),
            CallLogEntry("demo-2", "+44 7700 900123", "Dental Clinic",
                Protocol.CallEvent.State.DISCONNECTED, now - 3 * 3600_000,
                incoming = true, answered = true, seen = true),
            CallLogEntry("demo-3", "+90 212 555 34 21", "Office",
                Protocol.CallEvent.State.DISCONNECTED, now - 30 * 3600_000,
                incoming = false, answered = true, seen = true),
        ))
    }

    companion object {
        const val ACTION_SEED = "com.simtether.SEED_DEMO"
        const val ACTION_CLEAR = "com.simtether.CLEAR_DEMO"
    }
}
