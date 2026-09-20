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
 * its ephemeral X25519 pubkey → completes the session handshake; all
 * subsequent frames are ChaCha20-Poly1305 envelopes.
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
    private var session: SecureSession? = null

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
            // First frame: client ephemeral pubkey (32B) + pairing
            // token (16B), plaintext. The token proves the QR scan —
            // without it any LAN device could occupy the client slot.
            if (bytes.size != 48) { conn.close(1002, "bad handshake"); return }
            val ephPub = bytes.copyOfRange(0, 32)
            val token = bytes.copyOfRange(32, 48)
            if (!java.security.MessageDigest.isEqual(token, pairingToken)) {
                Log.w(TAG, "rejected client: bad pairing token")
                conn.close(4003, "bad token")
                return
            }
            session = SecureSession.bridgeHandshake(staticKeyPair.first, ephPub)
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
