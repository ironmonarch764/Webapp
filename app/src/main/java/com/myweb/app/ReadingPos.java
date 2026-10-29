package com.myweb.app;

import java.net.URI;
import java.util.Locale;

/** Bantuan posisi membaca tanpa API Android (agar bisa diuji di JVM). */
public final class ReadingPos {

    /** Batas atas posisi scroll yang masuk akal (piksel CSS). */
    public static final int MAX_Y = 10_000_000;

    private ReadingPos() { }

    public static int clampY(int y) {
        if (y < 0) return 0;
        return Math.min(y, MAX_Y);
    }

    /**
     * Apakah dua alamat adalah dokumen yang sama untuk keperluan memulihkan posisi: host sama (tanpa "www."),
     * path sama (garis miring di ujung diabaikan), query sama. Skema (http/https) dan fragmen (#...) diabaikan,
     * karena redirect http->https dan anchor tidak mengubah isi halaman. Alamat tak terbaca = tidak sama.
     */
    public static boolean sameDocument(String a, String b) {
        if (a == null || b == null) return false;
        try {
            URI x = new URI(a.trim());
            URI y = new URI(b.trim());
            String hx = host(x);
            String hy = host(y);
            if (hx.isEmpty() || hy.isEmpty()) return false;
            return hx.equals(hy) && path(x).equals(path(y)) && eq(x.getRawQuery(), y.getRawQuery());
        } catch (Exception e) {
            return false;
        }
    }

    private static String host(URI u) {
        String h = u.getHost();
        if (h == null) return "";
        h = h.toLowerCase(Locale.ROOT);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static String path(URI u) {
        String p = u.getRawPath();
        if (p == null) return "";
        int n = p.length();
        while (n > 0 && p.charAt(n - 1) == '/') n--;
        return p.substring(0, n);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
