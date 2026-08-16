#!/usr/bin/env python3
"""
TP-Link Deco (TPM5) PPPoE driver - TMP protocol over SSH.

Reverse-engineered from the official Deco app (com.tplink.tpm5 3.10.489,
decompiled with jadx). The Deco router has no CGI admin panel; the app talks
to it over SSH, which is tunneled to a local TMP server (port 20002).

Transport (mirrors the app's com.tplink.tmp.transport.ssh2 layer):
  1. SSH to the router on port 22, username = TP-Link ID e-mail, password =
     the TP-Link ID password, plaintext password auth (no hashing).
  2. A direct-tcpip channel to 127.0.0.1:20002 on the router (the app does an
     equivalent SSH port-forward).

TMP v1 framing (com.tplink.tmp.tmp.h0):
  16-byte header:
    [0]    magic     = 0x01
    [1]    version   = 0x01
    [2]    type      (4 = keepalive ping, 5 = request/response)
    [3]    reserved  = 0
    [4..5] payload length (big-endian)
    [6..7] reserved  = 0
    [8..11] sequence number (big-endian; client counts up from 1)
    [12..15] CRC32 of the whole frame, computed with the constant
             appVersion 1516993677 in bytes 12-15, then overwritten with the CRC.
  There is no hello/handshake: the client just sends type-5 frames.

App v2 business payload (com.tplink.tmp.business.v2.o / u):
  [0] 0x01 magic
  [1] 0x02 version
  then an 18-byte header:
    [0..1]  opcode (big-endian)
    [2]     packet type (2 = PUSH, 3 = PUSH_ACK, 4 = PULL, 5 = PULL_ACK)
    [3]     error byte (0 = ok; responses carry -3220 when the token expired)
    [4..5]  txid (big-endian; client counts up from 0)
    [6..9]  crc32 of the data payload
    [10..13] total data length
    [14..17] offset (chunking, 8156-byte chunks; unused for small payloads)
  then the data.

Session ops (opcodes): 1 = allocToken (no data, response data = token bytes),
  2 = verifyToken (data = token), 3 = freeToken (no data).
WAN ops (com.tplink.libtpnetwork.mesh.global.p): 16388 = IPV4_GET,
  16389 = IPV4_SET. IPV4_GET returns {"wan": IPv4WanBean, "lan": ...};
  IPV4_SET takes {"wan": IPv4WanBean}. The PPPoE user/password fields inside
  "wan" -> "user_info" are base64-encoded.

NOTE: implemented purely from the decompiled app - NOT live-tested against a
real Deco router yet. Treat the first run on real hardware as a test.
"""

import base64
import json
import logging
import struct
import zlib

try:
    import paramiko
except ImportError:  # pragma: no cover
    paramiko = None

log = logging.getLogger("iuser-monitor")

TMP_PORT = 22
TMP_FORWARD_PORT = 20002
APP_VERSION = 1516993677  # constant used while computing the frame CRC

TMP_MAGIC = 0x01
TMP_VERSION = 0x01
TMP_TYPE_DATA = 5
TMP_TYPE_PING = 4

OP_ALLOC_TOKEN = 1
OP_VERIFY_TOKEN = 2
OP_FREE_TOKEN = 3
OP_IPV4_GET = 16388
OP_IPV4_SET = 16389

PT_PUSH = 2  # request
PT_PUSH_ACK = 3  # response to a push


def _build_tmp_frame(ptype, seq, payload):
    header = bytearray(16)
    header[0] = TMP_MAGIC
    header[1] = TMP_VERSION
    header[2] = ptype
    struct.pack_into(">H", header, 4, len(payload))
    struct.pack_into(">I", header, 8, seq)
    struct.pack_into(">I", header, 12, APP_VERSION)
    frame = bytes(header) + payload
    crc = zlib.crc32(frame) & 0xFFFFFFFF
    return frame[:12] + struct.pack(">I", crc) + frame[16:]


def _parse_tmp_header(header):
    return {
        "magic": header[0],
        "version": header[1],
        "type": header[2],
        "length": struct.unpack(">H", header[4:6])[0],
        "seq": struct.unpack(">I", header[8:12])[0],
    }


def _build_appv2(opcode, txid, data):
    data = data if data is not None else b""
    payload = bytearray(2 + 18)
    payload[0] = 0x01  # magic
    payload[1] = 0x02  # version
    struct.pack_into(">H", payload, 2, opcode)
    payload[4] = PT_PUSH
    payload[5] = 0  # error
    struct.pack_into(">H", payload, 6, txid)
    struct.pack_into(">I", payload, 8, zlib.crc32(data) & 0xFFFFFFFF)
    struct.pack_into(">I", payload, 12, len(data))
    struct.pack_into(">I", payload, 16, 0)  # offset
    return bytes(payload) + data


def _parse_appv2(payload):
    opcode = struct.unpack(">H", payload[2:4])[0]
    ptype = payload[4]
    error = payload[5]
    txid = struct.unpack(">H", payload[6:8])[0]
    data = payload[20:]
    return {"opcode": opcode, "ptype": ptype, "error": error, "txid": txid,
            "data": data}


class TPLinkDeco:
    """TMP-over-SSH client for TP-Link Deco routers (TPM5 firmware)."""

    def __init__(self, host, tplink_id, tplink_password, timeout=10):
        if paramiko is None:
            raise RuntimeError(
                "paramiko is not installed; run: pip install paramiko")
        self.host = host
        self.tplink_id = tplink_id
        self.tplink_password = tplink_password
        self.timeout = timeout
        self._ssh = None
        self._chan = None
        self._seq = 1
        self._txid = 0
        self._token = None

    # -- transport -----------------------------------------------------

    def _open(self):
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(
            self.host,
            port=TMP_PORT,
            username=self.tplink_id,
            password=self.tplink_password,
            timeout=self.timeout,
            banner_timeout=self.timeout,
            auth_timeout=self.timeout,
            allow_agent=False,
            look_for_keys=False,
        )
        transport = ssh.get_transport()
        chan = transport.open_channel(
            "direct-tcpip", ("127.0.0.1", TMP_FORWARD_PORT), ("127.0.0.1", 0))
        chan.settimeout(self.timeout)
        self._ssh = ssh
        self._chan = chan

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self._chan.recv(n - len(buf))
            if not chunk:
                raise IOError("SSH channel closed by the router")
            buf += chunk
        return buf

    def _send_frame(self, ptype, payload):
        frame = _build_tmp_frame(ptype, self._seq, payload)
        self._seq += 1
        self._chan.sendall(frame)

    def _recv_frame(self):
        while True:
            header = self._recv_exact(16)
            info = _parse_tmp_header(header)
            if info["magic"] != TMP_MAGIC or info["version"] != TMP_VERSION:
                raise IOError("Bad TMP header (magic/version mismatch)")
            payload = self._recv_exact(info["length"]) if info["length"] else b""
            if info["type"] == TMP_TYPE_PING:
                # Router keepalive: answer it and keep waiting for data.
                self._send_frame(TMP_TYPE_PING, b"")
                continue
            if info["type"] != TMP_TYPE_DATA:
                raise IOError(f"Unexpected TMP frame type {info['type']}")
            return info["seq"], payload

    def _app_request(self, opcode, data=None):
        txid = self._txid
        self._txid = (self._txid + 1) & 0xFFFF
        self._send_frame(TMP_TYPE_DATA, _build_appv2(opcode, txid, data))
        expected_seq = self._seq - 1
        while True:
            seq, payload = self._recv_frame()
            if seq == expected_seq:
                return _parse_appv2(payload)

    # -- session -------------------------------------------------------

    def _open_session(self):
        reply = self._app_request(OP_ALLOC_TOKEN)
        if reply["error"] != 0:
            raise IOError(f"allocToken failed (error={reply['error']})")
        self._token = reply["data"]
        reply = self._app_request(OP_VERIFY_TOKEN, self._token)
        if reply["error"] != 0:
            raise IOError(f"verifyToken failed (error={reply['error']})")

    # -- API (mirrors TPLinkCgi.login / set_pppoe) ---------------------

    def login(self):
        try:
            self._open()
            self._open_session()
            log.info("Deco router login OK (%s)", self.host)
            return True
        except Exception as e:  # noqa: BLE001
            log.error("Deco router login failed (%s): %s", self.host, e)
            self.close()
            return False

    def get_wan(self):
        """Returns the IPv4WanBean dict from IPV4_GET (16388)."""
        reply = self._app_request(OP_IPV4_GET)
        if reply["error"] != 0:
            raise IOError(f"IPV4_GET failed (error={reply['error']})")
        obj = json.loads(reply["data"].decode("utf-8"))
        return obj["wan"]

    def set_pppoe(self, new_username, new_password):
        """Round-trip like the app: GET wan, patch user_info, SET wan back."""
        try:
            wan = self.get_wan()
        except Exception as e:  # noqa: BLE001
            return f"Could not read current WAN config: {e}"
        user_info = wan.get("user_info") or {}
        user_info["username"] = base64.b64encode(
            new_username.encode("utf-8")).decode("ascii")
        user_info["password"] = base64.b64encode(
            new_password.encode("utf-8")).decode("ascii")
        user_info["auto_config"] = True
        wan["user_info"] = user_info
        wan["dial_type"] = "PPPOE_V4"
        payload = json.dumps({"wan": wan}, separators=(",", ":")).encode("utf-8")
        try:
            reply = self._app_request(OP_IPV4_SET, payload)
            if reply["error"] != 0:
                return f"Router rejected IPV4_SET (error={reply['error']})"
            return ""
        except Exception as e:  # noqa: BLE001
            return f"Router request failed: {e}"

    def close(self):
        try:
            if self._token is not None:
                self._app_request(OP_FREE_TOKEN)
        except Exception:  # noqa: BLE001
            pass
        self._token = None
        try:
            if self._chan is not None:
                self._chan.close()
        except Exception:  # noqa: BLE001
            pass
        self._chan = None
        try:
            if self._ssh is not None:
                self._ssh.close()
        except Exception:  # noqa: BLE001
            pass
        self._ssh = None