package com.myweb.app;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;

/**
 * Pengujian mandiri untuk FilterEngine dan PublicSuffix (tanpa JUnit, tanpa Android).
 *
 * Jalankan (JDK 11+):
 *   mkdir -p /tmp/ft
 *   javac -d /tmp/ft app/src/main/java/com/myweb/app/PublicSuffix.java \
 *       app/src/main/java/com/myweb/app/FilterEngine.java \
 *       app/src/test/java/com/myweb/app/FilterEngineSelfTest.java
 *   java -cp /tmp/ft com.myweb.app.FilterEngineSelfTest
 * Di GitHub Actions langkah ini dijalankan otomatis oleh build.yml sebelum build APK.
 */
public final class FilterEngineSelfTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; } else { fail++; System.out.println("GAGAL: " + name); }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) { fail++; System.out.println("GAGAL: " + name + " | diharapkan=" + expected + " didapat=" + actual); }
        else pass++;
    }

    // aturan umum dinyalakan agar logika umum tetap teruji; perilaku bawaan (mati) diuji terpisah
    private static FilterEngine.Builder builder(boolean cosmetic) {
        return new FilterEngine.Builder(null, cosmetic).genericCosmetic(true);
    }

    private static FilterEngine.Rules abp(boolean cosmetic, String... lines) {
        FilterEngine.Builder b = builder(cosmetic);
        for (String l : lines) b.addLine(l, FilterEngine.KIND_ABP);
        return b.build();
    }

    private static FilterEngine.Rules dom(String... lines) {
        FilterEngine.Builder b = builder(false);
        for (String l : lines) b.addLine(l, FilterEngine.KIND_DOMAIN);
        return b.build();
    }

    public static void main(String[] args) throws Exception {
        testPublicSuffixVectors();
        testPublicSuffixBuiltin();
        testHostnameValidation();
        testNetworkRules();
        testDomainLists();
        testCommentsAndJunk();
        testExceptionsImportantPopup();
        testCosmetic();
        testSelectorSafety();
        testGenericOffByDefaultAndPageBudget();
        testListValidation();
        testRealisticSamples();
        testFuzz();
        testPerformance();
        testPublicSuffixFileLoading();   // terakhir: mengganti daftar aktif global

        System.out.println();
        System.out.println("Lulus: " + pass + "  Gagal: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------- Public Suffix ----------------

    private static void psl(PublicSuffix p, String host, String expectedRegistrable) {
        eq("psl " + host, expectedRegistrable, p.registrable(host));
    }

    private static void testPublicSuffixVectors() {
        // aturan minimum yang dibutuhkan vektor uji resmi publicsuffix.org (checkPublicSuffix)
        PublicSuffix p = PublicSuffix.fromRules(
            "// komentar\nac\ncom\nbiz\nuk.com\n*.mm\njp\nac.jp\nkyoto.jp\nide.kyoto.jp\n*.kobe.jp\n!city.kobe.jp\n" +
            "*.ck\n!www.ck\nus\nak.us\nk12.ak.us\n");
        psl(p, "example.COM", "example.com");
        psl(p, "WwW.example.COM", "example.com");
        // TLD tidak dikenal -> aturan bawaan "*"
        psl(p, "example.example", "example.example");
        psl(p, "b.example.example", "example.example");
        psl(p, "a.b.example.example", "example.example");
        // TLD dengan satu aturan
        psl(p, "domain.biz", "domain.biz");
        psl(p, "b.domain.biz", "domain.biz");
        psl(p, "a.b.domain.biz", "domain.biz");
        // TLD dengan beberapa aturan tingkat-2
        psl(p, "example.com", "example.com");
        psl(p, "a.b.example.com", "example.com");
        psl(p, "example.uk.com", "example.uk.com");
        psl(p, "a.b.example.uk.com", "example.uk.com");
        psl(p, "test.ac", "test.ac");
        // wildcard
        psl(p, "b.c.mm", "b.c.mm");
        psl(p, "a.b.c.mm", "b.c.mm");
        psl(p, "c.mm", "c.mm");                 // c.mm sendiri public suffix -> dikembalikan apa adanya
        // TLD kompleks
        psl(p, "test.jp", "test.jp");
        psl(p, "www.test.jp", "test.jp");
        psl(p, "test.ac.jp", "test.ac.jp");
        psl(p, "www.test.ac.jp", "test.ac.jp");
        psl(p, "test.kyoto.jp", "test.kyoto.jp");
        psl(p, "b.ide.kyoto.jp", "b.ide.kyoto.jp");
        psl(p, "a.b.ide.kyoto.jp", "b.ide.kyoto.jp");
        psl(p, "b.c.kobe.jp", "b.c.kobe.jp");
        psl(p, "a.b.c.kobe.jp", "b.c.kobe.jp");
        psl(p, "city.kobe.jp", "city.kobe.jp");           // pengecualian
        psl(p, "www.city.kobe.jp", "city.kobe.jp");
        // wildcard + pengecualian
        psl(p, "b.test.ck", "b.test.ck");
        psl(p, "a.b.test.ck", "b.test.ck");
        psl(p, "www.ck", "www.ck");
        psl(p, "www.www.ck", "www.ck");
        // K12 US
        psl(p, "test.us", "test.us");
        psl(p, "www.test.us", "test.us");
        psl(p, "test.ak.us", "test.ak.us");
        psl(p, "www.test.k12.ak.us", "test.k12.ak.us");
        // bentuk tidak biasa
        psl(p, "example.com.", "example.com");            // titik di ujung
        psl(p, "", "");
        psl(p, null, "");
        psl(p, "localhost", "localhost");
        psl(p, "a..b.com", "a..b.com");                    // label kosong: tidak ditebak
        psl(p, "192.168.1.10", "192.168.1.10");            // IPv4 utuh (logika lama menghasilkan "1.10")
        psl(p, "10.0.0.1", "10.0.0.1");
        psl(p, "[::1]", "[::1]");
        psl(p, "999.1.1.1", "1.1");                        // bukan IPv4 valid -> diperlakukan sebagai nama (aturan bawaan "*")
        psl(p, "xn--p1ai", "xn--p1ai");
    }

    private static void testPublicSuffixBuiltin() {
        // daftar bawaan (aktif sebelum psl.dat diunduh)
        psl(PublicSuffix.fromRules(""), "example.com", "example.com");
        eq("registrableDomain komiku.org", "komiku.org", PublicSuffix.registrableDomain("komiku.org"));
        eq("sub.example.com", "example.com", PublicSuffix.registrableDomain("sub.example.com"));
        eq("a.b.c.example.com", "example.com", PublicSuffix.registrableDomain("a.b.c.example.com"));
        eq("example.co.uk", "example.co.uk", PublicSuffix.registrableDomain("example.co.uk"));
        eq("www.example.co.uk", "example.co.uk", PublicSuffix.registrableDomain("www.example.co.uk"));
        eq("example.co.id", "example.co.id", PublicSuffix.registrableDomain("example.co.id"));
        eq("cdn.example.co.id", "example.co.id", PublicSuffix.registrableDomain("cdn.example.co.id"));
        eq("example.web.id", "example.web.id", PublicSuffix.registrableDomain("example.web.id"));
        eq("x.example.or.id", "example.or.id", PublicSuffix.registrableDomain("x.example.or.id"));
        eq("example.com.au", "example.com.au", PublicSuffix.registrableDomain("a.example.com.au"));
        // kasus yang SALAH pada logika lama (hanya mengenal co/com/or/web/net/org/ac/go/my/sch)
        eq("gov.uk", "service.gov.uk", PublicSuffix.registrableDomain("www.service.gov.uk"));
        eq("edu.au", "uni.edu.au", PublicSuffix.registrableDomain("www.uni.edu.au"));
        eq("gov.br", "agency.gov.br", PublicSuffix.registrableDomain("www.agency.gov.br"));
        eq("ne.jp", "site.ne.jp", PublicSuffix.registrableDomain("www.site.ne.jp"));
        eq("me.uk", "blog.me.uk", PublicSuffix.registrableDomain("www.blog.me.uk"));
        // hosting publik: dua situs berbeda tidak boleh dianggap satu situs
        eq("github.io a", "alice.github.io", PublicSuffix.registrableDomain("alice.github.io"));
        eq("github.io b", "bob.github.io", PublicSuffix.registrableDomain("www.bob.github.io"));
        check("blogspot beda situs", !PublicSuffix.registrableDomain("a.blogspot.com").equals(PublicSuffix.registrableDomain("b.blogspot.com")));
        eq("pages.dev", "komik.pages.dev", PublicSuffix.registrableDomain("cdn.komik.pages.dev"));
        // IP
        eq("ip lokal", "192.168.1.10", PublicSuffix.registrableDomain("192.168.1.10"));
        check("dua IP berbeda tidak sama", !PublicSuffix.registrableDomain("192.168.1.10").equals(PublicSuffix.registrableDomain("10.0.1.10")));
    }

    private static void testPublicSuffixFileLoading() throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"));
        // 1) file kecil/HTML ditolak, daftar aktif tidak berubah
        File bad = File.createTempFile("psl-bad", ".dat", dir);
        FileWriter w = new FileWriter(bad);
        w.write("<html><body>Captive portal</body></html>\n");
        w.close();
        check("HTML bukan PSL valid", !PublicSuffix.isValidFile(bad));
        check("loadFromFile menolak HTML", !PublicSuffix.loadFromFile(bad));
        eq("daftar lama tetap", "example.co.uk", PublicSuffix.registrableDomain("www.example.co.uk"));
        // 2) file besar & valid diterima
        File good = File.createTempFile("psl-good", ".dat", dir);
        w = new FileWriter(good);
        w.write("// ===BEGIN ICANN DOMAINS===\n");
        for (int i = 0; i < 1500; i++) w.write("tld" + i + "\n");
        w.write("zz\nzz.test\n*.wild\n!ok.wild\n// ===BEGIN PRIVATE DOMAINS===\nhost.example\n");
        w.close();
        check("PSL besar valid", PublicSuffix.isValidFile(good));
        check("loadFromFile menerima", PublicSuffix.loadFromFile(good));
        eq("aturan dari file dipakai", "a.zz.test", PublicSuffix.registrableDomain("x.a.zz.test"));
        eq("wildcard dari file", "b.x.wild", PublicSuffix.registrableDomain("b.x.wild"));
        eq("pengecualian dari file", "ok.wild", PublicSuffix.registrableDomain("www.ok.wild"));
        eq("hosting privat dari file", "me.host.example", PublicSuffix.registrableDomain("a.me.host.example"));
        check("load ulang file sama murah", PublicSuffix.loadFromFile(good));
        check("file tidak ada", !PublicSuffix.loadFromFile(new File(dir, "tidak-ada-psl.dat")));
        bad.delete();
        good.delete();
    }

    // ---------------- Validasi nama host ----------------

    private static void testHostnameValidation() {
        String[] ok = {"example.com", "a-b.example.co.uk", "xn--p1ai.xn--p1ai", "ads1.example.org", "a.bc", "x.xn--80asehdb"};
        for (String s : ok) check("host valid: " + s, FilterEngine.isHostname(s));
        String[] bad = {"", "localhost", "example", "-a.com", "a-.com", "a..com", ".example.com", "example.com.", "ex ample.com",
            "exa_mple.com", "1.2.3.4", "example.123", "a.b", "*.example.com", "example.com^", "example.com/path",
            "UPPER.com", "a.b.c.d.e.f.g.h.i.j.k.l.m.n.o.p.q.r.s.t.u.v.w.x.y.z".replace("z", "z-")};
        for (String s : bad) check("host tidak valid: " + s, !FilterEngine.isHostname(s));
        StringBuilder lab = new StringBuilder();
        for (int i = 0; i < 64; i++) lab.append('a');
        check("label 64 huruf ditolak", !FilterEngine.isHostname(lab + ".com"));
        check("label 63 huruf diterima", FilterEngine.isHostname(lab.substring(1) + ".com"));
    }

    // ---------------- Aturan jaringan ----------------

    private static void testNetworkRules() {
        eq("||d^", FilterEngine.R_BLOCK, builder(false).addLine("||ads.example.com^", FilterEngine.KIND_ABP));
        eq("||d^$third-party", FilterEngine.R_BLOCK, builder(false).addLine("||ads.example.com^$third-party", FilterEngine.KIND_ABP));
        eq("||d^$3p,important", FilterEngine.R_BLOCK, builder(false).addLine("||ads.example.com^$3p,important", FilterEngine.KIND_ABP));
        eq("||d$third-party", FilterEngine.R_BLOCK, builder(false).addLine("||ads.example.com$third-party", FilterEngine.KIND_ABP));
        eq("||d", FilterEngine.R_BLOCK, builder(false).addLine("||ads.example.com", FilterEngine.KIND_ABP));
        eq("||d^$all", FilterEngine.R_BLOCK, builder(false).addLine("||ads.example.com^$all", FilterEngine.KIND_ABP));
        eq("huruf besar", FilterEngine.R_BLOCK, builder(false).addLine("||ADS.Example.COM^$THIRD-PARTY", FilterEngine.KIND_ABP));
        // tidak didukung -> dilewati (tidak boleh jadi blokir domain penuh)
        String[] unsupported = {
            "||example.com/ads/banner.js", "||example.com^$script", "||example.com^$image,third-party",
            "||example.com^$domain=a.com", "||example.com^$~third-party", "||example.com^*/ads", "||example.com:8080^",
            "||example.com|", "||*.example.com^", "||example.com^$", "||example.com^$third-party,",
            "|https://example.com/ads", "/banner\\d+/", "@@example.com", "||example.com^$csp=script-src 'self'",
        };
        for (String l : unsupported) {
            FilterEngine.Builder b = builder(false);
            int r = b.addLine(l, FilterEngine.KIND_ABP);
            check("dilewati: " + l, r == FilterEngine.R_UNSUPPORTED || r == FilterEngine.R_INVALID);
            check("tidak memblokir: " + l, !b.build().isBlocked("example.com"));
        }
        // domain rusak
        eq("||-bad.com^", FilterEngine.R_INVALID, builder(false).addLine("||-bad.com^", FilterEngine.KIND_ABP));
        eq("||1.2.3.4^", FilterEngine.R_INVALID, builder(false).addLine("||1.2.3.4^", FilterEngine.KIND_ABP));
        eq("||^", FilterEngine.R_UNSUPPORTED, builder(false).addLine("||^", FilterEngine.KIND_ABP));
        // subdomain dan induk
        FilterEngine.Rules r = abp(false, "||ads.example.com^", "||tracker.net^");
        check("host persis", r.isBlocked("ads.example.com"));
        check("subdomain ikut", r.isBlocked("x.y.ads.example.com"));
        check("induk TIDAK ikut", !r.isBlocked("example.com"));
        check("saudara TIDAK ikut", !r.isBlocked("www.example.com"));
        check("huruf besar & titik akhir", r.isBlocked("ADS.Example.com."));
        check("domain lain", !r.isBlocked("notads.example.com") && !r.isBlocked("ads.example.org"));
        check("tracker.net", r.isBlocked("cdn.tracker.net"));
        check("null/kosong", !r.isBlocked(null) && !r.isBlocked("") && !r.isBlocked("."));
        check("TLD saja tidak dicek", !r.isBlocked("net") && !r.isBlocked("com"));
        // bawaan (defaults)
        FilterEngine.Builder b = new FilterEngine.Builder(java.util.Arrays.asList("DoubleClick.net", "x.org"), false);
        FilterEngine.Rules d = b.build();
        check("default dinormalisasi", d.isBlocked("ad.doubleclick.net") && d.isBlocked("x.org"));
        eq("jumlah default", 2, d.blockCount());
    }

    // ---------------- Daftar domain / hosts ----------------

    private static void testDomainLists() {
        FilterEngine.Rules r = dom(
            "# komentar", "! komentar adblock", "", "   ",
            "0.0.0.0 ads.one.com", "127.0.0.1 ads.two.com", "0.0.0.0 a.three.com b.three.com  # dua host",
            "0.0.0.0 localhost", "127.0.0.1 localhost.localdomain", "0.0.0.0 0.0.0.0",
            "plain.four.com", "Plain.FIVE.com", "six.com # komentar di ujung", "seven.com.",
            "||adguard.eight.com^", "@@||ok.eight.com^", "||adguard.nine.com^$important",
            "not a domain at all", "bad_underscore.com", "http://example.org/x", "1.2.3.4", "192.168.0.1 router.home.arpa");
        String[] blocked = {"ads.one.com", "ads.two.com", "a.three.com", "b.three.com", "plain.four.com", "plain.five.com", "six.com", "seven.com", "x.adguard.eight.com", "adguard.nine.com"};
        for (String h : blocked) check("diblokir: " + h, r.isBlocked(h));
        String[] free = {"localhost", "localhost.localdomain", "not", "bad_underscore.com", "example.org", "router.home.arpa", "ok.eight.com"};
        for (String h : free) check("tidak diblokir: " + h, !r.isBlocked(h));
        // baris polos pada daftar bergaya ABP BUKAN domain (fragmen URL)
        FilterEngine.Rules e = abp(false, "example.com", "banner.ads.example.org", "-ad-banner.", "/ads/");
        check("baris polos ABP bukan domain", !e.isBlocked("example.com") && !e.isBlocked("banner.ads.example.org"));
        // komentar '#' pada KIND_DOMAIN dilewati, bukan dianggap kosmetik
        eq("# pada daftar domain", FilterEngine.R_SKIP, builder(true).addLine("##.ad", FilterEngine.KIND_DOMAIN));
    }

    // ---------------- Komentar, kosong, sampah ----------------

    private static void testCommentsAndJunk() {
        FilterEngine.Builder b = builder(true);
        eq("kosong", FilterEngine.R_SKIP, b.addLine("", FilterEngine.KIND_ABP));
        eq("spasi", FilterEngine.R_SKIP, b.addLine("   \t ", FilterEngine.KIND_ABP));
        eq("null", FilterEngine.R_SKIP, b.addLine(null, FilterEngine.KIND_ABP));
        eq("komentar !", FilterEngine.R_SKIP, b.addLine("! Title: EasyList", FilterEngine.KIND_ABP));
        eq("header [", FilterEngine.R_SKIP, b.addLine("[Adblock Plus 2.0]", FilterEngine.KIND_ABP));
        eq("BOM + header", FilterEngine.R_SKIP, b.addLine("\uFEFF[Adblock Plus 2.0]", FilterEngine.KIND_ABP));
        eq("BOM + aturan", FilterEngine.R_BLOCK, b.addLine("\uFEFF||bom.example.com^", FilterEngine.KIND_ABP));
        eq("baris sangat panjang", FilterEngine.R_INVALID, b.addLine("||" + new String(new char[5000]).replace('\0', 'a') + ".com^", FilterEngine.KIND_ABP));
        check("satu baris rusak tidak merusak yang lain",
            b.addLine("||good1.example.com^", FilterEngine.KIND_ABP) == FilterEngine.R_BLOCK
            && b.addLine("\u0000\u0001\u0002###{{{", FilterEngine.KIND_ABP) != FilterEngine.R_BLOCK
            && b.addLine("||good2.example.com^", FilterEngine.KIND_ABP) == FilterEngine.R_BLOCK);
        FilterEngine.Rules r = b.build();
        check("aturan sebelum & sesudah baris rusak tetap aktif", r.isBlocked("good1.example.com") && r.isBlocked("good2.example.com") && r.isBlocked("bom.example.com"));
        eq("crash internal", 0, b.crashedCount());
        check("ringkasan tidak memuat nama domain", !r.summary().contains("example") && r.summary().contains("blok="));
    }

    // ---------------- Pengecualian, important, popup ----------------

    private static void testExceptionsImportantPopup() {
        FilterEngine.Rules r = abp(false,
            "||tracker.com^", "@@||good.tracker.com^",          // izinkan satu subdomain
            "||ads.net^$important", "@@||ads.net^",             // important menang atas pengecualian
            "||cdn.example.org^", "@@||example.org^",           // pengecualian induk melindungi turunan
            "||pop.io^$popup", "||popthird.io^$popup,third-party",
            "@@||site.com^$script",                              // pengecualian bertipe: dilewati
            "@@||typed.com^$popup");
        check("blokir biasa", r.isBlocked("x.tracker.com") && r.isBlocked("tracker.com"));
        check("pengecualian subdomain", !r.isBlocked("good.tracker.com") && !r.isBlocked("a.good.tracker.com"));
        check("saudara pengecualian tetap diblokir", r.isBlocked("other.tracker.com"));
        check("$important menang atas @@", r.isBlocked("ads.net") && r.isBlocked("x.ads.net"));
        check("@@ induk melindungi turunan", !r.isBlocked("cdn.example.org"));
        check("popup BUKAN blokir sumber daya", !r.isBlocked("pop.io") && !r.isBlocked("popthird.io"));
        check("popup dikenali untuk navigasi", r.isPopupBlocked("pop.io") && r.isPopupBlocked("a.popthird.io"));
        check("domain lain bukan popup", !r.isPopupBlocked("tracker.com") && !r.isPopupBlocked("other.io"));
        check("pengecualian bertipe dilewati", !abp(false, "||site.com^", "@@||site.com^$script").isBlocked("site.com") == false);
        eq("@@ $popup dilewati", FilterEngine.R_UNSUPPORTED, builder(false).addLine("@@||typed.com^$popup", FilterEngine.KIND_ABP));
        // @@ di daftar domain (AdGuard DNS) dipakai lintas daftar
        FilterEngine.Builder b = builder(false);
        b.addLine("0.0.0.0 shared.example.net", FilterEngine.KIND_DOMAIN);     // daftar 1
        b.addLine("@@||shared.example.net^", FilterEngine.KIND_DOMAIN);        // daftar lain mengizinkan
        check("pengecualian lintas daftar", !b.build().isBlocked("shared.example.net"));
        // @@ untuk popup
        FilterEngine.Rules pr = abp(false, "||pp.io^$popup", "@@||pp.io^");
        check("@@ juga mengizinkan popup", !pr.isPopupBlocked("pp.io"));
    }

    // ---------------- Kosmetik ----------------

    private static void testCosmetic() {
        FilterEngine.Rules r = abp(true,
            "##.ad-banner", "###sponsor", "##div[class^=\"adv-\"]",
            "komiku.id##.iklan", "komiku.id,example.org##.popup-box",
            "~skip.komiku.id##.ignored",                             // hanya pengecualian: tidak didukung
            "komiku.id,~vip.komiku.id##.promo",
            "other.com#@#.ad-banner", "komiku.id#@#.promo",
            "##.ad-banner",                                           // duplikat
            "example.*##.tld-wildcard",                               // wildcard TLD: tidak didukung
            "site.com##.a:has-text(Iklan)", "site.com##.b:xpath(//div)", "site.com##+js(abort-on-property-read, x)",
            "site.com#?#.c:-abp-has(.x)", "site.com#$#.d { color: red }");
        String komik = r.cssFor("komiku.id");
        check("generik berlaku", komik.contains(".ad-banner{display:none!important}") && komik.contains("#sponsor{") && komik.contains("div[class^=\"adv-\"]{"));
        check("khusus domain berlaku", komik.contains(".iklan{"));
        check("aturan multi-domain berlaku di semua domainnya", komik.contains(".popup-box{") && r.cssFor("example.org").contains(".popup-box{") && !r.cssFor("example.com").contains(".popup-box{"));
        check("pengecualian #@# per-domain", !komik.contains(".promo{"));
        check("subdomain mewarisi aturan induk", r.cssFor("www.komiku.id").contains(".iklan{") && r.cssFor("cdn.m.komiku.id").contains(".iklan{"));
        check("exclude ~ bekerja", r.cssFor("komiku.id").contains(".promo{") == false);
        check("domain lain TIDAK kena aturan komiku", !r.cssFor("example.com").contains(".iklan{"));
        check("generik tetap di domain lain", r.cssFor("example.com").contains(".ad-banner{"));
        check("#@# domain lain membuang generik", !r.cssFor("other.com").contains(".ad-banner{") && !r.cssFor("sub.other.com").contains(".ad-banner{"));
        check("tidak ada duplikat", count(komik, ".ad-banner{") == 1);
        check("hanya-pengecualian tidak jadi generik", !komik.contains(".ignored{") && !r.cssFor("skip.komiku.id").contains(".ignored{"));
        check("wildcard TLD dilewati", !r.cssFor("example.com").contains(".tld-wildcard"));
        check("prosedural dilewati", !r.cssFor("site.com").contains(":has-text") && !r.cssFor("site.com").contains("xpath") && !r.cssFor("site.com").contains("abort"));
        check("sintaks diperluas dilewati", !r.cssFor("site.com").contains("-abp-has") && !r.cssFor("site.com").contains("color"));
        // exclude per-aturan
        FilterEngine.Rules ex = abp(true, "komiku.id,~vip.komiku.id##.promo");
        check("exclude: host biasa kena", ex.cssFor("komiku.id").contains(".promo{") && ex.cssFor("a.komiku.id").contains(".promo{"));
        check("exclude: vip dan turunannya bebas", !ex.cssFor("vip.komiku.id").contains(".promo{") && !ex.cssFor("x.vip.komiku.id").contains(".promo{"));
        // kosmetik mati: dilewati total, tidak memakai memori
        FilterEngine.Rules off = abp(false, "##.ad-banner", "komiku.id##.iklan");
        eq("kosmetik mati -> CSS kosong", "", off.cssFor("komiku.id"));
        eq("cssFor null", "", r.cssFor(null));
        // batas
        FilterEngine.Builder cap = builder(true);
        for (int i = 0; i < FilterEngine.MAX_GENERIC + 50; i++) cap.addLine("##.g" + i, FilterEngine.KIND_ABP);
        FilterEngine.Rules cr = cap.build();
        check("batas aturan per halaman", count(cr.cssFor("a.com"), "{display:none!important}") == FilterEngine.MAX_PAGE_RULES);
        check("ringkasan mencatat terpotong", cr.summary().contains("terpotong=50"));
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    private static void testSelectorSafety() {
        String[] ok = {".ad", "#ad", "div.ad > a", "a[href*=\"ads\"]", "a[href^='http://x.com/?a=1;b=2']", "div:not(.keep) .ad",
            ".a\\:b", "ul li:nth-child(2n+1)", "[data-x=\"}\"]", "div:has(> img.ad)", "*", ".a,.b"};
        for (String s : ok) check("selector aman: " + s, FilterEngine.selectorOk(s));
        String[] bad = {"", ".a{}body{background:red}", "a}b", "a{b", ".x;color:red", "@media print", "a<b", ".a[href=\"x]",
            ".a[b", ".a)", ".a(", "a/*c*/b", "div{display:block}", "a\nb", "a:has-text(x)", "a:xpath(//a)", "a:matches-css(color: red)",
            "a:-abp-contains(x)", "a:contains(x)", "a:upward(2)", "a:style(color:red)", "a:remove()", "x +js(set, a, b)",
            "a[style=\"background:url(http://evil/x)\"]", "a{background:url(//x)}", ".a:expression(alert(1))", "a\"", "a'"};
        for (String s : bad) check("selector DITOLAK: " + s.replace("\n", "\\n"), !FilterEngine.selectorOk(s));
        StringBuilder longSel = new StringBuilder(".");
        for (int i = 0; i < 300; i++) longSel.append('a');
        check("selector terlalu panjang", !FilterEngine.selectorOk(longSel.toString()));
        // injeksi CSS lewat daftar yang disusupi tidak boleh lolos ke keluaran
        FilterEngine.Rules r = abp(true, "##.ok", "##a}body{background:url(//evil/t)}", "evil.com##x{}y", "##.ok2");
        String css = r.cssFor("evil.com");
        check("injeksi CSS ditolak", !css.contains("evil") && !css.contains("body{") && css.contains(".ok{") && css.contains(".ok2{"));
        // satu selector tak seimbang tidak boleh menelan aturan sesudahnya
        FilterEngine.Rules u = abp(true, "##.before", "##.broken[attr=\"x", "##.after");
        check("rule rusak dibuang, tetangga utuh", u.cssFor("a.com").contains(".before{") && u.cssFor("a.com").contains(".after{") && !u.cssFor("a.com").contains("broken"));
        // setiap rule berdiri sendiri (selector tidak digabung dengan koma)
        check("satu rule per baris", count(u.cssFor("a.com"), "\n") == 2);
    }

    // ---------------- Regresi laporan: kosmetik ON membuat HP panas dan aplikasi keluar ----------------

    private static void testGenericOffByDefaultAndPageBudget() {
        // bawaan: aturan UMUM tidak dimuat sama sekali, aturan khusus domain tetap jalan
        FilterEngine.Builder b = new FilterEngine.Builder(null, true);
        int r1 = b.addLine("##.ad-banner", FilterEngine.KIND_ABP);
        int r2 = b.addLine("###sponsor", FilterEngine.KIND_ABP);
        int r3 = b.addLine("##a[href*=\"track\"]", FilterEngine.KIND_ABP);
        int r4 = b.addLine("komiku.id##.iklan", FilterEngine.KIND_ABP);
        eq("umum dilewati (1)", FilterEngine.R_UNSUPPORTED, r1);
        eq("umum dilewati (2)", FilterEngine.R_UNSUPPORTED, r2);
        eq("umum dilewati (3)", FilterEngine.R_UNSUPPORTED, r3);
        eq("khusus domain tetap diterima", FilterEngine.R_COSMETIC, r4);
        String css = b.build().cssFor("komiku.id");
        check("CSS hanya aturan domain", css.equals(".iklan{display:none!important}\n"));
        check("domain lain tidak kena apa-apa", b.build().cssFor("example.com").isEmpty());
        // 10.000 aturan umum EasyList tidak menambah apa pun ke CSS halaman
        FilterEngine.Builder big = new FilterEngine.Builder(null, true);
        for (int i = 0; i < 10000; i++) big.addLine("##a[href*=\"x" + i + "\"]", FilterEngine.KIND_ABP);
        eq("10.000 aturan umum -> CSS kosong", "", big.build().cssFor("komiku.id"));
        // anggaran per halaman: satu situs dengan 5.000 aturan khusus tetap dipotong
        FilterEngine.Builder many = new FilterEngine.Builder(null, true);
        for (int i = 0; i < 5000; i++) many.addLine("situs.com##.slot" + i, FilterEngine.KIND_ABP);
        String pc = many.build().cssFor("situs.com");
        eq("anggaran aturan per halaman", FilterEngine.MAX_PAGE_RULES, count(pc, "{display:none!important}"));
        check("anggaran karakter", pc.length() <= FilterEngine.MAX_CSS_CHARS);
    }

    // ---------------- Validasi unduhan ----------------

    private static File tmp(String content) throws IOException {
        File f = File.createTempFile("lst", ".txt", new File(System.getProperty("java.io.tmpdir")));
        FileWriter w = new FileWriter(f);
        w.write(content);
        w.close();
        return f;
    }

    private static void testListValidation() throws IOException {
        StringBuilder hosts = new StringBuilder();
        for (int i = 0; i < 80; i++) hosts.append("ads").append(i).append(".example.com\n");
        File plain = tmp(hosts.toString());
        check("daftar domain polos valid", FilterEngine.looksLikeList(plain, FilterEngine.KIND_DOMAIN));
        // daftar gaya ABPindo: header + kebanyakan kosmetik/path, hanya 3 aturan domain murni
        StringBuilder abpindo = new StringBuilder("[Adblock Plus 2.0]\n! Title: contoh\n||one.example.com^\n||two.example.com^\n||three.example.com^\n");
        for (int i = 0; i < 100; i++) abpindo.append("situs").append(i).append(".id##.iklan").append(i).append("\n");
        File abp = tmp(abpindo.toString());
        check("daftar kebanyakan kosmetik tetap valid", FilterEngine.looksLikeList(abp, FilterEngine.KIND_ABP));
        // yang HARUS ditolak
        File html = tmp("<html><body>Login Wi-Fi</body></html>\n" + hosts);
        check("HTML ditolak walau ada baris mirip domain", FilterEngine.countAccepted(html, FilterEngine.KIND_ABP) < FilterEngine.MIN_ACCEPTED
            && !FilterEngine.looksLikeList(html, FilterEngine.KIND_ABP));
        File htmlOnly = tmp("<!DOCTYPE html>\n<html><head><title>x</title></head><body>\n" + new String(new char[200]).replace("\0", "teks biasa\n") + "</body></html>");
        check("halaman HTML panjang ditolak", !FilterEngine.looksLikeList(htmlOnly, FilterEngine.KIND_ABP) && !FilterEngine.looksLikeList(htmlOnly, FilterEngine.KIND_DOMAIN));
        check("file kosong ditolak", !FilterEngine.looksLikeList(tmp(""), FilterEngine.KIND_ABP));
        check("file pendek ditolak", !FilterEngine.looksLikeList(tmp("[Adblock Plus 2.0]\n! x\n||a.example.com^\n"), FilterEngine.KIND_ABP));
        check("file tidak ada ditolak", !FilterEngine.looksLikeList(new File("/tidak/ada/file.txt"), FilterEngine.KIND_ABP));
        plain.delete(); abp.delete(); html.delete(); htmlOnly.delete();
    }

    // ---------------- Sampel realistis (format EasyList / AdGuard DNS / ABPindo) ----------------

    private static void testRealisticSamples() {
        FilterEngine.Builder b = new FilterEngine.Builder(null, true).genericCosmetic(true);
        String[] adguard = {
            "! Title: AdGuard DNS filter", "! Version: 1.0", "||0-0.fr^", "||ad.example-ads.com^$important",
            "||tracker.example-t.net^$third-party", "@@||cdn.good-site.com^$important", "/^ads?[0-9]+\\./$denyallow=a.com",
            "||dns.rewrite.example^$dnsrewrite=1.2.3.4", "||client.example^$client=1.2.3.4",
            "||ads.keep.com^", "@@||ads.keep.com^"
        };
        for (String l : adguard) b.addLine(l, FilterEngine.KIND_DOMAIN);
        String[] yoyo = {"1.2.3.4", "ads.yoyo-one.com", "tracker.yoyo-two.net", "# komentar", "invalid line here"};
        for (String l : yoyo) b.addLine(l, FilterEngine.KIND_DOMAIN);
        String[] easylist = {
            "[Adblock Plus 2.0]", "! Title: EasyList", "-ad-manager/", "/adframe.", "&ad_type=", "||adserver.example.org^$third-party",
            "||ads.example.org/banner/*$image", "||popunder.example.org^$popup", "@@||example.org/ads/$script,domain=trusted.com",
            "##.advert", "###ad_box", "example.org##.sticky-ad", "example.org#@#.advert",
            "~sub.example.org##.x-only-exclude", "example.org###ad-top:not(.keep)", "domain-list.com,other-list.com##.shared",
            "||pagead2.googlesyndication.com^$third-party"
        };
        for (String l : easylist) b.addLine(l, FilterEngine.KIND_ABP);
        FilterEngine.Rules r = b.build();
        check("sampel: blokir dasar", r.isBlocked("a.0-0.fr") && r.isBlocked("ad.example-ads.com") && r.isBlocked("pagead2.googlesyndication.com"));
        check("sampel: daftar polos yoyo", r.isBlocked("ads.yoyo-one.com") && r.isBlocked("tracker.yoyo-two.net"));
        check("sampel: pengecualian", !r.isBlocked("ads.keep.com") && !r.isBlocked("cdn.good-site.com"));
        check("sampel: baris tak didukung tidak memblokir", !r.isBlocked("example.org") && !r.isBlocked("dns.rewrite.example") && !r.isBlocked("client.example"));
        check("sampel: popup", r.isPopupBlocked("popunder.example.org") && !r.isBlocked("popunder.example.org"));
        String css = r.cssFor("example.org");
        check("sampel: kosmetik example.org", css.contains(".sticky-ad{") && !css.contains(".advert{") && css.contains("#ad_box{") && css.contains("#ad-top:not(.keep){"));
        check("sampel: kosmetik domain lain", r.cssFor("domain-list.com").contains(".shared{") && r.cssFor("other-list.com").contains(".shared{") && r.cssFor("x.net").contains(".advert{"));
        check("sampel: hanya-pengecualian dilewati", !css.contains("x-only-exclude"));
        eq("sampel: crash internal", 0, b.crashedCount());
    }

    // ---------------- Fuzz ----------------

    private static void testFuzz() {
        Random rnd = new Random(12345);
        String alphabet = "|@#!$^*/:,.~-_[](){}<>\"'\\ \t\n\r\u0000abcxyz019ABC=?&%;+éあ\uFEFF";
        String[] seeds = {"||", "@@||", "##", "#@#", "#?#", "$", "^", "0.0.0.0 ", "[Adblock", "||a.com^$", ",", "~", "*."};
        FilterEngine.Builder b = new FilterEngine.Builder(null, true);
        int n = 60000;
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            if (rnd.nextBoolean()) sb.append(seeds[rnd.nextInt(seeds.length)]);
            int len = rnd.nextInt(60);
            for (int k = 0; k < len; k++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            if (rnd.nextInt(5) == 0) sb.append(seeds[rnd.nextInt(seeds.length)]);
            b.addLine(sb.toString(), rnd.nextBoolean() ? FilterEngine.KIND_ABP : FilterEngine.KIND_DOMAIN);
        }
        eq("fuzz: tidak ada exception internal (" + n + " baris)", 0, b.crashedCount());
        FilterEngine.Rules r = b.build();
        // hasil akhir tetap bisa dipakai
        r.isBlocked("a.b.com");
        r.isPopupBlocked("a.b.com");
        String css = r.cssFor("a.b.com");
        check("fuzz: CSS keluaran tidak berisi pemisah rule berbahaya", css.indexOf('<') < 0 && css.indexOf('@') < 0 && css.indexOf(';') < 0 || onlyInQuotes(css));
    }

    /** Tanda ; < @ hanya boleh muncul di dalam kutip atau di "{display:none!important}" yang kita tulis sendiri. */
    private static boolean onlyInQuotes(String css) {
        String[] lines = css.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String tail = "{display:none!important}";
            if (!line.endsWith(tail)) return false;
            String sel = line.substring(0, line.length() - tail.length());
            if (!FilterEngine.selectorOk(sel)) return false;
        }
        return true;
    }

    // ---------------- Kinerja (hanya dicetak, tidak menjadi syarat lulus) ----------------

    private static void testPerformance() {
        Random rnd = new Random(7);
        int n = 150000;
        String[] tlds = {"com", "net", "org", "io", "co.id", "co.uk"};
        FilterEngine.Builder b = new FilterEngine.Builder(null, true);
        long t0 = System.nanoTime();
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long mem0 = rt.totalMemory() - rt.freeMemory();
        for (int i = 0; i < n; i++) {
            String d = "h" + rnd.nextInt(1000000) + "." + tlds[rnd.nextInt(tlds.length)];
            switch (i % 6) {
                case 0: b.addLine("||" + d + "^$third-party", FilterEngine.KIND_ABP); break;
                case 1: b.addLine("0.0.0.0 " + d, FilterEngine.KIND_DOMAIN); break;
                case 2: b.addLine("@@||" + d + "^", FilterEngine.KIND_ABP); break;
                case 3: b.addLine(d + "##.ad-" + i + " > a[href*=\"track\"]", FilterEngine.KIND_ABP); break;
                case 4: b.addLine("##.generic" + i, FilterEngine.KIND_ABP); break;
                default: b.addLine("||" + d + "/path/" + i + "^$script", FilterEngine.KIND_ABP); break;
            }
        }
        FilterEngine.Rules r = b.build();
        long t1 = System.nanoTime();
        System.gc();
        long mem1 = rt.totalMemory() - rt.freeMemory();
        long q0 = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < 200000; i++) {
            if (r.isBlocked("cdn." + "h" + (i % 1000000) + ".com")) hits++;
        }
        long q1 = System.nanoTime();
        long c0 = System.nanoTime();
        String css = r.cssFor("h1.com");
        long c1 = System.nanoTime();
        System.out.println("[kinerja] parse " + n + " baris: " + ((t1 - t0) / 1000000) + " ms, memori ~" + ((mem1 - mem0) / (1024 * 1024)) + " MB");
        System.out.println("[kinerja] 200k pencarian isBlocked: " + ((q1 - q0) / 1000000) + " ms (" + hits + " cocok)");
        System.out.println("[kinerja] cssFor: " + ((c1 - c0) / 1000000) + " ms, " + css.length() + " karakter");
        System.out.println("[ringkasan] " + r.summary());
        check("kinerja: parse selesai dan aturan terpakai", r.blockCount() > 0);
        eq("kinerja: crash internal", 0, b.crashedCount());
    }
}
