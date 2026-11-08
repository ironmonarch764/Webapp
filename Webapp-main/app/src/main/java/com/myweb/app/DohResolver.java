package com.myweb.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLException;

/**
 * Resolver DNS-over-HTTPS (format JSON: Cloudflare / Google / kustom) - Java murni, tanpa Android, supaya bisa diuji di JVM.
 *
 * Aturan perilaku (satu aturan, konsisten):
 *  - Jawaban DNS yang VALID dari server pilihan pengguna (termasuk NXDOMAIN, "tidak ada alamat", SERVFAIL/REFUSED, alamat
 *    0.0.0.0 pemblokir) dihormati apa adanya. TIDAK ada pengalihan ke DNS sistem (filter DNS tidak boleh ditembus).
 *  - Hanya kegagalan TRANSPORT (jaringan, TLS, waktu habis, HTTP bukan 200, isi bukan JSON DNS) yang memicu cadangan ke
 *    DNS sistem. Cadangan itu dicatat/diberitahukan lewat Listener (tanpa nama host) dan hasilnya di-cache singkat saja.
 *  - Setelah 3 kegagalan transport beruntun, DoH dilewati 30 dtk (langsung DNS sistem) supaya tiap host tidak menunggu timeout.
 *  - Cache menghormati TTL jawaban (dibatasi 10 dtk .. 5 menit), ada cache negatif singkat, dibatasi 512 entri (LRU).
 *  - IPv4 diutamakan (perilaku lama). IPv6 baru dicari bila IPv4 tidak ada ATAU semua sambungan IPv4 gagal.
 */
public final class DohResolver {

    // ---------- antarmuka ----------
    public interface Transport {
        Reply get(String url, int timeoutMs, int maxBytes) throws IOException;
        void cancelAll();
    }

    public interface SystemDns {
        List<InetAddress> lookup(String host) throws UnknownHostException;
    }

    public interface Listener {
        void onEvent(String message);
    }

    public static final class Reply {
        public final int code;
        public final byte[] body;
        public Reply(int code, byte[] body) { this.code = code; this.body = body; }
    }

    /** Kegagalan transport / format: satu-satunya jenis kegagalan yang boleh memicu DNS sistem. */
    static final class DohFailure extends IOException {
        DohFailure(String m) { super(m); }
    }

    public static final SystemDns SYSTEM = host -> Arrays.asList(InetAddress.getAllByName(host));

    // ---------- konstanta ----------
    static final long MIN_TTL_MS = 10_000L;
    static final long MAX_TTL_MS = 5 * 60_000L;
    static final long NEG_TTL_MS = 30_000L;
    static final long FAIL_TTL_MS = 5_000L;
    static final long FALLBACK_TTL_MS = 30_000L;
    static final long BREAKER_MS = 30_000L;
    static final int BREAKER_FAILS = 3;
    static final int MAX_ENTRIES = 512;
    static final int MAX_BODY = 64 * 1024;

    // ---------- keadaan ----------
    private final String endpoint;
    private final Transport transport;
    private final SystemDns sys;
    private final ExecutorService exec;
    private final Listener listener;
    private final LongSupplier clock;

    int dohTimeoutMs = 5000;
    int sysTimeoutMs = 8000;
    int waitMs = 30000;

    private volatile boolean closed = false;
    private volatile long skipUntil = 0;
    private volatile long lastEventAt = 0;
    private volatile long lastFallbackAt = 0;
    private final AtomicInteger failStreak = new AtomicInteger();
    private final AtomicInteger okCount = new AtomicInteger();
    private final AtomicInteger fallbackCount = new AtomicInteger();
    private final AtomicInteger failCount = new AtomicInteger();

    private static final class CacheEntry {
        final List<InetAddress> addrs;
        final long expires;
        final String err;
        CacheEntry(List<InetAddress> a, long e, String err) { this.addrs = a; this.expires = e; this.err = err; }
        List<InetAddress> result() throws UnknownHostException {
            if (err != null) throw new UnknownHostException(err);
            return addrs;
        }
    }

    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
        new LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> e) { return size() > MAX_ENTRIES; }
        });
    private final Map<String, FutureTask<CacheEntry>> inflight = new ConcurrentHashMap<>();

    DohResolver(String endpoint, Transport t, SystemDns s, ExecutorService exec, Listener l, LongSupplier clock) {
        this.endpoint = endpoint;
        this.transport = t;
        this.sys = s;
        this.exec = exec;
        this.listener = l;
        this.clock = clock;
    }

    public static DohResolver create(String endpoint, ExecutorService exec, Listener l) {
        String err = validateEndpoint(endpoint);
        if (err != null) throw new IllegalArgumentException(err);
        return new DohResolver(endpoint, new UrlTransport(false), SYSTEM, exec, l, System::currentTimeMillis);
    }

    // ---------- validasi endpoint ----------
    /** null = valid; selain itu pesan singkat untuk pengguna. Hanya https; tanpa userinfo/fragment; host sah. */
    public static String validateEndpoint(String url) {
        if (url == null || url.isEmpty()) return "Alamat kosong";
        if (url.length() > 2048) return "Alamat terlalu panjang";
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c == 0x7f) return "Alamat tidak boleh memuat spasi atau karakter kontrol";
        }
        URI u;
        try { u = new URI(url); } catch (URISyntaxException e) { return "Format alamat tidak valid"; }
        if (u.getScheme() == null || !u.getScheme().equalsIgnoreCase("https")) return "Harus diawali https://";
        String host = u.getHost();
        if (host == null || host.isEmpty()) return "Alamat server tidak valid";
        if (u.getRawUserInfo() != null) return "Alamat tidak boleh memuat nama pengguna/sandi";
        if (u.getRawFragment() != null) return "Alamat tidak boleh memuat #";
        int port = u.getPort();
        if (port == 0 || port > 65535) return "Nomor port tidak valid";
        String h = stripBrackets(host);
        if (parseLiteral(h) == null && normalizeHost(h) == null) return "Nama server tidak valid";
        return null;
    }

    // ---------- alamat literal & nama host ----------
    static String stripBrackets(String h) {
        if (h != null && h.length() > 2 && h.charAt(0) == '[' && h.charAt(h.length() - 1) == ']') return h.substring(1, h.length() - 1);
        return h;
    }

    /** IPv4/IPv6 literal ketat (tanpa pernah menyentuh DNS). null bila bukan literal yang sah. */
    static InetAddress parseLiteral(String s) {
        if (s == null) return null;
        try {
            byte[] b = parseV4(s);
            if (b == null) b = parseV6(s);
            return b == null ? null : InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] parseV4(String s) {
        String[] p = s.split("\\.", -1);
        if (p.length != 4) return null;
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            String x = p[i];
            if (x.isEmpty() || x.length() > 3 || (x.length() > 1 && x.charAt(0) == '0')) return null;
            int v = 0;
            for (int k = 0; k < x.length(); k++) {
                char c = x.charAt(k);
                if (c < '0' || c > '9') return null;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return null;
            b[i] = (byte) v;
        }
        return b;
    }

    /** Memecah bagian IPv6 menjadi grup 16-bit; bagian terakhir boleh berupa IPv4 bila lastMayBeV4. */
    private static int[] groups(String part, boolean lastMayBeV4) {
        if (part.isEmpty()) return new int[0];
        String[] t = part.split(":", -1);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            String g = t[i];
            if (g.isEmpty()) return null;
            if (lastMayBeV4 && i == t.length - 1 && g.indexOf('.') >= 0) {
                byte[] v4 = parseV4(g);
                if (v4 == null) return null;
                out.add(((v4[0] & 0xff) << 8) | (v4[1] & 0xff));
                out.add(((v4[2] & 0xff) << 8) | (v4[3] & 0xff));
                continue;
            }
            if (g.length() > 4) return null;
            int v = 0;
            for (int k = 0; k < g.length(); k++) {
                int d = Character.digit(g.charAt(k), 16);
                if (d < 0) return null;
                v = v * 16 + d;
            }
            out.add(v);
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static byte[] parseV6(String s) {
        if (s.indexOf(':') < 0 || s.length() > 45) return null;
        int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) return null;
        int[] head, tail;
        if (dbl < 0) {
            head = groups(s, true);
            tail = new int[0];
            if (head == null || head.length != 8) return null;
        } else {
            head = groups(s.substring(0, dbl), false);
            tail = groups(s.substring(dbl + 2), true);
            if (head == null || tail == null || head.length + tail.length > 7) return null;
        }
        int[] all = new int[8];
        for (int i = 0; i < head.length; i++) all[i] = head[i];
        for (int i = 0; i < tail.length; i++) all[8 - tail.length + i] = tail[i];
        byte[] b = new byte[16];
        for (int i = 0; i < 8; i++) {
            b[i * 2] = (byte) (all[i] >> 8);
            b[i * 2 + 1] = (byte) all[i];
        }
        return b;
    }

    /** Huruf kecil, tanpa titik akhir, IDN -> ASCII, sintaks DNS valid. null bila tidak valid. */
    static String normalizeHost(String raw) {
        if (raw == null) return null;
        String h = raw.trim();
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) return null;
        boolean ascii = true;
        for (int i = 0; i < h.length(); i++) if (h.charAt(i) > 0x7e) { ascii = false; break; }
        if (!ascii) {
            try { h = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED); } catch (IllegalArgumentException e) { return null; }
        }
        h = h.toLowerCase(java.util.Locale.ROOT);
        if (h.length() > 253) return null;
        for (String label : h.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) return null;
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') return null;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
                if (!ok) return null;
            }
        }
        return h;
    }

    private static boolean usable(InetAddress a, int family) {
        if (family == 4 && !(a instanceof Inet4Address)) return false;
        if (family == 6 && !(a instanceof Inet6Address)) return false;
        return !(a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isMulticastAddress());
    }

    // ---------- JSON ketat ----------
    static final class Json {
        private final String s;
        private int i = 0;
        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            Object v = j.val(0);
            j.ws();
            if (j.i != text.length()) throw new IllegalArgumentException("sisa");
            return v;
        }

        private IllegalArgumentException bad() { return new IllegalArgumentException("json"); }
        private void ws() { while (i < s.length()) { char c = s.charAt(i); if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break; } }
        private char peek() { if (i >= s.length()) throw bad(); return s.charAt(i); }
        private void expect(char c) { if (peek() != c) throw bad(); i++; }

        private Object val(int d) {
            if (d > 20) throw bad();
            char c = peek();
            if (c == '{') {
                i++;
                Map<String, Object> m = new HashMap<>();
                ws();
                if (peek() == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, val(d + 1));
                    ws();
                    if (peek() == ',') { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek() == ']') { i++; return l; }
                while (true) {
                    ws();
                    l.add(val(d + 1));
                    ws();
                    if (peek() == ',') { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (i == st) throw bad();
            try { return Double.parseDouble(s.substring(st, i)); } catch (NumberFormatException e) { throw bad(); }
        }

        private String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                i++;
                if (c == '"') return sb.toString();
                if (c < 0x20) throw bad();
                if (c != '\\') { sb.append(c); continue; }
                char e = peek();
                i++;
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        if (i + 4 > s.length()) throw bad();
                        int v = 0;
                        for (int k = 0; k < 4; k++) {
                            int dg = Character.digit(s.charAt(i + k), 16);
                            if (dg < 0) throw bad();
                            v = v * 16 + dg;
                        }
                        i += 4;
                        sb.append((char) v);
                        break;
                    }
                    default: throw bad();
                }
            }
        }
    }

    // ---------- satu jawaban DoH ----------
    private static final class Parsed {
        int status;
        int rawCount;                       // jumlah rekaman bertipe diminta
        final List<InetAddress> addrs = new ArrayList<>();
        long ttlSec = -1;                   // minimum TTL (termasuk CNAME), -1 = tidak ada
    }

    @SuppressWarnings("unchecked")
    private static Parsed parseAnswer(byte[] body, int family) throws DohFailure {
        Object root;
        try {
            root = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new DohFailure("respons bukan JSON DNS");
        }
        if (!(root instanceof Map)) throw new DohFailure("respons bukan JSON DNS");
        Map<String, Object> m = (Map<String, Object>) root;
        Object st = m.get("Status");
        if (!(st instanceof Number)) throw new DohFailure("respons bukan JSON DNS");
        Parsed p = new Parsed();
        p.status = ((Number) st).intValue();
        Object an = m.get("Answer");
        if (an == null) return p;
        if (!(an instanceof List)) throw new DohFailure("respons DNS tidak valid");
        int want = family == 4 ? 1 : 28;
        int n = 0;
        for (Object o : (List<Object>) an) {
            if (++n > 64) break;
            if (!(o instanceof Map)) throw new DohFailure("respons DNS tidak valid");
            Map<String, Object> r = (Map<String, Object>) o;
            Object ty = r.get("type");
            if (!(ty instanceof Number)) continue;
            int type = ((Number) ty).intValue();
            if (type != want && type != 5) continue;
            Object ttl = r.get("TTL");
            if (ttl instanceof Number) {
                long t = Math.max(0L, ((Number) ttl).longValue());
                p.ttlSec = p.ttlSec < 0 ? t : Math.min(p.ttlSec, t);
            }
            if (type != want) continue;
            Object data = r.get("data");
            InetAddress a = data instanceof String ? parseLiteral((String) data) : null;
            if (a == null) throw new DohFailure("alamat tidak valid pada respons");
            p.rawCount++;
            if (usable(a, family) && p.addrs.size() < 8) p.addrs.add(a);
        }
        return p;
    }

    private Parsed rawQuery(String host, int family) throws DohFailure {
        String url;
        try {
            url = endpoint + (endpoint.indexOf('?') >= 0 ? "&" : "?") + "name=" + URLEncoder.encode(host, "UTF-8") +
                "&type=" + (family == 4 ? "A" : "AAAA");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new DohFailure("encoding");
        }
        Reply r;
        try {
            r = transport.get(url, dohTimeoutMs, MAX_BODY);
        } catch (DohFailure f) {
            throw f;
        } catch (IOException e) {
            throw new DohFailure(describe(e));
        }
        if (r.code != 200) throw new DohFailure("HTTP " + r.code);
        return parseAnswer(r.body, family);
    }

    static String describe(IOException e) {
        if (e instanceof SocketTimeoutException) return "waktu habis";
        if (e instanceof SSLException) return "koneksi aman (TLS) gagal";
        if (e instanceof UnknownHostException) return "alamat server DoH tidak ditemukan";
        if (e instanceof ConnectException) return "tidak bisa tersambung";
        return "jaringan: " + e.getClass().getSimpleName();
    }

    /** Uji server DoH (tanpa cache/cadangan). null = berfungsi; selain itu alasan singkat. */
    public String probe() {
        try {
            Parsed p = rawQuery("example.com", 4);
            if (p.status != 0) return "server DNS menjawab kode " + p.status;
            return null;
        } catch (DohFailure f) {
            return f.getMessage();
        }
    }

    // ---------- pencarian ----------
    private long now() { return clock.getAsLong(); }

    private CacheEntry cacheGet(String key) {
        CacheEntry e = cache.get(key);
        if (e == null) return null;
        if (e.expires > now()) return e;
        cache.remove(key);
        return null;
    }

    private void put(String key, List<InetAddress> addrs, String err, long ttlMs) {
        if (closed) return;
        cache.put(key, new CacheEntry(addrs, now() + ttlMs, err));
    }

    private static long clampTtl(long ttlSec) {
        long ms = ttlSec < 0 ? MIN_TTL_MS : ttlSec * 1000L;
        return Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, ms));
    }

    /** Alamat untuk satu keluarga (4 atau 6). UnknownHostException = tidak ada hasil / gagal. */
    public List<InetAddress> lookup(String rawHost, int family) throws UnknownHostException {
        if (closed) throw new UnknownHostException("proxy dihentikan");
        String h0 = stripBrackets(rawHost);
        InetAddress lit = parseLiteral(h0);
        if (lit != null) {
            if (usable(lit, family) || lit.isLoopbackAddress() || lit.isAnyLocalAddress()) {
                if ((family == 4) == (lit instanceof Inet4Address)) return Collections.singletonList(lit);
            }
            throw new UnknownHostException("bukan alamat IPv" + family);
        }
        final String host = normalizeHost(h0);
        if (host == null) throw new UnknownHostException("nama host tidak valid");
        final String key = family + "|" + host;
        CacheEntry e = cacheGet(key);
        if (e != null) return e.result();
        FutureTask<CacheEntry> mine = new FutureTask<>(() -> fetch(host, family));
        FutureTask<CacheEntry> prev = inflight.putIfAbsent(key, mine);
        FutureTask<CacheEntry> t = prev != null ? prev : mine;
        if (prev == null) {
            try { mine.run(); } finally { inflight.remove(key, mine); }
        }
        try {
            return t.get(waitMs, TimeUnit.MILLISECONDS).result();
        } catch (ExecutionException ex) {
            throw new UnknownHostException("pencarian gagal");
        } catch (TimeoutException ex) {
            throw new UnknownHostException("pencarian terlalu lama");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UnknownHostException("pencarian dibatalkan");
        }
    }

    private CacheEntry fetch(String host, int family) {
        String key = family + "|" + host;
        if (now() < skipUntil) return viaSystem(host, family, "DoH dilewati sementara");
        try {
            Parsed p = rawQuery(host, family);
            failStreak.set(0);
            okCount.incrementAndGet();
            if (p.status == 0 && !p.addrs.isEmpty()) {
                CacheEntry e = new CacheEntry(p.addrs, now() + clampTtl(p.ttlSec), null);
                if (!closed) cache.put(key, e);
                return e;
            }
            if (p.status == 0) {
                String msg = p.rawCount > 0 ? "diblokir oleh server DNS" : "tidak ada alamat IPv" + family;
                put(key, null, msg, NEG_TTL_MS);
                return new CacheEntry(null, 0, msg);
            }
            if (p.status == 3) {
                String msg = "domain tidak ditemukan";
                put("4|" + host, null, msg, NEG_TTL_MS);
                put("6|" + host, null, msg, NEG_TTL_MS);
                return new CacheEntry(null, 0, msg);
            }
            String msg = "server DNS menjawab kode " + p.status;
            put(key, null, msg, FAIL_TTL_MS);
            return new CacheEntry(null, 0, msg);
        } catch (DohFailure f) {
            if (closed) return new CacheEntry(null, 0, "proxy dihentikan");
            failCount.incrementAndGet();
            if (failStreak.incrementAndGet() >= BREAKER_FAILS) skipUntil = now() + BREAKER_MS;
            return viaSystem(host, family, f.getMessage());
        }
    }

    private CacheEntry viaSystem(String host, int family, String reason) {
        fallbackCount.incrementAndGet();
        long t = now();
        lastFallbackAt = t;
        if (t - lastEventAt > 30_000L || lastEventAt == 0) {
            lastEventAt = t;
            try { if (listener != null) listener.onEvent("DoH gagal (" + reason + "), memakai DNS sistem"); } catch (RuntimeException ignored) { }
        }
        if (closed) return new CacheEntry(null, 0, "proxy dihentikan");
        Future<List<InetAddress>> f = exec.submit(() -> sys.lookup(host));
        try {
            List<InetAddress> all = f.get(sysTimeoutMs, TimeUnit.MILLISECONDS);
            List<InetAddress> v4 = new ArrayList<>(), v6 = new ArrayList<>();
            for (InetAddress a : all) {
                if (a instanceof Inet4Address) { if (v4.size() < 8) v4.add(a); }
                else if (v6.size() < 8) v6.add(a);
            }
            put("4|" + host, v4.isEmpty() ? null : v4, v4.isEmpty() ? "tidak ada alamat IPv4" : null, FALLBACK_TTL_MS);
            put("6|" + host, v6.isEmpty() ? null : v6, v6.isEmpty() ? "tidak ada alamat IPv6" : null, FALLBACK_TTL_MS);
            List<InetAddress> mine = family == 4 ? v4 : v6;
            return mine.isEmpty() ? new CacheEntry(null, 0, "tidak ada alamat IPv" + family) : new CacheEntry(mine, 0, null);
        } catch (TimeoutException e) {
            f.cancel(true);
            return failBoth(host, "DNS sistem waktu habis");
        } catch (ExecutionException e) {
            return failBoth(host, "domain tidak ditemukan");
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            return new CacheEntry(null, 0, "dibatalkan");
        }
    }

    private CacheEntry failBoth(String host, String msg) {
        put("4|" + host, null, msg, FAIL_TTL_MS);
        put("6|" + host, null, msg, FAIL_TTL_MS);
        return new CacheEntry(null, 0, msg);
    }

    // ---------- sambungan ----------
    private static Socket attempt(InetAddress a, int port, int ms) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(a, port), ms);
            s.setTcpNoDelay(true);
            return s;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    /**
     * Sambung ke host:port. IPv4 dicoba dulu (maks 4 alamat), baru IPv6 (maks 4) bila IPv4 tidak ada / semua gagal.
     * Batas total totalMs; berhenti segera bila resolver ditutup atau cancelled bernilai true.
     */
    public Socket connect(String rawHost, int port, int perMs, int totalMs, BooleanSupplier cancelled) throws IOException {
        String h = stripBrackets(rawHost);
        long deadline = System.nanoTime() + totalMs * 1_000_000L;
        InetAddress lit = parseLiteral(h);
        if (lit != null) return attempt(lit, port, perMs);
        IOException last = null;
        for (int fam = 4; fam <= 6; fam += 2) {
            List<InetAddress> addrs;
            try {
                addrs = lookup(h, fam);
            } catch (UnknownHostException e) {
                if (last == null) last = e;
                continue;
            }
            int n = 0;
            for (InetAddress a : addrs) {
                if (n++ >= 4) break;
                if (closed) throw new IOException("proxy dihentikan");
                if (cancelled != null && cancelled.getAsBoolean()) throw new IOException("klien memutus");
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) throw last != null ? last : new SocketTimeoutException("batas waktu sambungan");
                try {
                    return attempt(a, port, (int) Math.min(perMs, left));
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        throw last != null ? last : new UnknownHostException(h);
    }

    // ---------- kontrol ----------
    public void close() {
        closed = true;
        transport.cancelAll();
        cache.clear();
    }

    public boolean isClosed() { return closed; }
    public long lastFallbackAt() { return lastFallbackAt; }
    int cacheSize() { return cache.size(); }

    public String stats() {
        return "DoH ok=" + okCount.get() + ", gagal=" + failCount.get() + ", cadangan sistem=" + fallbackCount.get() +
            ", cache=" + cache.size();
    }

    // ---------- transport HTTPS nyata ----------
    /** HttpURLConnection standar (validasi sertifikat TLS bawaan sistem tidak diubah). allowPlain hanya untuk uji lokal. */
    public static final class UrlTransport implements Transport {
        private final boolean allowPlain;
        private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();

        public UrlTransport(boolean allowPlain) { this.allowPlain = allowPlain; }

        @Override
        public Reply get(String url, int timeoutMs, int maxBytes) throws IOException {
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol()) && !allowPlain) throw new IOException("bukan https");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/dns-json");
            active.add(c);
            boolean ok = false;
            try {
                int code = c.getResponseCode();
                if (code != 200) return new Reply(code, new byte[0]);
                long deadline = System.nanoTime() + 2L * timeoutMs * 1_000_000L;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        bo.write(buf, 0, n);
                        if (bo.size() > maxBytes) throw new IOException("respons terlalu besar");
                        if (System.nanoTime() > deadline) throw new SocketTimeoutException("terlalu lambat");
                    }
                }
                ok = true;
                return new Reply(code, bo.toByteArray());
            } finally {
                active.remove(c);
                if (!ok) c.disconnect();
            }
        }

        @Override
        public void cancelAll() {
            for (HttpURLConnection c : active) {
                try { c.disconnect(); } catch (RuntimeException ignored) { }
            }
        }
    }
}
