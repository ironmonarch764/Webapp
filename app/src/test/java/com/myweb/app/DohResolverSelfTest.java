package com.myweb.app;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uji DohResolver di JVM (tanpa Android): validasi endpoint, literal IP, JSON ketat, TTL/cache, NXDOMAIN, kegagalan
 * transport + cadangan, pemutus sirkuit, singleflight, batas cache, penutupan, soket nyata, dan HTTP nyata (server lokal).
 * Jalankan: javac ... && java -cp ... com.myweb.app.DohResolverSelfTest  (juga dijalankan oleh build.yml).
 */
public final class DohResolverSelfTest {
    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) pass++;
        else { fail++; System.out.println("GAGAL: " + name); }
    }

    // ---------- alat bantu ----------
    interface Script { DohResolver.Reply reply(String url) throws IOException; }

    static final class FakeTransport implements DohResolver.Transport {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final List<String> urls = Collections.synchronizedList(new ArrayList<String>());
        volatile Script script;
        FakeTransport(Script s) { script = s; }
        public DohResolver.Reply get(String url, int t, int max) throws IOException {
            calls.incrementAndGet();
            urls.add(url);
            return script.reply(url);
        }
        public void cancelAll() { cancels.incrementAndGet(); }
    }

    static final class FakeSys implements DohResolver.SystemDns {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> hosts = Collections.synchronizedList(new ArrayList<String>());
        volatile List<InetAddress> result;
        volatile long delayMs = 0;
        FakeSys(List<InetAddress> r) { result = r; }
        public List<InetAddress> lookup(String host) throws UnknownHostException {
            calls.incrementAndGet();
            hosts.add(host);
            if (delayMs > 0) { try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            if (result == null) throw new UnknownHostException(host);
            return result;
        }
    }

    static final class Events implements DohResolver.Listener {
        final List<String> msgs = Collections.synchronizedList(new ArrayList<String>());
        public void onEvent(String m) { msgs.add(m); }
    }

    static InetAddress ip(String s) {
        try { return InetAddress.getByName(s); } catch (UnknownHostException e) { throw new RuntimeException(e); }
    }

    static DohResolver.Reply json(String body) { return new DohResolver.Reply(200, body.getBytes(StandardCharsets.UTF_8)); }

    static String aAnswer(int status, String... ipTtl) {
        StringBuilder sb = new StringBuilder("{\"Status\":" + status);
        if (ipTtl.length > 0) {
            sb.append(",\"Answer\":[");
            for (int i = 0; i < ipTtl.length; i += 2) {
                if (i > 0) sb.append(',');
                String d = ipTtl[i];
                int type = d.indexOf(':') >= 0 ? 28 : 1;
                sb.append("{\"name\":\"x\",\"type\":" + type + ",\"TTL\":" + ipTtl[i + 1] + ",\"data\":\"" + d + "\"}");
            }
            sb.append("]");
        }
        return sb.append("}").toString();
    }

    static String typeOf(String url) { return url.contains("type=AAAA") ? "AAAA" : "A"; }

    static final class Clock implements java.util.function.LongSupplier {
        final AtomicLong t = new AtomicLong(1_000_000L);
        public long getAsLong() { return t.get(); }
    }

    static ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });

    static DohResolver mk(FakeTransport t, FakeSys s, Events ev, Clock c) {
        return new DohResolver("https://doh.test/dns-query", t, s, pool, ev, c);
    }

    static boolean throwsUnknown(DohResolver r, String h, int fam) {
        try { r.lookup(h, fam); return false; } catch (UnknownHostException e) { return true; }
    }

    // ---------- main ----------
    public static void main(String[] a) throws Exception {
        endpointTests();
        literalTests();
        hostTests();
        jsonTests();
        successAndTtlTests();
        negativeTests();
        failureAndFallbackTests();
        breakerTests();
        invalidAnswerTests();
        lifecycleTests();
        concurrencyTests();
        connectTests();
        httpTransportTests();
        System.out.println("DohResolverSelfTest: " + pass + " lulus, " + fail + " gagal");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void endpointTests() {
        String[] ok = {"https://1.1.1.1/dns-query", "https://8.8.8.8/resolve", "https://dns.example.com/dns-query",
            "https://dns.example.com:8443/dns-query?ct=application/dns-json", "https://[2606:4700:4700::1111]/dns-query", "HTTPS://dns.example.com/x"};
        for (String s : ok) check("endpoint sah: " + s, DohResolver.validateEndpoint(s) == null);
        String[] bad = {null, "", "http://1.1.1.1/dns-query", "ftp://x.y/z", "https://", "https:///dns-query", "https://a b.com/x",
            "https://user:pw@dns.example.com/x", "https://dns.example.com/x#frag", "https://dns.example.com:0/x",
            "https://dns.example.com:99999/x", "dns.example.com/dns-query", "https://-bad-.com/x", "https://exa\tmple.com/x",
            "https://dns.example.com/" + "a".repeat(2100), "javascript:alert(1)", "https://.com/x", "https://a..b/x"};
        for (String s : bad) check("endpoint ditolak: " + (s == null ? "null" : s.length() > 40 ? s.substring(0, 40) : s), DohResolver.validateEndpoint(s) != null);
        boolean threw = false;
        try { DohResolver.create("http://x.y/z", pool, null); } catch (IllegalArgumentException e) { threw = true; }
        check("create menolak http", threw);
    }

    static void literalTests() {
        check("v4 sah", DohResolver.parseLiteral("1.2.3.4") != null);
        check("v4 255", DohResolver.parseLiteral("255.255.255.255") != null);
        String[] bad4 = {"256.1.1.1", "1.2.3", "1.2.3.4.5", "01.2.3.4", "1.2.3.", "a.b.c.d", "", "1..3.4", "1.2.3.-4", "example.com"};
        for (String s : bad4) check("v4 ditolak: " + s, DohResolver.parseLiteral(s) == null);
        String[] ok6 = {"::1", "::", "2606:4700:4700::1111", "fe80::1", "1:2:3:4:5:6:7:8", "::ffff:1.2.3.4", "1::", "64:ff9b::1.2.3.4"};
        for (String s : ok6) check("v6 sah: " + s, DohResolver.parseLiteral(s) != null);
        String[] bad6 = {":", ":::", "1::2::3", "12345::1", "g::1", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:",
            "::1.2.3", "fe80::1%eth0", "abc:", "1.2.3.4::1", "::1:2:3:4:5:6:7:8"};
        for (String s : bad6) check("v6 ditolak: " + s, DohResolver.parseLiteral(s) == null);
        check("v6 nilai benar", Arrays.equals(DohResolver.parseLiteral("2001:db8::1").getAddress(),
            new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}));
        check("v4 nilai benar", DohResolver.parseLiteral("10.0.0.7").getHostAddress().equals("10.0.0.7"));
        check("kurung dilepas", DohResolver.stripBrackets("[::1]").equals("::1"));
    }

    static void hostTests() {
        check("host huruf kecil", "example.com".equals(DohResolver.normalizeHost("ExAmPle.COM")));
        check("titik akhir dibuang", "example.com".equals(DohResolver.normalizeHost("example.com.")));
        check("IDN -> punycode", "xn--mnchen-3ya.de".equals(DohResolver.normalizeHost("m\u00fcnchen.de")));
        check("underscore boleh", DohResolver.normalizeHost("_dmarc.example.com") != null);
        String[] bad = {"", ".", "a..b", "-a.com", "a-.com", "a b.com", "a&b=c.com", "a/b.com", "a#b.com", "a?b.com", "a".repeat(64) + ".com",
            ("a".repeat(60) + ".").repeat(5) + "com"};
        for (String s : bad) check("host ditolak: " + (s.length() > 30 ? s.substring(0, 30) : s), DohResolver.normalizeHost(s) == null);
    }

    static void jsonTests() throws Exception {
        FakeTransport t = new FakeTransport(u -> json("{}"));
        DohResolver r = mk(t, new FakeSys(null), new Events(), new Clock());
        String[] garbage = {"", "<html>login</html>", "{", "{\"Status\":0", "[]", "null", "{\"Status\":\"0\"}", "{\"x\":1}", "{\"Status\":0} trailing",
            "{'Status':0}", "{\"Status\":0,}", "{\"Status\":0,\"Answer\":5}", "{\"Status\":0,\"Answer\":[1]}", "\u0000\u0001"};
        FakeSys sys = new FakeSys(null);
        int i = 0;
        for (String g : garbage) {
            final String body = g;
            FakeTransport tt = new FakeTransport(u -> json(body));
            DohResolver rr = mk(tt, sys, new Events(), new Clock());
            check("JSON/isi tidak valid -> bukan jawaban: " + (g.length() > 25 ? g.substring(0, 25) : g), rr.probe() != null);
            i++;
        }
        check("probe sukses", mk(new FakeTransport(u -> json(aAnswer(0, "93.184.216.34", "60"))), sys, null, new Clock()).probe() == null);
        check("probe NXDOMAIN dilaporkan", mk(new FakeTransport(u -> json(aAnswer(3))), sys, null, new Clock()).probe() != null);
        check("probe HTTP 403", "HTTP 403".equals(mk(new FakeTransport(u -> new DohResolver.Reply(403, new byte[0])), sys, null, new Clock()).probe()));
        check("probe waktu habis", "waktu habis".equals(mk(new FakeTransport(u -> { throw new java.net.SocketTimeoutException("x"); }), sys, null, new Clock()).probe()));
        check("probe TLS", "koneksi aman (TLS) gagal".equals(mk(new FakeTransport(u -> { throw new javax.net.ssl.SSLHandshakeException("x"); }), sys, null, new Clock()).probe()));
        check("probe tanpa alamat server", mk(new FakeTransport(u -> { throw new UnknownHostException("x"); }), sys, null, new Clock()).probe() != null);
        // escape JSON yang sah tetap diterima
        FakeTransport te = new FakeTransport(u -> json("{ \"Status\" : 0 , \"Comment\":\"a\\n\\u00e9\\\"\", \"Answer\" : [ {\"type\":1,\"TTL\":30,\"data\":\"1.2.3.4\"} ] }"));
        DohResolver re = mk(te, sys, null, new Clock());
        check("JSON sah dengan spasi/escape", re.probe() == null);
    }

    static void successAndTtlTests() throws Exception {
        Clock c = new Clock();
        FakeTransport t = new FakeTransport(u -> typeOf(u).equals("A") ? json(aAnswer(0, "93.184.216.34", "120")) : json(aAnswer(0)));
        FakeSys sys = new FakeSys(null);
        DohResolver r = mk(t, sys, new Events(), c);
        List<InetAddress> l = r.lookup("Example.COM", 4);
        check("A berhasil", l.size() == 1 && l.get(0).getHostAddress().equals("93.184.216.34"));
        check("host di URL DoH huruf kecil & terenkripsi", t.urls.get(0).contains("name=example.com&type=A"));
        check("tanpa DNS sistem saat DoH berhasil", sys.calls.get() == 0);
        r.lookup("example.com", 4);
        check("cache dipakai (tidak ada query kedua)", t.calls.get() == 1);
        c.t.addAndGet(119_000);
        r.lookup("example.com", 4);
        check("masih valid tepat sebelum TTL 120 dtk", t.calls.get() == 1);
        c.t.addAndGet(2_000);
        r.lookup("example.com", 4);
        check("kedaluwarsa setelah TTL -> query ulang", t.calls.get() == 2);

        // TTL kecil dinaikkan ke lantai 10 dtk; TTL besar dibatasi 5 menit
        Clock c2 = new Clock();
        FakeTransport t2 = new FakeTransport(u -> json(aAnswer(0, "93.184.216.34", "1")));
        DohResolver r2 = mk(t2, sys, null, c2);
        r2.lookup("a.test", 4);
        c2.t.addAndGet(9_000);
        r2.lookup("a.test", 4);
        check("TTL 1 dtk: lantai 10 dtk (belum query ulang di 9 dtk)", t2.calls.get() == 1);
        c2.t.addAndGet(2_000);
        r2.lookup("a.test", 4);
        check("TTL 1 dtk: query ulang setelah 10 dtk", t2.calls.get() == 2);
        Clock c3 = new Clock();
        FakeTransport t3 = new FakeTransport(u -> json(aAnswer(0, "93.184.216.34", "86400")));
        DohResolver r3 = mk(t3, sys, null, c3);
        r3.lookup("b.test", 4);
        c3.t.addAndGet(5 * 60_000 + 1000);
        r3.lookup("b.test", 4);
        check("TTL 1 hari dibatasi 5 menit", t3.calls.get() == 2);
        // TTL minimum dari beberapa rekaman (+ CNAME)
        Clock c4 = new Clock();
        FakeTransport t4 = new FakeTransport(u -> json("{\"Status\":0,\"Answer\":[{\"type\":5,\"TTL\":20,\"data\":\"x.test.\"},{\"type\":1,\"TTL\":200,\"data\":\"93.184.216.34\"},{\"type\":1,\"TTL\":300,\"data\":\"93.184.216.35\"}]}"));
        DohResolver r4 = mk(t4, sys, null, c4);
        check("dua alamat A diterima (CNAME diabaikan sebagai alamat)", r4.lookup("c.test", 4).size() == 2);
        c4.t.addAndGet(21_000);
        r4.lookup("c.test", 4);
        check("TTL = minimum termasuk CNAME", t4.calls.get() == 2);
        // IPv6 hanya bila diminta
        FakeTransport t5 = new FakeTransport(u -> typeOf(u).equals("A") ? json(aAnswer(0)) : json(aAnswer(0, "2606:4700::6810:85e5", "60")));
        DohResolver r5 = mk(t5, sys, null, new Clock());
        check("A kosong -> UnknownHost (belum ada AAAA dicari)", throwsUnknown(r5, "v6only.test", 4) && t5.calls.get() == 1);
        List<InetAddress> v6 = r5.lookup("v6only.test", 6);
        check("AAAA sah", v6.size() == 1 && v6.get(0) instanceof java.net.Inet6Address);
        check("tidak pernah memakai DNS sistem", sys.calls.get() == 0);
    }

    static void negativeTests() throws Exception {
        Clock c = new Clock();
        FakeTransport t = new FakeTransport(u -> json(aAnswer(3)));
        FakeSys sys = new FakeSys(Collections.singletonList(ip("203.0.113.9")));
        Events ev = new Events();
        DohResolver r = mk(t, sys, ev, c);
        check("NXDOMAIN -> UnknownHost", throwsUnknown(r, "nope.test", 4));
        check("NXDOMAIN TIDAK beralih ke DNS sistem", sys.calls.get() == 0 && ev.msgs.isEmpty());
        check("NXDOMAIN: AAAA tidak ditanyakan lagi (cache negatif keluarga lain)", throwsUnknown(r, "nope.test", 6) && t.calls.get() == 1);
        check("cache negatif", throwsUnknown(r, "nope.test", 4) && t.calls.get() == 1);
        c.t.addAndGet(31_000);
        check("cache negatif kedaluwarsa 30 dtk", throwsUnknown(r, "nope.test", 4) && t.calls.get() == 2);

        // jawaban kosong (NODATA) tidak diperlakukan sebagai kegagalan
        FakeTransport tn = new FakeTransport(u -> json(aAnswer(0)));
        DohResolver rn = mk(tn, sys, ev, new Clock());
        check("NODATA -> UnknownHost tanpa DNS sistem", throwsUnknown(rn, "nodata.test", 4) && sys.calls.get() == 0);

        // SERVFAIL / REFUSED = jawaban sah dari server pilihan -> dihormati, tanpa cadangan
        for (int st : new int[]{1, 2, 4, 5}) {
            FakeTransport ts = new FakeTransport(u -> json(aAnswer(st)));
            DohResolver rs = mk(ts, sys, ev, new Clock());
            check("status " + st + " dihormati tanpa DNS sistem", throwsUnknown(rs, "x.test", 4) && sys.calls.get() == 0);
        }

        // alamat pemblokir 0.0.0.0 / :: / loopback = diblokir, bukan cadangan, bukan dihubungi
        for (String bad : new String[]{"0.0.0.0", "127.0.0.1", "224.0.0.1"}) {
            FakeTransport tb = new FakeTransport(u -> json(aAnswer(0, bad, "60")));
            DohResolver rb = mk(tb, sys, ev, new Clock());
            check("jawaban " + bad + " diperlakukan diblokir", throwsUnknown(rb, "ads.test", 4) && sys.calls.get() == 0);
        }
        FakeTransport tb6 = new FakeTransport(u -> json(aAnswer(0, "::", "60")));
        check("jawaban :: diblokir", throwsUnknown(mk(tb6, sys, ev, new Clock()), "ads.test", 6));
        FakeTransport tmix = new FakeTransport(u -> json(aAnswer(0, "0.0.0.0", "60", "93.184.216.34", "60")));
        check("alamat sah di antara 0.0.0.0 tetap dipakai",
            mk(tmix, sys, ev, new Clock()).lookup("mix.test", 4).size() == 1);
        check("nama tidak valid ditolak tanpa query", throwsUnknown(r, "bad name!.test", 4) && t.calls.get() == 2);
        check("literal bukan keluarga yang diminta ditolak", throwsUnknown(r, "1.2.3.4", 6));
        check("literal v4 lewat lookup", r.lookup("1.2.3.4", 4).get(0).getHostAddress().equals("1.2.3.4") && t.calls.get() == 2);
    }

    static void failureAndFallbackTests() throws Exception {
        List<Script> failures = new ArrayList<>();
        failures.add(u -> new DohResolver.Reply(500, new byte[0]));
        failures.add(u -> new DohResolver.Reply(302, new byte[0]));
        failures.add(u -> { throw new java.net.SocketTimeoutException("t"); });
        failures.add(u -> { throw new java.net.ConnectException("refused"); });
        failures.add(u -> { throw new javax.net.ssl.SSLHandshakeException("bad cert"); });
        failures.add(u -> json("<html>captive portal</html>"));
        failures.add(u -> json("{\"Status\":0,\"Answer\":[{\"type\":1,\"TTL\":5,\"data\":\"not-an-ip.example\"}]}"));
        int k = 0;
        for (Script s : failures) {
            Clock c = new Clock();
            FakeTransport t = new FakeTransport(s);
            FakeSys sys = new FakeSys(Collections.singletonList(ip("203.0.113.9")));
            Events ev = new Events();
            DohResolver r = mk(t, sys, ev, c);
            List<InetAddress> l = r.lookup("site.test", 4);
            check("kegagalan #" + k + " -> DNS sistem dipakai", l.size() == 1 && l.get(0).getHostAddress().equals("203.0.113.9") && sys.calls.get() == 1);
            check("kegagalan #" + k + " -> pemberitahuan tanpa nama host", ev.msgs.size() == 1 && !ev.msgs.get(0).contains("site.test") && ev.msgs.get(0).contains("DNS sistem"));
            r.lookup("site.test", 4);
            check("hasil cadangan di-cache singkat (tidak query ulang)", sys.calls.get() == 1 && t.calls.get() == 1);
            check("fallback hanya IPv4 -> keluarga 6 tidak ada hasil", throwsUnknown(r, "site.test", 6));
            c.t.addAndGet(31_000);
            r.lookup("site.test", 4);
            check("setelah 30 dtk DoH dicoba lagi", t.calls.get() == 2);
            k++;
        }
        // satu pemberitahuan per 30 dtk walau banyak host gagal
        Clock c = new Clock();
        FakeTransport t = new FakeTransport(u -> new DohResolver.Reply(500, new byte[0]));
        FakeSys sys = new FakeSys(Collections.singletonList(ip("203.0.113.9")));
        Events ev = new Events();
        DohResolver r = mk(t, sys, ev, c);
        r.lookup("a1.test", 4);
        r.lookup("a2.test", 4);
        check("pemberitahuan dibatasi (1 per 30 dtk)", ev.msgs.size() == 1);
        c.t.addAndGet(31_000);
        r.lookup("a3.test", 4);
        check("pemberitahuan lagi setelah 30 dtk", ev.msgs.size() == 2);
        check("lastFallbackAt terisi", r.lastFallbackAt() > 0);

        // DNS sistem juga gagal -> UnknownHost, hasil gagal di-cache 5 dtk
        Clock c2 = new Clock();
        FakeTransport t2 = new FakeTransport(u -> { throw new java.net.ConnectException("x"); });
        FakeSys sys2 = new FakeSys(null);
        DohResolver r2 = mk(t2, sys2, null, c2);
        check("DoH + sistem gagal -> UnknownHost", throwsUnknown(r2, "dead.test", 4));
        check("kegagalan beruntun di-cache sebentar", throwsUnknown(r2, "dead.test", 4) && sys2.calls.get() == 1);
        c2.t.addAndGet(6_000);
        check("setelah 5 dtk dicoba lagi", throwsUnknown(r2, "dead.test", 4) && sys2.calls.get() == 2);

        // DNS sistem menggantung -> dibatasi waktu
        FakeTransport t3 = new FakeTransport(u -> { throw new java.net.ConnectException("x"); });
        FakeSys sys3 = new FakeSys(Collections.singletonList(ip("203.0.113.9")));
        sys3.delayMs = 3000;
        DohResolver r3 = mk(t3, sys3, null, new Clock());
        r3.sysTimeoutMs = 200;
        long t0 = System.nanoTime();
        boolean thr = throwsUnknown(r3, "slow.test", 4);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("DNS sistem menggantung -> UnknownHost cepat (" + ms + " ms)", thr && ms < 1500);
    }

    static void breakerTests() throws Exception {
        Clock c = new Clock();
        FakeTransport t = new FakeTransport(u -> { throw new java.net.SocketTimeoutException("t"); });
        FakeSys sys = new FakeSys(Collections.singletonList(ip("203.0.113.9")));
        DohResolver r = mk(t, sys, new Events(), c);
        for (int i = 0; i < 3; i++) r.lookup("h" + i + ".test", 4);
        check("3 kegagalan beruntun memanggil DoH 3 kali", t.calls.get() == 3);
        r.lookup("h3.test", 4);
        check("pemutus sirkuit: host berikut langsung ke DNS sistem (tanpa menunggu timeout)", t.calls.get() == 3 && sys.calls.get() == 4);
        c.t.addAndGet(31_000);
        t.script = u -> json(aAnswer(0, "93.184.216.34", "60"));
        List<InetAddress> l = r.lookup("h4.test", 4);
        check("setelah jeda 30 dtk DoH dicoba lagi dan pulih", t.calls.get() == 4 && l.get(0).getHostAddress().equals("93.184.216.34"));
        t.script = u -> { throw new java.net.SocketTimeoutException("t"); };
        r.lookup("h5.test", 4);
        r.lookup("h6.test", 4);
        t.script = u -> json(aAnswer(0, "93.184.216.34", "60"));
        r.lookup("h7.test", 4);
        r.lookup("h8.test", 4);
        t.script = u -> { throw new java.net.SocketTimeoutException("t"); };
        r.lookup("h9.test", 4);
        check("keberhasilan mereset hitungan kegagalan (tidak langsung terputus)", t.calls.get() == 9);
    }

    static void invalidAnswerTests() throws Exception {
        FakeSys sys = new FakeSys(null);
        // data bukan literal IP tidak boleh pernah dikirim ke resolver sistem sebagai nama (kebocoran DNS)
        FakeTransport t = new FakeTransport(u -> json("{\"Status\":0,\"Answer\":[{\"type\":1,\"TTL\":5,\"data\":\"evil.example.com\"}]}"));
        DohResolver r = mk(t, sys, null, new Clock());
        throwsUnknown(r, "victim.test", 4);
        check("data non-IP tidak diperlakukan sebagai nama", !sys.hosts.contains("evil.example.com") && sys.hosts.contains("victim.test"));
        // jawaban IPv4 pada permintaan AAAA ditolak
        FakeTransport t2 = new FakeTransport(u -> json(aAnswer(0, "93.184.216.34", "60")));
        DohResolver r2 = mk(t2, new FakeSys(null), null, new Clock());
        check("rekaman A pada keluarga 6 diabaikan -> tidak ada hasil", throwsUnknown(r2, "m.test", 6));
    }

    static void lifecycleTests() throws Exception {
        FakeTransport t = new FakeTransport(u -> json(aAnswer(0, "93.184.216.34", "60")));
        DohResolver r = mk(t, new FakeSys(null), null, new Clock());
        r.lookup("a.test", 4);
        r.close();
        check("close memanggil cancelAll", t.cancels.get() == 1 && r.isClosed());
        check("lookup setelah close ditolak", throwsUnknown(r, "a.test", 4) && r.cacheSize() == 0);
        // konfigurasi lain = resolver lain = cache lain (tidak ada hasil lama yang terbawa)
        FakeTransport tA = new FakeTransport(u -> json(aAnswer(0, "198.51.100.1", "300")));
        FakeTransport tB = new FakeTransport(u -> json(aAnswer(0, "198.51.100.2", "300")));
        DohResolver ra = mk(tA, new FakeSys(null), null, new Clock());
        DohResolver rb = mk(tB, new FakeSys(null), null, new Clock());
        ra.lookup("same.test", 4);
        check("resolver baru tidak memakai cache resolver lama", rb.lookup("same.test", 4).get(0).getHostAddress().equals("198.51.100.2"));
        // hasil yang selesai SETELAH close tidak masuk cache
        final CountDownLatch inQuery = new CountDownLatch(1), go = new CountDownLatch(1);
        FakeTransport ts = new FakeTransport(u -> { inQuery.countDown(); try { go.await(); } catch (InterruptedException e) { } return json(aAnswer(0, "198.51.100.3", "300")); });
        DohResolver rc = mk(ts, new FakeSys(null), null, new Clock());
        Thread th = new Thread(() -> { try { rc.lookup("late.test", 4); } catch (UnknownHostException e) { } });
        th.start();
        inQuery.await();
        rc.close();
        go.countDown();
        th.join(3000);
        check("hasil terlambat setelah close tidak di-cache", rc.cacheSize() == 0);
        // batas cache
        FakeTransport tm = new FakeTransport(u -> json(aAnswer(0, "198.51.100.4", "300")));
        DohResolver rm = mk(tm, new FakeSys(null), null, new Clock());
        for (int i = 0; i < 1500; i++) rm.lookup("h" + i + ".test", 4);
        check("cache dibatasi 512 entri (ukuran " + rm.cacheSize() + ")", rm.cacheSize() <= DohResolver.MAX_ENTRIES);
        // permintaan berhasil terbaru tetap ada (LRU)
        int before = tm.calls.get();
        rm.lookup("h1499.test", 4);
        check("entri terbaru masih di cache", tm.calls.get() == before);
    }

    static void concurrencyTests() throws Exception {
        FakeTransport t = new FakeTransport(u -> { try { Thread.sleep(300); } catch (InterruptedException e) { } return json(aAnswer(0, "198.51.100.5", "60")); });
        final DohResolver r = mk(t, new FakeSys(null), null, new Clock());
        int n = 12;
        final CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(n);
        final AtomicInteger okc = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try { start.await(); if (r.lookup("burst.test", 4).size() == 1) okc.incrementAndGet(); } catch (Exception e) { }
                done.countDown();
            }).start();
        }
        start.countDown();
        done.await();
        check("12 permintaan bersamaan -> 1 query DoH (singleflight)", t.calls.get() == 1 && okc.get() == n);
    }

    static void connectTests() throws Exception {
        try (ServerSocket srv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            int port = srv.getLocalPort();
            // DoH gagal -> DNS sistem memberi 127.0.0.1 -> tersambung
            FakeTransport t = new FakeTransport(u -> { throw new java.net.ConnectException("x"); });
            FakeSys sys = new FakeSys(Collections.singletonList(ip("127.0.0.1")));
            DohResolver r = mk(t, sys, new Events(), new Clock());
            try (Socket s = r.connect("local.test", port, 2000, 5000, null)) {
                check("connect lewat cadangan sistem", s.isConnected());
            }
            try (Socket s = r.connect("127.0.0.1", port, 2000, 5000, null)) {
                check("connect literal tanpa DNS", s.isConnected() && sys.calls.get() == 1);
            }
            try (Socket s = r.connect("[127.0.0.1]", port, 2000, 5000, null)) {
                check("connect literal berkurung", s.isConnected());
            }
            boolean thr = false;
            try { r.connect("local.test", port, 2000, 5000, () -> true); } catch (IOException e) { thr = true; }
            check("connect berhenti bila klien sudah memutus", thr);
            DohResolver rc = mk(t, sys, null, new Clock());
            rc.close();
            thr = false;
            try { rc.connect("local.test", port, 2000, 5000, null); } catch (IOException e) { thr = true; }
            check("connect setelah close ditolak", thr);
            boolean nx = false;
            try {
                mk(new FakeTransport(u -> json(aAnswer(3))), new FakeSys(null), null, new Clock()).connect("none.test", port, 2000, 5000, null);
            } catch (UnknownHostException e) { nx = true; } catch (IOException e) { }
            check("connect NXDOMAIN -> UnknownHostException", nx);
        }
        // IPv4 ditolak (port tidak mendengarkan) lalu IPv6 berhasil, bila lingkungan mendukung ::1
        ServerSocket v6 = null;
        try { v6 = new ServerSocket(0, 8, InetAddress.getByName("::1")); } catch (IOException e) { v6 = null; }
        if (v6 == null) {
            System.out.println("(IPv6 loopback tidak tersedia: uji IPv4->IPv6 dilewati)");
        } else {
            try (ServerSocket srv6 = v6) {
                int port = srv6.getLocalPort();
                FakeTransport t = new FakeTransport(u -> typeOf(u).equals("A") ? json(aAnswer(0, "127.0.0.2", "60")) : json(aAnswer(0, "::1", "60")));
                // DoH menjawab 127.0.0.2 (loopback, difilter) -> A kosong -> beralih ke AAAA ::1 (juga loopback, difilter)
                // sehingga gunakan cadangan sistem yang tidak difilter untuk mengukur urutan: v4 gagal (refused) lalu v6 berhasil
                FakeTransport tf = new FakeTransport(u -> { throw new java.net.ConnectException("x"); });
                FakeSys sys = new FakeSys(Arrays.asList(ip("127.0.0.1"), ip("::1")));
                DohResolver r = mk(tf, sys, null, new Clock());
                try (Socket s = r.connect("dual.test", port, 2000, 5000, null)) {
                    check("IPv4 gagal -> IPv6 dicoba dan berhasil", s.getInetAddress() instanceof java.net.Inet6Address);
                }
            }
        }

        // urutan pencarian tanpa memerlukan IPv6 nyata: A ada tapi semua sambungan gagal -> AAAA baru ditanyakan
        FakeTransport tq = new FakeTransport(u -> typeOf(u).equals("A") ? json(aAnswer(0, "198.51.100.7", "60")) : json(aAnswer(0, "2001:db8::1", "60")));
        DohResolver rq = mk(tq, new FakeSys(null), null, new Clock());
        boolean failedConn = false;
        try { rq.connect("stage.test", 9, 300, 3000, null); } catch (IOException e) { failedConn = !(e instanceof UnknownHostException); }
        boolean sawA = false, sawAAAA = false;
        for (String u : tq.urls) { if (u.contains("type=AAAA")) sawAAAA = true; else sawA = true; }
        check("A ada tapi sambungan gagal -> AAAA ikut dicari sebagai cadangan", sawA && sawAAAA && failedConn);
        FakeTransport tq2 = new FakeTransport(u -> typeOf(u).equals("A") ? json(aAnswer(0)) : json(aAnswer(0, "2001:db8::1", "60")));
        DohResolver rq2 = mk(tq2, new FakeSys(null), null, new Clock());
        try { rq2.connect("v6only.test", 9, 300, 3000, null); } catch (IOException e) { }
        check("A kosong -> AAAA dicari", tq2.urls.size() == 2 && tq2.urls.get(1).contains("type=AAAA"));
        FakeTransport tq3 = new FakeTransport(u -> json(aAnswer(0, "198.51.100.7", "60")));
        DohResolver rq3 = mk(tq3, new FakeSys(null), null, new Clock());
        long t0 = System.nanoTime();
        try { rq3.connect("budget.test", 9, 5000, 400, null); } catch (IOException e) { }
        check("batas total waktu sambungan dihormati", (System.nanoTime() - t0) / 1_000_000 < 3000);
        // urutan: IPv4 diutamakan bila tersambung
        try (ServerSocket srv4 = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            FakeTransport tf = new FakeTransport(u -> { throw new java.net.ConnectException("x"); });
            FakeSys sys = new FakeSys(Arrays.asList(ip("::1"), ip("127.0.0.1")));
            DohResolver r = mk(tf, sys, null, new Clock());
            try (Socket s = r.connect("pref.test", srv4.getLocalPort(), 2000, 5000, null)) {
                check("IPv4 diutamakan walau IPv6 muncul lebih dulu", s.getInetAddress() instanceof java.net.Inet4Address);
            }
        }
    }

    // ---------- HTTP nyata (server lokal, tanpa TLS: hanya transport uji allowPlain) ----------
    static void httpTransportTests() throws Exception {
        final AtomicInteger hits = new AtomicInteger();
        final String[] lastQuery = new String[1];
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        srv.createContext("/ok", ex -> {
            hits.incrementAndGet();
            lastQuery[0] = ex.getRequestURI().getRawQuery();
            byte[] b = aAnswer(0, "93.184.216.34", "60").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/dns-json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        srv.createContext("/err", ex -> { ex.sendResponseHeaders(503, -1); ex.close(); });
        srv.createContext("/redir", ex -> { ex.getResponseHeaders().add("Location", "/ok"); ex.sendResponseHeaders(302, -1); ex.close(); });
        srv.createContext("/huge", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (OutputStream o = ex.getResponseBody()) { byte[] z = new byte[8192]; Arrays.fill(z, (byte) ' '); for (int i = 0; i < 40; i++) o.write(z); } catch (IOException e) { }
        });
        srv.createContext("/hang", ex -> { try { Thread.sleep(3000); } catch (InterruptedException e) { } ex.close(); });
        srv.createContext("/drip", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (OutputStream o = ex.getResponseBody()) { for (int i = 0; i < 40; i++) { o.write(' '); o.flush(); Thread.sleep(100); } } catch (Exception e) { }
        });
        srv.createContext("/cut", ex -> { ex.sendResponseHeaders(200, 1000); try (OutputStream o = ex.getResponseBody()) { o.write("{\"Sta".getBytes()); } catch (IOException e) { } ex.close(); });
        srv.start();
        int port = srv.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        try {
            DohResolver.UrlTransport ut = new DohResolver.UrlTransport(true);
            FakeSys sys = new FakeSys(Collections.singletonList(ip("203.0.113.9")));

            DohResolver ok = new DohResolver(base + "/ok", ut, sys, pool, null, System::currentTimeMillis);
            List<InetAddress> l = ok.lookup("http.test", 4);
            check("HTTP nyata: DoH berhasil", l.size() == 1 && sys.calls.get() == 0);
            check("HTTP nyata: nama/tipe di query string", "name=http.test&type=A".equals(lastQuery[0]));
            DohResolver okq = new DohResolver(base + "/ok?ct=application/dns-json", ut, sys, pool, null, System::currentTimeMillis);
            okq.lookup("q.test", 4);
            check("endpoint dengan ? memakai &", "ct=application/dns-json&name=q.test&type=A".equals(lastQuery[0]));
            check("probe HTTP nyata", ok.probe() == null);

            String[] bads = {"/err", "/redir", "/huge", "/cut"};
            for (String p : bads) {
                FakeSys s2 = new FakeSys(Collections.singletonList(ip("203.0.113.9")));
                DohResolver r = new DohResolver(base + p, ut, s2, pool, null, System::currentTimeMillis);
                check("HTTP nyata " + p + " -> cadangan sistem", r.lookup("e.test", 4).get(0).getHostAddress().equals("203.0.113.9") && s2.calls.get() == 1);
            }
            int h0 = hits.get();
            new DohResolver(base + "/redir", ut, new FakeSys(Collections.singletonList(ip("203.0.113.9"))), pool, null, System::currentTimeMillis).lookup("r.test", 4);
            check("redirect tidak diikuti", hits.get() == h0);

            for (String p : new String[]{"/hang", "/drip"}) {
                FakeSys s3 = new FakeSys(Collections.singletonList(ip("203.0.113.9")));
                DohResolver r = new DohResolver(base + p, ut, s3, pool, null, System::currentTimeMillis);
                r.dohTimeoutMs = 400;
                long t0 = System.nanoTime();
                List<InetAddress> res = r.lookup("t.test", 4);
                long ms = (System.nanoTime() - t0) / 1000000;
                check("HTTP nyata " + p + " dibatasi waktu (" + ms + " ms) lalu cadangan", ms < 2500 && res.get(0).getHostAddress().equals("203.0.113.9"));
            }
        } finally {
            srv.stop(0);
        }
        // server tidak ada (port tertutup) -> ConnectException -> cadangan
        FakeSys s4 = new FakeSys(Collections.singletonList(ip("203.0.113.9")));
        DohResolver rr = new DohResolver("http://127.0.0.1:" + port + "/ok", new DohResolver.UrlTransport(true), s4, pool, null, System::currentTimeMillis);
        check("server DoH mati -> cadangan sistem", rr.lookup("down.test", 4).size() == 1 && s4.calls.get() == 1);
        // transport produksi menolak non-https
        boolean thr = false;
        try { new DohResolver.UrlTransport(false).get("http://127.0.0.1:" + port + "/ok", 500, 1000); } catch (IOException e) { thr = true; }
        check("transport produksi menolak http", thr);
    }
}
