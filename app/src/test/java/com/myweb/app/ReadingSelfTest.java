package com.myweb.app;

/**
 * Uji logika auto-scroll (AutoScrollMath) dan posisi membaca (ReadingPos) di JVM, tanpa Android.
 * Jalankan: javac ... && java -cp ... com.myweb.app.ReadingSelfTest  (juga dijalankan oleh build.yml).
 */
public final class ReadingSelfTest {
    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) pass++;
        else { fail++; System.out.println("GAGAL: " + name); }
    }

    private static final long MS = 1_000_000L;

    // Pembangkit acak deterministik untuk jitter frame
    private static long seed = 12345;
    private static int rnd(int n) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % n);
    }

    /** Simulasikan 'seconds' detik pada 'hz' dengan jitter +-jitterMs; kembalikan {totalPx, elapsedSec}. */
    private static double[] simulate(double hz, float pxPerSec, double seconds, double jitterMs) {
        AutoScrollMath m = new AutoScrollMath();
        m.reset();
        double periodNs = 1e9 / hz;
        long t0 = 5_000_000_000L;
        long first = 0;
        long lastT = 0;
        long total = 0;
        for (int i = 0; i * periodNs <= seconds * 1e9; i++) {
            long jit = jitterMs == 0 ? 0 : (long) ((rnd(2001) - 1000) / 1000.0 * jitterMs * MS);
            long t = t0 + (long) (i * periodNs) + jit;
            if (i == 0) first = t;
            if (t <= lastT) t = lastT + 1;
            total += m.step(t, pxPerSec);
            lastT = t;
        }
        return new double[] { total, (lastT - first) / 1e9 };
    }

    private static void testSpeedConsistency() {
        float density = 2.75f;
        float[] dps = { 10f, 28f, 100f, 300f };
        double[] hzs = { 60, 90, 120, 144 };
        for (float dp : dps) {
            float px = dp * density;
            double[] ref = null;
            for (double hz : hzs) {
                double[] r = simulate(hz, px, 10.0, 0.8);
                double expected = px * r[1];
                check("jarak sesuai waktu: " + dp + "dp/s @" + hz + "Hz (selisih " + (r[0] - expected) + ")",
                    Math.abs(r[0] - expected) < 1.5);
                if (ref == null) ref = r;
                else check("sama di semua refresh rate: " + dp + "dp/s @" + hz + "Hz",
                    Math.abs(r[0] / r[1] - ref[0] / ref[1]) < px * 0.01 + 0.3);
            }
        }
        // kecepatan sangat pelan di 120 Hz: tidak membulat jadi 0
        double[] slow = simulate(120, 10f * 2.0f, 10.0, 0);
        check("pelan di 120Hz tetap bergerak (~200px/10s): " + slow[0], slow[0] >= 195 && slow[0] <= 205);
    }

    private static void testHitchAndEdgeCases() {
        AutoScrollMath m = new AutoScrollMath();
        m.reset();
        check("frame pertama tidak menggeser", m.step(1_000_000_000L, 1000f) == 0);
        int mv = m.step(1_016_000_000L, 1000f);
        check("frame normal 16ms @1000px/s = 16px", mv == 16);
        int hitch = m.step(1_016_000_000L + 2_000_000_000L, 1000f);
        check("tersendat 2 detik dibatasi: tidak melompat (" + hitch + ")", hitch <= 101 && hitch >= 99);
        int back = m.step(1_000_000_000L, 1000f);
        check("waktu mundur tidak menghasilkan gerakan negatif", back >= 0);
        m.reset();
        check("setelah reset, frame pertama kembali 0", m.step(9_000_000_000L, 1000f) == 0);
        check("kecepatan 0 tidak bergerak", simulate(60, 0f, 2.0, 0)[0] == 0);
    }

    private static void testEnd() {
        AutoScrollMath m = new AutoScrollMath();
        m.reset();
        long t = 10_000_000_000L;
        check("masih bisa scroll: lanjut", !m.shouldStop(t, true, false));
        check("mulai di dasar: belum berhenti", !m.shouldStop(t + 100 * MS, false, false));
        check("di dasar 1.9 dtk: belum berhenti", !m.shouldStop(t + 100 * MS + 1900 * MS, false, false));
        check("di dasar 2.0 dtk: berhenti", m.shouldStop(t + 100 * MS + 2000 * MS, false, false));

        m.reset();
        long b = 20_000_000_000L;
        m.shouldStop(b, false, false);
        check("gambar masih dimuat: tidak berhenti di 2 dtk", !m.shouldStop(b + 2500 * MS, false, true));
        check("gambar masih dimuat: tidak berhenti di 7.9 dtk", !m.shouldStop(b + 7900 * MS, false, true));
        check("batas tunggu maksimum 8 dtk tercapai", m.shouldStop(b + 8000 * MS, false, true));

        m.reset();
        m.shouldStop(b, false, false);
        check("pending lalu selesai: berhenti setelah >= 2 dtk", m.shouldStop(b + 3000 * MS, false, false));

        // dokumen bertambah tinggi: hitungan menunggu diulang dari nol
        m.reset();
        m.shouldStop(b, false, false);
        check("sebelum tambah tinggi (1.5 dtk) belum berhenti", !m.shouldStop(b + 1500 * MS, false, false));
        check("dokumen bertambah tinggi: lanjut", !m.shouldStop(b + 1600 * MS, true, false));
        check("dasar lagi: hitungan baru, belum berhenti", !m.shouldStop(b + 1700 * MS, false, false));
        check("1.9 dtk sejak dasar baru: belum berhenti", !m.shouldStop(b + 1700 * MS + 1900 * MS, false, false));
        check("2.0 dtk sejak dasar baru: berhenti", m.shouldStop(b + 1700 * MS + 2000 * MS, false, false));

        // frekuensi pertanyaan "gambar masih dimuat?"
        m.reset();
        long c = 30_000_000_000L;
        check("tidak bertanya saat belum di dasar", !m.wantImageCheck(c));
        m.shouldStop(c, false, false);
        check("tidak bertanya sebelum 1 dtk", !m.wantImageCheck(c + 900 * MS));
        check("bertanya di 1 dtk", m.wantImageCheck(c + 1000 * MS));
        check("tidak bertanya lagi dalam 1 dtk", !m.wantImageCheck(c + 1500 * MS));
        check("bertanya lagi di 2 dtk", m.wantImageCheck(c + 2000 * MS));
        m.shouldStop(c + 2100 * MS, true, false);
        check("bisa scroll lagi: tidak bertanya", !m.wantImageCheck(c + 3500 * MS));
    }

    private static void testReadingPos() {
        check("clamp negatif", ReadingPos.clampY(-5) == 0);
        check("clamp normal", ReadingPos.clampY(1234) == 1234);
        check("clamp raksasa", ReadingPos.clampY(Integer.MAX_VALUE) == ReadingPos.MAX_Y);

        check("sama persis", ReadingPos.sameDocument("https://a.com/b/c", "https://a.com/b/c"));
        check("http vs https sama", ReadingPos.sameDocument("http://a.com/b", "https://a.com/b"));
        check("www diabaikan", ReadingPos.sameDocument("https://www.a.com/b", "https://a.com/b"));
        check("garis miring ujung diabaikan", ReadingPos.sameDocument("https://a.com/b/", "https://a.com/b"));
        check("fragmen diabaikan", ReadingPos.sameDocument("https://a.com/b#x", "https://a.com/b"));
        check("huruf besar host diabaikan", ReadingPos.sameDocument("https://A.COM/b", "https://a.com/b"));
        check("root sama", ReadingPos.sameDocument("https://a.com", "https://a.com/"));
        check("path beda", !ReadingPos.sameDocument("https://a.com/ch-1", "https://a.com/ch-2"));
        check("host beda", !ReadingPos.sameDocument("https://a.com/b", "https://b.com/b"));
        check("subdomain beda", !ReadingPos.sameDocument("https://m.a.com/b", "https://a.com/b"));
        check("query beda", !ReadingPos.sameDocument("https://a.com/b?p=1", "https://a.com/b?p=2"));
        check("query sama", ReadingPos.sameDocument("https://a.com/b?p=1", "https://a.com/b?p=1"));
        check("query ada vs tidak ada", !ReadingPos.sameDocument("https://a.com/b?p=1", "https://a.com/b"));
        check("null a", !ReadingPos.sameDocument(null, "https://a.com"));
        check("null b", !ReadingPos.sameDocument("https://a.com", null));
        check("sampah tidak melempar", !ReadingPos.sameDocument("ini bukan url", "https://a.com"));
        check("halaman error aplikasi", !ReadingPos.sameDocument("file:///android_asset/error.html", "https://a.com"));
        boolean threw = false;
        try { ReadingPos.sameDocument("https://a.com/b c", "https://a.com/b c"); } catch (RuntimeException e) { threw = true; }
        check("spasi di URL tidak melempar", !threw);
    }

    public static void main(String[] args) {
        testSpeedConsistency();
        testHitchAndEdgeCases();
        testEnd();
        testReadingPos();
        System.out.println("Lulus: " + pass + "  Gagal: " + fail);
        if (fail > 0) System.exit(1);
    }
}
