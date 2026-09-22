package com.simtether.relay

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * SimTether splice relay — the rendezvous for remote access.
 *
 * Both phones dial OUT to here (no port-forwarding, survives CGNAT):
 *
 *   bridge → ws://host:port/register/{fingerprint}
 *   client → ws://host:port/connect/{fingerprint}
 *
 * and the two sockets are spliced byte-for-byte. The Noise IK session
 * runs end-to-end THROUGH the splice — this server sees ciphertext
 * only. Keep it that way: no inspection, no frame parsing, no logs
 * beyond connection lifecycle.
 *
 * Env:
 *   PORT         listen port (default 44711)
 *   ACCESS_TOKEN if set, both endpoints must present it as ?token= —
 *                turns the relay into a private pipe for your own
 *                devices (BYO/personal deployments).
 *
 * Client detach is signalled to the bridge as a text frame
 * "st-peer-gone" — the bridge clears the dead session but keeps its
 * registration socket (and the room) alive for the next client.
 */
class RelayServer(
    port: Int,
    private val accessToken: String?,
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

    private class Room {
        @Volatile var bridge: WebSocket? = null
        @Volatile var client: WebSocket? = null
    }

    private val rooms = ConcurrentHashMap<String, Room>()
    private val roles = ConcurrentHashMap<WebSocket, Pair<String, String>>() // conn → (fp, role)

    override fun onOpen(conn: WebSocket, hs: ClientHandshake) {
        val desc = conn.resourceDescriptor ?: ""
        val path = desc.substringBefore('?')
        val query = desc.substringAfter('?', "")
        if (accessToken != null) {
            val tok = query.split('&').firstNotNullOfOrNull {
                it.substringBefore('=').takeIf { k -> k == "token" }
                    ?.let { _ -> it.substringAfter('=') }
            } ?: ""
            if (tok != accessToken) {
                conn.close(4001, "auth")
                return
            }
        }
        val seg = path.trim('/').split('/')
        if (seg.size != 2 || seg[1].isBlank()) {
            conn.close(1008, "bad path")
            return
        }
        val (role, fp) = seg
        when (role) {
            "register" -> {
                val room = rooms.getOrPut(fp) { Room() }
                // One bridge per room — a re-registration replaces the
                // stale socket (and drops any client spliced to it).
                room.bridge?.takeIf { it != conn }?.close(1000, "replaced")
                room.client?.close(1000, "bridge replaced")
                room.client = null
                room.bridge = conn
                roles[conn] = fp to role
                println("room $fp: bridge registered")
            }
            "connect" -> {
                val room = rooms[fp]
                val b = room?.bridge
                if (b == null || !b.isOpen) {
                    conn.close(4004, "no bridge")
                    return
                }
                room.client?.close(1000, "replaced")
                room.client = conn
                conn.setAttachment(b)
                b.setAttachment(conn)
                roles[conn] = fp to role
                println("room $fp: client spliced")
            }
            else -> conn.close(1008, "bad path")
        }
    }

    override fun onMessage(conn: WebSocket, bytes: ByteBuffer) {
        conn.getAttachment<WebSocket>()?.send(bytes)
    }

    // Text frames are control, not payload — the app protocol is
    // binary-only, so nothing user-originated ever reaches this path.
    override fun onMessage(conn: WebSocket, text: String) {
        conn.getAttachment<WebSocket>()?.send(text)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val peer = conn.getAttachment<WebSocket>()
        conn.setAttachment<WebSocket?>(null)
        val r = roles.remove(conn)
        if (r == null) {
            // Unspliced or unregistered socket — nothing to clean up.
            peer?.setAttachment<WebSocket?>(null)
            return
        }
        val (fp, role) = r
        if (role == "register") {
            val room = rooms[fp]
            if (room?.bridge == conn) {
                rooms.remove(fp, room)
                peer?.setAttachment<WebSocket?>(null)
                peer?.close(1000, "bridge gone")
                println("room $fp: bridge gone — closed")
            }
        } else {
            // Client left: detach but keep the room — the bridge's
            // registration socket stays live for the next client.
            peer?.setAttachment<WebSocket?>(null)
            peer?.send("st-peer-gone")
            rooms[fp]?.let { if (it.client == conn) it.client = null }
            println("room $fp: client detached")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        System.err.println("relay error: ${ex.message}")
    }

    override fun onStart() {
        println("simtether relay listening on $address")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val port = System.getenv("PORT")?.toIntOrNull() ?: 44711
            val token = System.getenv("ACCESS_TOKEN")?.takeIf { it.isNotBlank() }
            RelayServer(port, token).apply {
                // WS ping/liveness — reaps half-dead sockets so rooms
                // don't stay registered to ghosts.
                connectionLostTimeout = 60
            }.start()
        }
    }
}
