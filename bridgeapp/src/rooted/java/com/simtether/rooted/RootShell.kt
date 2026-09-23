package com.simtether.rooted

import android.util.Log

/**
 * Minimal `su` process runner — rooted flavor only. Every privileged
 * poke goes through here so there's exactly one place that logs and
 * bounds execution.
 */
object RootShell {
    private const val TAG = "SimTether.Root"

    /**
     * Cheap root probe: `su -c id` returning uid=0 means root is both
     * present and already granted (Magisk auto-grants once approved;
     * the first call may show the grant dialog).
     */
    fun probe(timeoutMs: Long = 15_000): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly(); return false
        }
        p.exitValue() == 0 && out.contains("uid=0")
    }.getOrElse {
        Log.w(TAG, "su probe failed", it)
        false
    }

    /** Run [cmd] under `su -c`; returns stdout, or null on failure/timeout. */
    fun run(cmd: String, timeoutMs: Long = 5_000): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "timeout: $cmd")
            return null
        }
        if (p.exitValue() != 0) {
            Log.w(TAG, "exit ${p.exitValue()}: $cmd — $err")
            return null
        }
        out.trim().ifEmpty { err.trim() }
    }.getOrElse {
        Log.w(TAG, "exec failed: $cmd", it)
        null
    }

    /** Fire-and-forget poke (mixer controls — output is noise). */
    fun poke(cmd: String) {
        Thread({
            run(cmd)
        }, "su-poke").also { it.isDaemon = true }.start()
    }

    /** First absolute path that exists (for tinymix/tinyplay discovery). */
    fun which(vararg names: String): String? {
        for (n in names) {
            val hit = run(
                "command -v $n || ls /system/bin/$n /vendor/bin/$n /system/xbin/$n 2>/dev/null")
                ?.lineSequence()?.firstOrNull { it.trim().startsWith("/") }
            if (hit != null) return hit.trim()
        }
        return null
    }
}
