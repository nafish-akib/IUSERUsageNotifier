package com.example.iuserusagenotifier

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.zip.CRC32

/**
 * Driver for TP-Link Deco routers (TPM5 firmware) — TMP protocol over SSH.
 *
 * Ported from the Python driver in router_automation/tplink_deco.py, which was
 * reverse-engineered from the Deco app (com.tplink.tpm5 3.10.489). The Deco
 * has no CGI admin panel; the app talks to it over SSH:
 *   1. SSH to the router port 22, username = TP-Link ID e-mail, password = the
 *      TP-Link ID password, plaintext password auth.
 *   2. A local SSH port-forward to 127.0.0.1:20002 on the router (the app does
 *      the same with JSch setPortForwardingL).
 *   3. TMP v1 frames: 16-byte header (magic 0x01, version 0x01, type 5 =
 *      request/response, 16-bit BE length, 32-bit BE sequence, CRC32 of the
 *      whole frame computed with appVersion 1516993677 in the CRC field).
 *   4. App v2 payload: [0x01][0x02] + 18-byte header (opcode BE, packet type,
 *      error byte, txid BE, crc32(data) BE, total length BE, offset BE) + data.
 *   5. Session ops: 1 = allocToken, 2 = verifyToken, 3 = freeToken.
 *      WAN ops: 16388 = IPV4_GET, 16389 = IPV4_SET ({"wan": {...}} JSON, the
 *      PPPoE user/password inside wan.user_info are base64-encoded).
 *
 * Returns an empty string on success, or a human-readable error message.
 * NOTE: implemented from the decompiled app — not yet verified on real hardware.
 */
object TPLinkDecoRouter {

    private const val SSH_PORT = 22
    private const val TMP_FORWARD_PORT = 20002
    private const val APP_VERSION = 1516993677
    private const val TIMEOUT_MS = 15000

    private const val OP_ALLOC_TOKEN = 1
    private const val OP_VERIFY_TOKEN = 2
    private const val OP_FREE_TOKEN = 3
    private const val OP_IPV4_GET = 16388
    private const val OP_IPV4_SET = 16389

    suspend fun loginAndSetPppoe(
        ip: String,
        tplinkId: String,
        tplinkIdPassword: String,
        newPppoeUser: String,
        newPppoePassword: String
    ): String = withContext(Dispatchers.IO) {
        var session: Session? = null
        var localPort = 0
        try {
            val jsch = JSch()
            session = jsch.getSession(tplinkId, ip, SSH_PORT).apply {
                setPassword(tplinkIdPassword)
                setConfig("StrictHostKeyChecking", "no")
                timeout = TIMEOUT_MS
                connect(TIMEOUT_MS)
            }
            localPort = session.setPortForwardingL(0, "127.0.0.1", TMP_FORWARD_PORT)

            Socket("127.0.0.1", localPort).use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val tmpl = Tmpl(socket.getInputStream(), socket.getOutputStream())
                try {
                    // Open the session: alloc + verify token.
                    val token = tmpl.request(OP_ALLOC_TOKEN, null)
                        ?: return@withContext "Token allocation failed"
                    tmpl.request(OP_VERIFY_TOKEN, token)

                    // Read the current WAN config.
                    val getReply = tmpl.request(OP_IPV4_GET, null)
                        ?: return@withContext "IPV4_GET returned no data"
                    val root = JsonParser.parseString(getReply.toString(Charsets.UTF_8)).asJsonObject
                    val wan = root.getAsJsonObject("wan")
                        ?: return@withContext "IPV4_GET: no \"wan\" object in response"

                    // Patch the PPPoE credentials (base64, like the app).
                    val userInfo = if (wan.has("user_info") && wan.get("user_info").isJsonObject) {
                        wan.getAsJsonObject("user_info")
                    } else {
                        JsonObject()
                    }
                    userInfo.addProperty(
                        "username",
                        Base64.encodeToString(newPppoeUser.toByteArray(), Base64.NO_WRAP)
                    )
                    userInfo.addProperty(
                        "password",
                        Base64.encodeToString(newPppoePassword.toByteArray(), Base64.NO_WRAP)
                    )
                    userInfo.addProperty("auto_config", true)
                    wan.add("user_info", userInfo)
                    wan.addProperty("dial_type", "PPPOE_V4")

                    val setPayload = JsonObject().apply { add("wan", wan) }.toString()
                        .toByteArray(Charsets.UTF_8)
                    tmpl.request(OP_IPV4_SET, setPayload)
                        ?: return@withContext "IPV4_SET returned no data"
                } finally {
                    try {
                        tmpl.request(OP_FREE_TOKEN, null)
                    } catch (_: Exception) {
                        // Session already gone — nothing to free.
                    }
                }
            }
            "PPPoE credentials switched (verify on the Deco app status page)"
        } catch (e: Exception) {
            "❌ ${e.localizedMessage ?: "Unexpected error"}"
        } finally {
            try {
                session?.delPortForwardingL(localPort)
            } catch (_: Exception) {
            }
            try {
                session?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** TMP v1 + app v2 transaction client over a socket. */
    private class Tmpl(private val input: InputStream, private val output: OutputStream) {
        private var seq = 1
        private var txid = 0

        /**
         * Sends one request and waits for the response with the matching
         * sequence number. Returns the response data (may be empty), or null
         * on transport failure. Throws on a non-zero router error byte.
         */
        fun request(opcode: Int, data: ByteArray?): ByteArray? {
            val currentTxid = txid++
            output.write(buildTmpFrame(5, seq, buildAppV2(opcode, currentTxid, data)))
            output.flush()
            val expectedSeq = seq
            seq++
            while (true) {
                val (frameSeq, payload) = readFrame() ?: return null
                if (frameSeq == expectedSeq) {
                    val error = payload[5].toInt()
                    if (error != 0) {
                        throw IOException("Router error $error (opcode $opcode)")
                    }
                    return if (payload.size > 20) payload.copyOfRange(20, payload.size) else ByteArray(0)
                }
            }
        }

        private fun readFrame(): Pair<Int, ByteArray>? {
            while (true) {
                val header = readExact(16) ?: return null
                val type = header[2].toInt() and 0xFF
                val length = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                val frameSeq = readIntBE(header, 8)
                val payload = if (length > 0) readExact(length) ?: return null else ByteArray(0)
                if (type == 4) { // Keepalive ping: answer and keep waiting.
                    output.write(buildTmpFrame(4, seq, ByteArray(0)))
                    output.flush()
                    continue
                }
                if (type != 5) {
                    throw IOException("Unexpected TMP frame type $type")
                }
                return frameSeq to payload
            }
        }

        private fun readExact(n: Int): ByteArray? {
            val buf = ByteArray(n)
            var offset = 0
            while (offset < n) {
                val read = input.read(buf, offset, n - offset)
                if (read < 0) return null
                offset += read
            }
            return buf
        }
    }

    private fun buildTmpFrame(ptype: Int, seq: Int, payload: ByteArray): ByteArray {
        val header = ByteArray(16)
        header[0] = 0x01 // magic
        header[1] = 0x01 // version
        header[2] = ptype.toByte()
        putShortBE(header, 4, payload.size)
        putIntBE(header, 8, seq)
        putIntBE(header, 12, APP_VERSION)
        val crc = CRC32().apply {
            update(header)
            update(payload)
        }.value.toInt()
        putIntBE(header, 12, crc)
        return header + payload
    }

    private fun buildAppV2(opcode: Int, txid: Int, data: ByteArray?): ByteArray {
        val d = data ?: ByteArray(0)
        val payload = ByteArray(20 + d.size)
        payload[0] = 0x01 // magic
        payload[1] = 0x02 // version
        putShortBE(payload, 2, opcode)
        payload[4] = 0x02 // PUSH (request)
        payload[5] = 0x00 // error
        putShortBE(payload, 6, txid)
        val crc = CRC32().apply { update(d) }.value.toInt()
        putIntBE(payload, 8, crc)
        putIntBE(payload, 12, d.size)
        putIntBE(payload, 16, 0) // offset
        System.arraycopy(d, 0, payload, 20, d.size)
        return payload
    }

    private fun putShortBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putIntBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readIntBE(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
}