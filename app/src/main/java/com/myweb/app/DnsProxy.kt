package com.myweb.app

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Proxy lokal kecil (127.0.0.1) yang dipakai WebView. Tugasnya hanya MENCARI ALAMAT IP lewat DNS-over-HTTPS
// (Cloudflare / Google / alamat sendiri), lalu meneruskan data apa adanya. HTTPS tetap tersambung langsung
// dari WebView ke situs (terenkripsi ujung ke ujung), jadi login/cookie/Cloudflare tidak terganggu.
// Kalau DoH gagal, otomatis memakai DNS bawaan HP.
object DnsProxy {

    val PRESETS = mapOf(
        "cloudflare" to "https://1.1.1.1/dns-query",
        "google" to "https://8.8.8.8/resolve"
    )

    @Volatile private var server: ServerSocket? = null
    @Volatile private var dohBase = ""
    private val pool = Executors.newCachedThreadPool()
    private class Entry(val addrs: List<InetAddress>, val expires: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 5 * 60 * 1000L

    @Synchronized
    fun start(doh: String): Int {
        if (doh != dohBase) cache.clear()
        dohBase = doh
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute { acceptLoop(s) }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (e: Exception) { }
        server = null
        cache.clear()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: Exception) { break }
            pool.execute { handle(c) }
        }
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        var answered = false
        var isConnect = false
        var handedOff = false
        try {
            client.soTimeout = 30000
            val inp = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            val head = readHead(inp) ?: return
            val lines = head.split("\r\n")
            val first = lines[0].split(" ")
            if (first.size < 3) return
            val method = first[0]
            val target = first[1]

            if (method.equals("CONNECT", true)) {
                isConnect = true
                val hp = hostPort(target, 443)
                val rs = open(hp.first, hp.second)
                remote = rs
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                out.flush()
                answered = true
            } else {
                val u = URL(target)
                val port = if (u.port > 0) u.port else 80
                val rs = open(u.host, port)
                remote = rs
                val path = if (u.file.isNullOrEmpty()) "/" else u.file
                val sb = StringBuilder().append(method).append(' ').append(path).append(' ').append(first[2]).append("\r\n")
                for (i in 1 until lines.size) {
                    val l = lines[i]
                    if (l.isEmpty()) continue
                    val k = l.substringBefore(':').trim().lowercase()
                    if (k == "proxy-connection" || k == "connection" || k == "keep-alive") continue
                    sb.append(l).append("\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                rs.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                rs.getOutputStream().flush()
                answered = true
            }

            client.soTimeout = 300000
            val r: Socket = remote ?: return
            r.soTimeout = 300000
            handedOff = true
            pool.execute { copy(r.getInputStream(), out, client, r) }
            copy(inp, r.getOutputStream(), client, r)
        } catch (e: Exception) {
            if (!answered && isConnect) {
                try { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()) } catch (x: Exception) { }
            }
            closeQuiet(client)
            closeQuiet(remote)
        } finally {
            // Jalur return awal (header kosong/tidak valid, koneksi ditutup sebelum waktunya) tidak pernah
            // sampai ke copy(), jadi socket harus ditutup di sini agar tidak bocor.
            if (!handedOff) {
                closeQuiet(client)
                closeQuiet(remote)
            }
        }
    }

    private fun copy(i: InputStream, o: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (e: Exception) { }
        closeQuiet(a)
        closeQuiet(b)
    }

    private fun closeQuiet(s: Socket?) {
        try { s?.close() } catch (e: Exception) { }
    }

    private fun readHead(inp: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16384) {
            val b = inp.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) return sb.substring(0, sb.length - 4)
        }
        return null
    }

    private fun hostPort(t: String, def: Int): Pair<String, Int> {
        if (t.startsWith("[")) {
            val e = t.indexOf(']')
            if (e > 0) return t.substring(1, e) to (t.substring(e + 1).removePrefix(":").toIntOrNull() ?: def)
        }
        val i = t.lastIndexOf(':')
        return if (i > 0) t.substring(0, i) to (t.substring(i + 1).toIntOrNull() ?: def) else t to def
    }

    private fun open(host: String, port: Int): Socket {
        var last: Exception? = null
        for (a in resolve(host).take(4)) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(a, port), 10000)
                s.tcpNoDelay = true
                return s
            } catch (e: Exception) {
                last = e
                closeQuiet(s)
            }
        }
        throw last ?: java.net.UnknownHostException(host)
    }

    private fun isLiteral(h: String) = h.contains(':') || h.matches(Regex("^[0-9.]+$"))

    private fun resolve(host: String): List<InetAddress> {
        if (isLiteral(host)) return listOf(InetAddress.getByName(host))
        val now = System.currentTimeMillis()
        cache[host]?.let { if (it.expires > now) return it.addrs }
        var res: List<InetAddress> = emptyList()
        try {
            res = doh(host, "A")
            if (res.isEmpty()) res = doh(host, "AAAA")
        } catch (e: Exception) { }
        if (res.isEmpty()) res = InetAddress.getAllByName(host).toList()   // cadangan: DNS bawaan HP
        cache[host] = Entry(res, now + TTL_MS)
        return res
    }

    private fun doh(host: String, type: String): List<InetAddress> {
        val base = dohBase
        val sep = if (base.contains('?')) "&" else "?"
        val c = URL("$base${sep}name=$host&type=$type").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("Accept", "application/dns-json")
        try {
            if (c.responseCode != 200) return emptyList()
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val ans = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            val want = if (type == "A") 1 else 28
            val out = ArrayList<InetAddress>()
            for (i in 0 until ans.length()) {
                val o = ans.getJSONObject(i)
                if (o.optInt("type") == want) out.add(InetAddress.getByName(o.getString("data")))
            }
            return out
        } finally {
            c.disconnect()
        }
    }
}
