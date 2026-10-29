package com.myweb.app;

/**
 * Hitungan auto-scroll tanpa API Android (agar bisa diuji di JVM).
 *
 * Kecepatan berbasis WAKTU (selisih frameTimeNanos), bukan jumlah frame, jadi jarak per detik sama di 60/90/120/144 Hz.
 * Sisa pecahan piksel (remainder) dibawa ke frame berikutnya supaya kecepatan pelan tidak membulat jadi 0.
 */
public final class AutoScrollMath {

    /** Selisih waktu antar frame dibatasi agar tidak melompat jauh setelah layar tersendat lama. */
    public static final float MAX_DT = 0.1f;
    /** Di dasar halaman: tunggu segini lama (konten baru bisa menambah tinggi dokumen) sebelum berhenti. */
    public static final long END_WAIT_NS = 2_000_000_000L;
    /** Bila masih ada gambar yang dimuat di ujung halaman, tunggu paling lama segini. */
    public static final long END_WAIT_MAX_NS = 8_000_000_000L;
    /** Jarak antar pertanyaan "masih ada gambar dimuat?" ke halaman saat menunggu di dasar. */
    public static final long CHECK_EVERY_NS = 1_000_000_000L;

    private long last;
    private float rem;
    private long stuckSince;
    private long lastCheck;

    public void reset() {
        last = 0L;
        rem = 0f;
        stuckSince = 0L;
        lastCheck = 0L;
    }

    /** Jumlah piksel yang harus digeser pada frame ini (>= 0). Frame pertama setelah reset() tidak menggeser. */
    public int step(long frameNanos, float pxPerSec) {
        int move = 0;
        if (last != 0L) {
            long d = frameNanos - last;
            if (d < 0L) d = 0L;
            float dt = Math.min(d / 1_000_000_000f, MAX_DT);
            rem += pxPerSec * dt;
            move = (int) rem;
            rem -= move;
        }
        last = frameNanos;
        return move;
    }

    /**
     * Apakah auto-scroll harus berhenti. canScroll=false berarti sudah di dasar. Dasar yang bertahan >= END_WAIT_NS
     * menghentikan auto-scroll, kecuali halaman melaporkan masih ada gambar dimuat (imagesPending): lalu menunggu
     * sampai END_WAIT_MAX_NS. Bila dokumen bertambah tinggi (canScroll true lagi) hitungan menunggu diulang dari nol.
     */
    public boolean shouldStop(long now, boolean canScroll, boolean imagesPending) {
        if (canScroll) {
            stuckSince = 0L;
            lastCheck = 0L;
            return false;
        }
        if (stuckSince == 0L) {
            stuckSince = now;
            return false;
        }
        long w = now - stuckSince;
        if (w >= END_WAIT_MAX_NS) return true;
        return w >= END_WAIT_NS && !imagesPending;
    }

    /** True paling sering sekali per CHECK_EVERY_NS selama di dasar: saatnya menanyakan halaman soal gambar yang dimuat. */
    public boolean wantImageCheck(long now) {
        if (stuckSince == 0L) return false;
        if (now - stuckSince < CHECK_EVERY_NS) return false;
        if (lastCheck != 0L && now - lastCheck < CHECK_EVERY_NS) return false;
        lastCheck = now;
        return true;
    }
}
