package com.simtether.bridge.net

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

/**
 * Outbound link to a splice relay — the remote-access half of the
 * bridge. Dials OUT (no port-forward, survives CGNAT) and registers
 * the room named by our key fingerprint. When a remote client joins
 * the room, the relay splices its socket onto ours and inbound frames
 * feed the same session pipeline as a LAN client.
 *
 * The registration socket idles (no frames) until a client actually
 * splices in — BridgeWsServer adopts it lazily on the first frame.
 */
class RelayLink(
    private val server: BridgeWsServer,
    private val addr: String,          // "host:port"
    private val fingerprint: String,   // st1-xxxx — the room name
    private val token: String?,
) {
    @Volatile private var stopped = false
    @Volatile private var socket: WebSocketClient? = null
    private var backoffMs = 2_000L
    private val thread = Thread({ loop() }, "relay-link").also { it.isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
        thread.interrupt()
    }

    private fun loop() {
        while (!stopped) {
            val q = token
                ?.let { "?token=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
            val c = object : WebSocketClient(URI("ws://$addr/register/$fingerprint$q")) {
                override fun onOpen(h: ServerHandshake) {
                    Log.d(TAG, "registered room $fingerprint on relay $addr")
                    backoffMs = 2_000L
                }

                override fun onMessage(message: String) {
                    // Relay control frame — the spliced client left.
                    // Clear the session but keep this registration.
                    if (message == "st-peer-gone") server.handleRemoteClose(this)
                }

                override fun onMessage(bytes: ByteBuffer) {
                    server.handleRemoteFrame(this, bytes)
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    Log.d(TAG, "relay socket closed code=$code reason=$reason")
                    server.handleRemoteClose(this)
                }

                override fun onError(ex: Exception) {
                    Log.w(TAG, "relay socket error: ${ex.message}")
                }
            }
            // Ping the relay when idle — keeps NAT conntrack entries
            // warm and reaps half-dead sockets.
            c.connectionLostTimeout = 60
            socket = c
            runCatching { c.connectBlocking() }
            if (stopped) break
            Thread.sleep(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        socket = null
    }

    private companion object {
        const val TAG = "SimTether.Relay"
    }
}
