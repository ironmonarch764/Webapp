package com.myweb.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Aturan fitur Page tanpa API Android (agar bisa diuji di JVM): batas page aktif menurut spesifikasi HP,
 * waktu hibernasi/tutup otomatis, dan pemilihan page yang harus dihibernasi/ditutup.
 */
public final class PagePolicy {

    /** Jumlah page maksimal (aktif + hibernasi). */
    public static final int MAX_TOTAL = 30;
    /** Batas atas yang boleh diisi pengguna untuk page aktif sekaligus. */
    public static final int HARD_MAX_LIVE = 12;
    /** Pilihan "Hibernasi setelah" (menit); indeks 1..7 di pengaturan, 0 = otomatis. */
    public static final int[] HIB_MINUTES = {1, 2, 3, 5, 10, 15, 30};
    /** Pilihan "Tutup setelah hibernasi" (jam); indeks 1..7 di pengaturan. */
    public static final int[] CLOSE_HOURS = {1, 3, 6, 12, 24, 48, 72};

    private PagePolicy() { }

    /**
     * Batas aman page aktif sekaligus menurut RAM total (MB, seperti dilaporkan sistem: HP 4 GB biasanya ~3,7 GB).
     * Sengaja kecil: tiap page aktif = satu WebView dengan memori sendiri.
     */
    public static int recommendedLive(long totalMb, boolean lowRamDevice) {
        int n;
        if (totalMb <= 2200) n = 1;
        else if (totalMb <= 3300) n = 2;
        else if (totalMb <= 4600) n = 3;
        else if (totalMb <= 6600) n = 4;
        else if (totalMb <= 9000) n = 5;
        else n = 6;
        if (lowRamDevice) n = 1;
        return n;
    }

    /** pref 0 (atau kurang) = otomatis (sama dengan saran), selain itu 1..HARD_MAX_LIVE. */
    public static int liveLimit(int pref, int recommended) {
        if (pref <= 0) return recommended;
        return Math.min(Math.max(pref, 1), HARD_MAX_LIVE);
    }

    /** Hibernasi otomatis: HP berRAM kecil lebih cepat. */
    public static int autoHibernateMinutes(int recommended) {
        return recommended <= 2 ? 3 : 5;
    }

    /** idx 0 = otomatis, 1..7 = HIB_MINUTES. */
    public static int hibernateMinutes(int idx, int recommended) {
        int i = Math.min(Math.max(idx, 0), HIB_MINUTES.length);
        return i == 0 ? autoHibernateMinutes(recommended) : HIB_MINUTES[i - 1];
    }

    /** idx 1..7 = CLOSE_HOURS (di luar rentang dijepit). */
    public static int closeHours(int idx) {
        int i = Math.min(Math.max(idx, 1), CLOSE_HOURS.length);
        return CLOSE_HOURS[i - 1];
    }

    /** Berapa page aktif yang melebihi batas aman (untuk peringatan). */
    public static boolean exceedsSafe(int liveCount, int recommended) {
        return liveCount > recommended;
    }

    /**
     * Page aktif yang harus dihibernasi agar jumlah aktif tidak melebihi limit: yang paling lama tidak dilihat dulu.
     * Page yang sedang dibuka (currentIdx) tidak pernah dipilih. Hasil = indeks.
     */
    public static List<Integer> overLimit(long[] lastActive, boolean[] live, int currentIdx, int limit) {
        List<Integer> out = new ArrayList<Integer>();
        int liveCount = 0;
        for (int i = 0; i < live.length; i++) if (live[i]) liveCount++;
        int excess = liveCount - Math.max(limit, 1);
        while (excess > 0) {
            int best = -1;
            for (int i = 0; i < live.length; i++) {
                if (!live[i] || i == currentIdx || out.contains(i)) continue;
                if (best < 0 || lastActive[i] < lastActive[best]) best = i;
            }
            if (best < 0) break;
            out.add(best);
            excess--;
        }
        return out;
    }

    /** Page aktif (bukan yang sedang dibuka) yang sudah lebih lama dari idleMs sejak terakhir dilihat. */
    public static List<Integer> idleToHibernate(long[] lastActive, boolean[] live, int currentIdx, long now, long idleMs) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < live.length; i++) {
            if (!live[i] || i == currentIdx) continue;
            if (now - lastActive[i] >= idleMs) out.add(i);
        }
        return out;
    }

    /**
     * Page hibernasi yang sudah lebih lama dari closeMs sejak masuk hibernasi. Tidak pernah memilih page yang sedang
     * dibuka dan selalu menyisakan minimal satu page.
     */
    public static List<Integer> expiredToClose(long[] hibernatedAt, boolean[] live, int currentIdx, long now, long closeMs) {
        List<Integer> out = new ArrayList<Integer>();
        int total = live.length;
        for (int i = 0; i < total; i++) {
            if (live[i] || i == currentIdx) continue;
            if (hibernatedAt[i] > 0 && now - hibernatedAt[i] >= closeMs) {
                if (total - out.size() <= 1) break;
                out.add(i);
            }
        }
        return out;
    }

    /** Indeks tujuan bila item di index digeser dir (-1/+1); -1 bila sudah di ujung. */
    public static int moveTarget(int index, int dir, int size) {
        int j = index + (dir < 0 ? -1 : 1);
        if (index < 0 || index >= size || j < 0 || j >= size) return -1;
        return j;
    }

    /** Page yang dibuka setelah page di closedIndex ditutup (size = jumlah SESUDAH ditutup); -1 bila kosong. */
    public static int nextAfterClose(int closedIndex, int sizeAfter) {
        if (sizeAfter <= 0) return -1;
        return Math.min(closedIndex, sizeAfter - 1);
    }

    /**
     * Urutan situs: yang disematkan tampil dulu. Geser hanya di dalam kelompoknya (sematan / bukan). pinned[] = urutan
     * daftar penuh. Hasil = indeks daftar penuh yang ditukar dengan index, atau -1 bila sudah di ujung kelompok.
     */
    /**
     * Pindahkan elemen dari indeks from ke indeks to (hasil akhirnya berada di to, elemen lain bergeser).
     * Dipakai urut tekan-tahan-geser. false bila indeks salah atau tidak ada perubahan.
     */
    public static <T> boolean moveToIndex(java.util.List<T> list, int from, int to) {
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size() || from == to) return false;
        T t = list.remove(from);
        list.add(to, t);
        return true;
    }

    public static int siteSwapTarget(boolean[] pinned, int index, int dir) {
        if (index < 0 || index >= pinned.length) return -1;
        int step = dir < 0 ? -1 : 1;
        int j = index + step;
        while (j >= 0 && j < pinned.length && pinned[j] != pinned[index]) j += step;
        return (j < 0 || j >= pinned.length) ? -1 : j;
    }
}
