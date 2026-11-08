package com.myweb.app;

public final class UrlRulesSelfTest {
    private static int pass = 0, fail = 0;
    private static void eq(String name, String exp, String got) {
        boolean ok = exp == null ? got == null : exp.equals(got);
        if (ok) pass++; else { fail++; System.out.println("GAGAL: " + name + " | harapan=" + exp + " | hasil=" + got); }
    }

    public static void main(String[] a) {
        // pembersih pelacak
        eq("utm dibuang", "https://a.com/p?id=5", UrlRules.cleanTracking("https://a.com/p?utm_source=x&id=5&utm_medium=y"));
        eq("semua dibuang", "https://a.com/p", UrlRules.cleanTracking("https://a.com/p?fbclid=abc&gclid=1"));
        eq("fragmen dijaga", "https://a.com/p?q=1#top", UrlRules.cleanTracking("https://a.com/p?q=1&igshid=zz#top"));
        eq("nama huruf besar", "https://a.com/", UrlRules.cleanTracking("https://a.com/?UTM_Source=x"));
        eq("tanpa pelacak -> null", null, UrlRules.cleanTracking("https://a.com/p?id=5&page=2"));
        eq("tanpa query -> null", null, UrlRules.cleanTracking("https://a.com/p"));
        eq("param mirip tidak dibuang", null, UrlRules.cleanTracking("https://a.com/p?utmx=1&myfbclid=2"));
        eq("nilai berisi = dijaga", "https://a.com/p?next=%2Fa%3Db", UrlRules.cleanTracking("https://a.com/p?next=%2Fa%3Db&utm_id=3"));
        eq("bukan http -> null", null, UrlRules.cleanTracking("ftp://a.com/?utm_source=x"));
        eq("null -> null", null, UrlRules.cleanTracking(null));
        eq("sampah -> null", null, UrlRules.cleanTracking("ht!tp://%%"));
        eq("tanda ? hanya di fragmen", null, UrlRules.cleanTracking("https://a.com/p#x?utm_source=1"));
        eq("idempoten", null, UrlRules.cleanTracking(UrlRules.cleanTracking("https://a.com/p?utm_source=x&id=1")));

        // AMP
        eq("google amp https", "https://example.com/artikel/1", UrlRules.unwrapAmp("https://www.google.com/amp/s/example.com/artikel/1"));
        eq("google amp http", "http://example.com/x", UrlRules.unwrapAmp("https://www.google.com/amp/example.com/x"));
        eq("google amp query", "https://example.com/x?a=1", UrlRules.unwrapAmp("https://www.google.co.id/amp/s/example.com/x?a=1"));
        eq("cdn ampproject c/s", "https://example.com/a/b", UrlRules.unwrapAmp("https://example-com.cdn.ampproject.org/c/s/example.com/a/b"));
        eq("cdn ampproject v/s", "https://example.com/a", UrlRules.unwrapAmp("https://cdn.ampproject.org/v/s/example.com/a"));
        eq("bukan amp", null, UrlRules.unwrapAmp("https://www.google.com/search?q=amp"));
        eq("amp tanpa tujuan", null, UrlRules.unwrapAmp("https://www.google.com/amp/"));
        eq("amp ke domain sendiri ditolak", null, UrlRules.unwrapAmp("https://www.google.com/amp/s/www.google.com/amp/s/x.com"));
        eq("situs biasa", null, UrlRules.unwrapAmp("https://example.com/amp/s/x.com"));
        eq("null", null, UrlRules.unwrapAmp(null));

        // HTTPS
        eq("naik https", "https://a.com/p?x=1#f", UrlRules.upgradeHttps("http://a.com/p?x=1#f"));
        eq("port 80 dibuang", "https://a.com/p", UrlRules.upgradeHttps("http://a.com:80/p"));
        eq("port 80 tanpa path", "https://a.com", UrlRules.upgradeHttps("http://a.com:80"));
        eq("port khusus tidak dinaikkan", null, UrlRules.upgradeHttps("http://a.com:8080/p"));
        eq("sudah https", null, UrlRules.upgradeHttps("https://a.com/"));
        eq("localhost", null, UrlRules.upgradeHttps("http://localhost/x"));
        eq("IP", null, UrlRules.upgradeHttps("http://192.168.1.5/x"));
        eq("IPv6", null, UrlRules.upgradeHttps("http://[::1]/x"));
        eq("host tanpa titik", null, UrlRules.upgradeHttps("http://router/x"));
        eq(".local", null, UrlRules.upgradeHttps("http://nas.local/x"));
        eq("userinfo dijaga", "https://u@a.com/", UrlRules.upgradeHttps("http://u@a.com/"));
        eq("null", null, UrlRules.upgradeHttps(null));
        System.out.println("UrlRulesSelfTest: " + pass + " lulus, " + fail + " gagal");
        System.exit(fail == 0 ? 0 : 1);
    }
}
