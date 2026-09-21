package com.simtether.bridge.net

import android.util.Log
import com.simtether.shared.crypto.SecureSession
import com.simtether.shared.protocol.Protocol
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * WS server on the bridge (hotspot host). First frame from a client is
 * Noise IK message 1 (token encrypted inside) → the bridge replies with
 * msg2 and both sides split into ChaCha20-Poly1305 transport ciphers.
 */
class BridgeWsServer(
    port: Int,
    private val staticKeyPair: Pair<ByteArray, ByteArray>,
    private val pairingToken: ByteArray,
    private val onClientReady: () -> Unit,
    private val onClientDisconnected: () -> Unit = {},
    private val onCommand: (Protocol.Envelope) -> Unit,
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

    // Wildcard bind is required to survive hotspot↔WiFi topology
    // changes without a rebind loop — but the socket must never serve
    // a non-LAN peer (cellular iface included). Gate at onOpen: only
    // private/link-local/loopback remotes proceed past this point;
    // the pairing token gates the session itself.
    private fun isLanPeer(addr: java.net.InetAddress): Boolean =
        addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            // Java only maps fec0::/10 to site-local; fc00::/7 ULA too.
            (addr.address.size == 16 && (addr.address[0].toInt() and 0xFE) == 0xFC)

    init {
        // A killed instance's socket can linger in TIME_WAIT and hold
        // the port — without reuse the restarted service can't bind.
        setReuseAddr(true)
    }

    @Volatile private var client: WebSocket? = null
    @Volatile private var session: SecureSession? = null

    val staticPubKey: ByteArray get() = staticKeyPair.second

    /** Client socket open AND encrypted session established. */
    fun isReady() = client?.isOpen == true && session != null

    fun send(env: Protocol.Envelope) {
        val s = session ?: run { Log.w(TAG, "send dropped: no session (${env.type})"); return }
        val c = client ?: run { Log.w(TAG, "send dropped: no client (${env.type})"); return }
        Log.d(TAG, "send ${env.type} seq=${env.seq}")
        c.send(s.encrypt(Protocol.encode(env).toByteArray()))
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val ip = conn.remoteSocketAddress?.address
        if (ip == null || !isLanPeer(ip)) {
            Log.w(TAG, "rejected non-LAN client: ${conn.remoteSocketAddress}")
            conn.close(4001, "lan only")
            return
        }
        Log.d(TAG, "client socket open: ${conn.remoteSocketAddress}")
        // Only one client makes sense — close any stale socket so its
        // session can't hijack sends or linger as a zombie.
        if (client != null && client != conn) {
            Log.d(TAG, "closing replaced socket ${client?.remoteSocketAddress}")
            client?.close(1000, "replaced")
        }
        client = conn
        session = null
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val bytes = ByteArray(message.remaining()).also { message.get(it) }
        val s = session
        if (s == null) {
            // First frame: Noise IK message 1 — the pairing token rides
            // inside as encrypted payload, so a mangled/wrong-key frame
            // fails AEAD before any session exists.
            if (bytes.size > 2048) { conn.close(1009, "oversize"); return }
            val hs = runCatching {
                SecureSession.bridgeHandshake(staticKeyPair.first, bytes)
            }.getOrElse {
                conn.close(1002, "bad handshake")
                return
            }
            if (!java.security.MessageDigest.isEqual(hs.peerPayload, pairingToken)) {
                Log.w(TAG, "rejected client: bad pairing token")
                conn.close(4003, "bad token")
                return
            }
            val (reply, sess) = hs.complete()
            conn.send(reply)
            session = sess
            Log.d(TAG, "session established")
            onClientReady()
        } else {
            val env = runCatching {
                Protocol.decode(s.decrypt(bytes).decodeToString())
            }.getOrElse {
                Log.e(TAG, "decrypt/decode failed (stale pairing?)", it)
                return
            }
            Log.d(TAG, "recv ${env.type} seq=${env.seq}")
            onCommand(env)
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // unused — binary frames only
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "client closed code=$code reason=$reason remote=$remote")
        if (conn == client) {
            client = null
            session = null
            onClientDisconnected()
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "ws error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "server started on $address")
    }

    private companion object {
        const val TAG = "SimTether.Server"
    }
}
