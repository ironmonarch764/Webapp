package com.myweb.app

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Transport DoH berbasis OkHttp: mendukung HTTP/2 (dengan HTTP/1.1 sebagai cadangan) dan memakai ulang sambungan.
// Diperlukan karena HttpURLConnection bawaan Android hanya bisa HTTP/1.1, sedangkan Quad9 sejak 15 Desember 2025
// tidak lagi menerima DoH lewat HTTP/1.1. Tiap instance punya daftar panggilan sendiri (untuk cancelAll), tetapi
// klien dan kolam sambungannya dipakai bersama.
class OkDohTransport : DohResolver.Transport {

    companion object {
        private val base: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private val active: MutableSet<Call> = ConcurrentHashMap.newKeySet<Call>()

    @Volatile
    private var lastProtocol: String = ""

    override fun get(url: String, timeoutMs: Int, maxBytes: Int): DohResolver.Reply {
        return get(url, "application/dns-json", timeoutMs, maxBytes)
    }

    override fun get(url: String, accept: String, timeoutMs: Int, maxBytes: Int): DohResolver.Reply {
        if (!url.startsWith("https://", ignoreCase = true)) throw IOException("bukan https")
        val req: Request = try {
            Request.Builder().url(url).header("Accept", accept).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("alamat tidak valid")
        }
        val client = base.newBuilder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(2L * timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(req)
        active.add(call)
        try {
            val reply: DohResolver.Reply = call.execute().use { resp ->
                lastProtocol = resp.protocol.toString()
                val body = resp.body
                if (resp.code != 200 || body == null) {
                    DohResolver.Reply(resp.code, ByteArray(0))
                } else {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    body.byteStream().use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > maxBytes) throw IOException("respons terlalu besar")
                        }
                    }
                    DohResolver.Reply(resp.code, out.toByteArray())
                }
            }
            return reply
        } catch (e: SocketTimeoutException) {
            throw e
        } catch (e: InterruptedIOException) {
            // batas waktu panggilan keseluruhan OkHttp: laporkan sebagai waktu habis
            throw SocketTimeoutException("waktu habis")
        } finally {
            active.remove(call)
        }
    }

    override fun info(): String = lastProtocol

    override fun cancelAll() {
        for (c in active) {
            try {
                c.cancel()
            } catch (e: RuntimeException) {
            }
        }
    }
}
