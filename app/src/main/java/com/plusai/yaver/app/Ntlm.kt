package com.plusai.yaver.app

import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** NTLMv2 (what Exchange's Windows authentication speaks), written out so no library is needed. */
object Ntlm {
    private const val FLAGS = 0x00000001 or 0x00000002 or 0x00000004 or 0x00000200 or 0x00008000 or 0x00080000 or 0x00800000 or 0x20000000 or (0x80000000).toInt()

    fun type1(): String {
        val b = ByteArray(32)
        "NTLMSSP".toByteArray().copyInto(b)
        le32(b, 8, 1)
        le32(b, 12, FLAGS)
        // empty domain and workstation buffers (offset 32)
        le16(b, 16, 0); le16(b, 18, 0); le32(b, 20, 32)
        le16(b, 24, 0); le16(b, 26, 0); le32(b, 28, 32)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    class Challenge(val flags: Int, val challenge: ByteArray, val targetInfo: ByteArray)

    fun parseType2(b64: String): Challenge {
        val b = Base64.decode(b64, Base64.DEFAULT)
        if (b.size < 32 || String(b, 0, 7) != "NTLMSSP" || r32(b, 8) != 2) throw IOException("bad NTLM challenge")
        val flags = r32(b, 20)
        val ch = b.copyOfRange(24, 32)
        var ti = ByteArray(0)
        if (b.size >= 48) {
            val len = r16(b, 40)
            val off = r32(b, 44)
            if (len > 0 && off + len <= b.size) ti = b.copyOfRange(off, off + len)
        }
        return Challenge(flags, ch, ti)
    }

    /** user may be DOMAIN\user, user@domain or just user. */
    fun type3(c: Challenge, userIn: String, password: String): String {
        var domain = ""
        var user = userIn
        if (userIn.contains('\\')) { domain = userIn.substringBefore('\\'); user = userIn.substringAfter('\\') }
        val ntHash = md4(password.toByteArray(Charsets.UTF_16LE))
        val v2Hash = hmac(ntHash, (user.uppercase() + domain).toByteArray(Charsets.UTF_16LE))
        val clientCh = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val ts = timestampFrom(c.targetInfo) ?: run {
            val t = (System.currentTimeMillis() + 11644473600000L) * 10000L
            ByteArray(8).also { for (i in 0 until 8) it[i] = (t ushr (8 * i)).toByte() }
        }
        val blob = ByteArrayOutputStream()
        blob.write(byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0))
        blob.write(ts)
        blob.write(clientCh)
        blob.write(byteArrayOf(0, 0, 0, 0))
        blob.write(c.targetInfo)
        blob.write(byteArrayOf(0, 0, 0, 0))
        val blobB = blob.toByteArray()
        val proof = hmac(v2Hash, c.challenge + blobB)
        val nt = proof + blobB
        val lm = if (timestampFrom(c.targetInfo) != null) ByteArray(24) else hmac(v2Hash, c.challenge + clientCh) + clientCh
        val dom = domain.toByteArray(Charsets.UTF_16LE)
        val usr = user.toByteArray(Charsets.UTF_16LE)
        val ws = "YAVER".toByteArray(Charsets.UTF_16LE)
        val head = 64
        val out = ByteArray(head + lm.size + nt.size + dom.size + usr.size + ws.size)
        "NTLMSSP".toByteArray().copyInto(out)
        le32(out, 8, 3)
        var off = head
        fun buf(pos: Int, data: ByteArray) { le16(out, pos, data.size); le16(out, pos + 2, data.size); le32(out, pos + 4, off); data.copyInto(out, off); off += data.size }
        buf(12, lm); buf(20, nt); buf(28, dom); buf(36, usr); buf(44, ws)
        le16(out, 52, 0); le16(out, 54, 0); le32(out, 56, off) // no session key
        le32(out, 60, (c.flags and FLAGS) or 0x00000001)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun timestampFrom(ti: ByteArray): ByteArray? {
        var p = 0
        while (p + 4 <= ti.size) {
            val id = r16(ti, p)
            val len = r16(ti, p + 2)
            if (id == 0) return null
            if (id == 7 && len == 8 && p + 12 <= ti.size) return ti.copyOfRange(p + 4, p + 12)
            p += 4 + len
        }
        return null
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacMD5")
        m.init(SecretKeySpec(key, "HmacMD5"))
        return m.doFinal(data)
    }

    private fun le16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun le32(b: ByteArray, o: Int, v: Int) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }
    private fun r16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun r32(b: ByteArray, o: Int) = r16(b, o) or (r16(b, o + 2) shl 16)

    /** MD4 (RFC 1320) — not shipped by Android, needed for the NT hash. */
    fun md4(input: ByteArray): ByteArray {
        val msgLen = input.size.toLong() * 8
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val msg = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[msg.size - 8 + i] = (msgLen ushr (8 * i)).toByte()
        var a = 0x67452301; var b = 0xefcdab89.toInt(); var c = 0x98badcfe.toInt(); var d = 0x10325476
        val x = IntArray(16)
        fun rl(v: Int, s: Int) = (v shl s) or (v ushr (32 - s))
        var blk = 0
        while (blk < msg.size) {
            for (i in 0 until 16) x[i] = (msg[blk + i * 4].toInt() and 0xff) or ((msg[blk + i * 4 + 1].toInt() and 0xff) shl 8) or ((msg[blk + i * 4 + 2].toInt() and 0xff) shl 16) or ((msg[blk + i * 4 + 3].toInt() and 0xff) shl 24)
            val aa = a; val bb = b; val cc = c; val dd = d
            fun f(x1: Int, y: Int, z: Int) = (x1 and y) or (x1.inv() and z)
            fun g(x1: Int, y: Int, z: Int) = (x1 and y) or (x1 and z) or (y and z)
            fun h(x1: Int, y: Int, z: Int) = x1 xor y xor z
            for (i in intArrayOf(0, 4, 8, 12)) {
                a = rl(a + f(b, c, d) + x[i], 3); d = rl(d + f(a, b, c) + x[i + 1], 7)
                c = rl(c + f(d, a, b) + x[i + 2], 11); b = rl(b + f(c, d, a) + x[i + 3], 19)
            }
            for (i in 0 until 4) {
                a = rl(a + g(b, c, d) + x[i] + 0x5a827999, 3); d = rl(d + g(a, b, c) + x[i + 4] + 0x5a827999, 5)
                c = rl(c + g(d, a, b) + x[i + 8] + 0x5a827999, 9); b = rl(b + g(c, d, a) + x[i + 12] + 0x5a827999, 13)
            }
            for (i in intArrayOf(0, 2, 1, 3)) {
                a = rl(a + h(b, c, d) + x[i] + 0x6ed9eba1, 3); d = rl(d + h(a, b, c) + x[i + 8] + 0x6ed9eba1, 9)
                c = rl(c + h(d, a, b) + x[i + 4] + 0x6ed9eba1, 11); b = rl(b + h(c, d, a) + x[i + 12] + 0x6ed9eba1, 15)
            }
            a += aa; b += bb; c += cc; d += dd
            blk += 64
        }
        val out = ByteArray(16)
        for ((k, v) in intArrayOf(a, b, c, d).withIndex()) for (i in 0 until 4) out[k * 4 + i] = (v shr (8 * i)).toByte()
        return out
    }
}

class SockResp(val code: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
    fun all(name: String) = headers.filter { it.first.equals(name, true) }.map { it.second }
    fun header(name: String) = all(name).firstOrNull()
    fun text() = String(body, Charsets.UTF_8)
}

/**
 * A tiny HTTP/1.1 client over one kept-alive TLS socket. NTLM authenticates the connection itself, which
 * HttpURLConnection's connection pool cannot guarantee — so EWS gets its own socket.
 */
class SockHttp(private val base: URL) {
    private var sock: Socket? = null
    private var ins: InputStream? = null
    private var out: OutputStream? = null
    var authenticated = false
        private set

    val isOpen: Boolean get() = sock?.isClosed == false

    private fun connect() {
        close()
        val port = if (base.port > 0) base.port else base.defaultPort
        val s: Socket
        if (base.protocol == "https") {
            val raw = Socket()
            raw.connect(InetSocketAddress(base.host, port), 20000)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, base.host, port, true) as SSLSocket
            ssl.soTimeout = 90000
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(base.host, ssl.session)) { ssl.close(); throw IOException("certificate does not match ${base.host}") }
            s = ssl
        } else {
            s = Socket()
            s.connect(InetSocketAddress(base.host, port), 20000)
            s.soTimeout = 90000
        }
        sock = s
        ins = BufferedInputStream(s.getInputStream())
        out = s.getOutputStream()
        authenticated = false
    }

    fun close() {
        try { sock?.close() } catch (_: Throwable) {}
        sock = null; ins = null; out = null; authenticated = false
    }

    fun markAuthenticated() { authenticated = true }

    fun exchange(method: String, path: String, headers: List<Pair<String, String>>, body: ByteArray?, cancel: Cancel? = null): SockResp {
        if (!isOpen) connect()
        cancel?.onCancel { close() }
        val o = out ?: throw IOException("not connected")
        val sb = StringBuilder()
        sb.append("$method $path HTTP/1.1\r\n")
        sb.append("Host: ${base.host}${if (base.port > 0 && base.port != base.defaultPort) ":" + base.port else ""}\r\n")
        sb.append("Connection: keep-alive\r\n")
        sb.append("User-Agent: ${Http.UA}\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ${body?.size ?: 0}\r\n\r\n")
        o.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) o.write(body)
        o.flush()
        return readResponse(method == "HEAD")
    }

    private fun readLine(i: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) { if (b.size() == 0) throw IOException("connection closed by the server"); break }
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return String(b.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readResponse(head: Boolean): SockResp {
        val i = ins ?: throw IOException("not connected")
        var status = readLine(i)
        var code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("bad response: $status")
        val hs = ArrayList<Pair<String, String>>()
        while (true) {
            val l = readLine(i)
            if (l.isEmpty()) {
                if (code == 100) { status = readLine(i); code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: 0; hs.clear(); continue }
                break
            }
            val c = l.indexOf(':')
            if (c > 0) hs.add(Pair(l.substring(0, c).trim(), l.substring(c + 1).trim()))
        }
        val resp = SockResp(code, hs, ByteArray(0))
        val te = resp.header("transfer-encoding") ?: ""
        val cl = resp.header("content-length")?.toIntOrNull()
        val body = ByteArrayOutputStream()
        if (head || code == 204 || code == 304) {
            // no body
        } else if (te.contains("chunked", true)) {
            while (true) {
                val sz = readLine(i).substringBefore(';').trim().toInt(16)
                if (sz == 0) { while (readLine(i).isNotEmpty()) { }; break }
                readExactly(i, sz, body)
                readLine(i)
            }
        } else if (cl != null) {
            readExactly(i, cl, body)
        } else {
            val buf = ByteArray(16384)
            while (true) { val n = i.read(buf); if (n < 0) break; body.write(buf, 0, n) }
            close()
        }
        if (resp.header("connection")?.contains("close", true) == true) close()
        return SockResp(code, hs, body.toByteArray())
    }

    private fun readExactly(i: InputStream, n: Int, out: ByteArrayOutputStream) {
        val buf = ByteArray(16384)
        var left = n
        while (left > 0) {
            val r = i.read(buf, 0, minOf(buf.size, left))
            if (r < 0) throw IOException("connection closed mid-response")
            out.write(buf, 0, r)
            left -= r
        }
    }
}
