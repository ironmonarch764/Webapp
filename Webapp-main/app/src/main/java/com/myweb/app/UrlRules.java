package com.myweb.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Aturan penulisan ulang alamat (Java murni, diuji di JVM). Semua fungsi mengembalikan alamat baru, atau null bila
 * tidak ada yang perlu diubah / alamat tidak valid. Tidak pernah melempar exception.
 */
public final class UrlRules {
    private UrlRules() { }

    private static final Set<String> TRACKING_PARAMS = new HashSet<>(Arrays.asList(
        "fbclid", "gclid", "gclsrc", "dclid", "wbraid", "gbraid", "msclkid", "mc_eid", "mc_cid", "igshid",
        "yclid", "twclid", "ttclid", "_hsenc", "_hsmi", "mkt_tok"));

    private static URI parse(String url) {
        if (url == null) return null;
        try {
            URI u = new URI(url);
            String s = u.getScheme();
            if (s == null || !(s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"))) return null;
            if (u.getHost() == null || u.getHost().isEmpty()) return null;
            return u;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean validWeb(String url) {
        return parse(url) != null;
    }

    /** Buang parameter pelacak (utm_*, fbclid, gclid, ...). null bila tidak ada yang dibuang. */
    public static String cleanTracking(String url) {
        URI u = parse(url);
        if (u == null) return null;
        String q = u.getRawQuery();
        if (q == null || q.isEmpty()) return null;
        List<String> keep = new ArrayList<>();
        boolean removed = false;
        for (String part : q.split("&", -1)) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String name = (eq < 0 ? part : part.substring(0, eq)).toLowerCase(Locale.ROOT);
            if (name.startsWith("utm_") || TRACKING_PARAMS.contains(name)) removed = true;
            else keep.add(part);
        }
        if (!removed) return null;
        int qi = url.indexOf('?');
        int fi = url.indexOf('#');
        if (qi < 0 || (fi >= 0 && fi < qi)) return null;
        String base = url.substring(0, qi);
        String frag = fi >= 0 ? url.substring(fi) : "";
        String out = base + (keep.isEmpty() ? "" : "?" + String.join("&", keep)) + frag;
        return validWeb(out) ? out : null;
    }

    /** Buka situs asli dari tautan AMP Google (google.com/amp/s/... dan *.cdn.ampproject.org/c/s/...). */
    public static String unwrapAmp(String url) {
        URI u = parse(url);
        if (u == null) return null;
        String host = u.getHost().toLowerCase(Locale.ROOT);
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        String rest = null;
        boolean secure = true;
        if (host.startsWith("www.google.") && path.startsWith("/amp/")) {
            rest = path.substring("/amp/".length());
            if (rest.startsWith("s/")) rest = rest.substring(2);
            else secure = false;
        } else if (host.equals("cdn.ampproject.org") || host.endsWith(".cdn.ampproject.org")) {
            String p = path.startsWith("/c/") ? path.substring(3) : path.startsWith("/v/") ? path.substring(3) : null;
            if (p == null) return null;
            rest = p;
            if (rest.startsWith("s/")) rest = rest.substring(2);
            else secure = false;
        }
        if (rest == null || rest.isEmpty()) return null;
        String tail = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
        if (u.getRawFragment() != null) tail += "#" + u.getRawFragment();
        String out = (secure ? "https://" : "http://") + rest + tail;
        URI o = parse(out);
        if (o == null) return null;
        // tujuan tidak boleh berupa domain AMP/Google itu sendiri (cegah putaran tak berujung)
        String oh = o.getHost().toLowerCase(Locale.ROOT);
        if (oh.endsWith("ampproject.org") || oh.startsWith("www.google.")) return null;
        return out;
    }

    /** http:// -> https:// untuk situs biasa. Port khusus, alamat IP, localhost dan jaringan lokal tidak dinaikkan. */
    public static String upgradeHttps(String url) {
        URI u = parse(url);
        if (u == null || !"http".equalsIgnoreCase(u.getScheme())) return null;
        String host = u.getHost().toLowerCase(Locale.ROOT);
        int port = u.getPort();
        if (port != -1 && port != 80) return null;
        if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".internal")
            || host.indexOf('.') < 0 || host.indexOf(':') >= 0 || host.startsWith("[")) return null;
        boolean allDigits = true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(c >= '0' && c <= '9') && c != '.') { allDigits = false; break; }
        }
        if (allDigits) return null;
        int idx = url.indexOf("://");
        String rest = url.substring(idx + 3);
        if (port == 80) {
            rest = rest.replaceFirst("^([^/?#]*?):80(?=[/?#]|$)", "$1");
        }
        String out = "https://" + rest;
        return validWeb(out) ? out : null;
    }
}
