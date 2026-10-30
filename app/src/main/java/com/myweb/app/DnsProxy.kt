package com.myweb.app

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// Proxy lokal kecil (127.0.0.1, port acak) yang dipakai WebView. Tugasnya hanya MENCARI ALAMAT IP lewat DNS-over-HTTPS
// (Cloudflare / Google / alamat sendiri, format JSON), lalu meneruskan data apa adanya. HTTPS tetap tersambung langsung
// dari WebView ke situs (terenkripsi ujung ke ujung), jadi login/cookie/Cloudflare tidak terganggu.
// Logika DNS (validasi, cache TTL, cadangan, pembatasan waktu) ada di DohResolver.java (diuji di JVM).
// Aturan cadangan: HANYA kegagalan transport DoH (jaringan/TLS/waktu habis/HTTP error/isi bukan JSON DNS) yang beralih ke
// DNS bawaan HP, dan itu diberitahukan. Jawaban DNS yang sah (NXDOMAIN, tanpa alamat, diblokir) dihormati tanpa cadangan.
object DnsProxy {

    val PRESETS = mapOf(
        "cloudflare" to "https://1.1.1.1/dns-query",
        "google" to "https://8.8.8.8/resolve"
    )

    @Volatile private var server: ServerSocket? = null
    @Volatile private var resolver: DohResolver? = null
    @Volatile private var owner: Any? = null
    @Volatile private var listener: ((String) -> Unit)? = null
    private var endpoint = ""
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    // Semua soket yang sedang terbuka (klien + tujuan): ditutup saat proxy dihentikan supaya tidak ada yang tertinggal
    private val live: MutableSet<Socket> = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val served = AtomicLong()

    /** null = alamat DoH valid; selain itu pesan untuk pengguna. */
    fun validate(url: String): String? = DohResolver.validateEndpoint(url)

    /** Uji server DoH (tanpa cache/cadangan). null = berfungsi; selain itu alasan singkat. Blokir: panggil dari thread latar. */
    fun probe(doh: String): String? {
        val err = DohResolver.validateEndpoint(doh)
        if (err != null) return err
        return try { DohResolver.create(doh, pool, null).probe() } catch (e: Exception) { "gagal menguji" }
    }

    fun stats(): String {
        val r = resolver ?: return "Proxy DNS: tidak aktif"
        return "Proxy DNS: aktif, sambungan dilayani=" + served.get() + ", " + r.stats()
    }

    /** Waktu (ms epoch) terakhir DNS sistem dipakai sebagai cadangan; 0 = belum / proxy tidak aktif. */
    fun lastFallbackAt(): Long = resolver?.lastFallbackAt() ?: 0L

    // Dipanggil dari UI thread. Melempar IllegalArgumentException (alamat tidak valid) atau IOException (port gagal dibuka);
    // pemanggil harus menampilkan kegagalan itu dan TIDAK memasang proxy ke WebView.
    @Synchronized
    fun start(doh: String, who: Any, onEvent: (String) -> Unit): Int {
        val err = DohResolver.validateEndpoint(doh)
        if (err != null) throw IllegalArgumentException(err)
        val existing = server
        val s: ServerSocket
        if (existing != null && !existing.isClosed) {
            s = existing
        } else {
            s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            server = s
            pool.execute { acceptLoop(s) }
        }
        // konfigurasi DoH berubah -> resolver baru (cache lama ikut dibuang, permintaan lama dibatalkan)
        if (resolver == null || endpoint != doh) {
            val old = resolver
            resolver = DohResolver.create(doh, pool) { m -> listener?.invoke(m) }
            endpoint = doh
            old?.close()
        }
        owner = who
        listener = onEvent
        return s.localPort
    }

    // Dipanggil Activity yang sedang dihancurkan: hanya menghentikan proxy bila Activity itu pemiliknya.
    // (Activity lama yang baru dihancurkan setelah Activity baru memulai proxy tidak boleh mematikannya.)
    @Synchronized
    fun stop(who: Any) {
        if (owner === who) shutdown()
    }

    // Pengguna memilih DNS sistem: hentikan proxy siapa pun pemiliknya.
    @Synchronized
    fun stopAll() {
        shutdown()
    }

    private fun shutdown() {
        try { server?.close() } catch (e: Exception) { }
        server = null
        resolver?.close()
        resolver = null
        endpoint = ""
        owner = null
        listener = null
        for (s in live.toList()) closeQuiet(s)
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: Exception) { break }
            live.add(c)
            if (s.isClosed) { closeQuiet(c); break }
            served.incrementAndGet()
            // Pengaman terakhir: exception apa pun di thread pool tidak boleh mematikan aplikasi
            try {
                pool.execute { try { handle(c) } catch (t: Throwable) { closeQuiet(c) } }
            } catch (e: Exception) {
                closeQuiet(c)
            }
        }
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        var answered = false
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
                val hp = hostPort(target, 443)
                val rs = open(hp.first, hp.second, client)
                remote = rs
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                out.flush()
                answered = true
            } else {
                val u = URL(target)
                val port = if (u.port > 0) u.port else 80
                val rs = open(u.host, port, client)
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
            // Aliran diambil DI SINI (di dalam try), bukan di dalam lambda pool: kalau r sudah ditutup (klien memutus
            // koneksi lebih dulu, mis. saat WebView kembali dari latar belakang), getInputStream() melempar
            // SocketException; di thread pool itu tidak tertangkap dan mematikan aplikasi.
            val rin = r.getInputStream()
            val rout = r.getOutputStream()
            handedOff = true
            pool.execute {
                try { copy(rin, out, client, r) } catch (t: Throwable) { closeQuiet(client); closeQuiet(r) }
            }
            copy(inp, rout, client, r)
        } catch (e: Exception) {
            // Kegagalan sebelum ada jawaban (DNS tidak ketemu, tidak bisa tersambung, URL rusak): beri tahu WebView dengan
            // status HTTP yang jelas untuk CONNECT maupun HTTP biasa (dulu HTTP biasa hanya ditutup tanpa jawaban).
            if (!answered) {
                val status = if (e is MalformedURLException) "400 Bad Request" else "502 Bad Gateway"
                try {
                    client.getOutputStream().write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                } catch (x: Exception) { }
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
        if (s == null) return
        live.remove(s)
        try { s.close() } catch (e: Exception) { }
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

    // Pencarian + sambungan: IPv4 dulu, IPv6 bila IPv4 tidak ada/gagal; batas 10 dtk per alamat, 20 dtk total.
    private fun open(host: String, port: Int, client: Socket): Socket {
        val r = resolver ?: throw IOException("proxy dihentikan")
        val s = r.connect(host, port, 10000, 20000, java.util.function.BooleanSupplier { client.isClosed })
        live.add(s)
        if (r.isClosed) {   // proxy dihentikan saat sambungan sedang dibuat
            closeQuiet(s)
            throw IOException("proxy dihentikan")
        }
        return s
    }
}
