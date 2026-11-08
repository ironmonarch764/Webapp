package com.myweb.app;

import java.util.Arrays;
import java.util.List;

/** Uji aturan Page (jalankan: javac PagePolicy.java PagePolicySelfTest.java; java com.myweb.app.PagePolicySelfTest). */
public class PagePolicySelfTest {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) pass++; else { fail++; System.out.println("GAGAL: " + name); }
    }

    static void eq(String name, Object a, Object b) {
        boolean ok = (a == null) ? b == null : a.equals(b);
        if (!ok) System.out.println("  diharapkan " + b + " dapat " + a);
        check(name, ok);
    }

    public static void main(String[] args) {
        // batas aman menurut RAM
        eq("1,5GB", PagePolicy.recommendedLive(1500, false), 1);
        eq("2GB", PagePolicy.recommendedLive(2000, false), 1);
        eq("3GB", PagePolicy.recommendedLive(2900, false), 2);
        eq("4GB (~3,7)", PagePolicy.recommendedLive(3700, false), 3);
        eq("6GB (~5,5)", PagePolicy.recommendedLive(5500, false), 4);
        eq("8GB (~7,4)", PagePolicy.recommendedLive(7400, false), 5);
        eq("12GB (~11)", PagePolicy.recommendedLive(11000, false), 6);
        eq("RAM rendah ditandai sistem", PagePolicy.recommendedLive(8000, true), 1);
        for (long mb = 500; mb < 20000; mb += 100) {
            int r = PagePolicy.recommendedLive(mb, false);
            check("rentang aman " + mb, r >= 1 && r <= 6);
            if (mb > 500) check("tidak turun saat RAM naik " + mb, r >= PagePolicy.recommendedLive(mb - 100, false));
        }

        // batas pilihan pengguna
        eq("otomatis", PagePolicy.liveLimit(0, 3), 3);
        eq("negatif = otomatis", PagePolicy.liveLimit(-5, 3), 3);
        eq("kustom 2", PagePolicy.liveLimit(2, 3), 2);
        eq("kustom 8 > saran", PagePolicy.liveLimit(8, 3), 8);
        eq("dijepit atas", PagePolicy.liveLimit(99, 3), PagePolicy.HARD_MAX_LIVE);
        check("peringatan melebihi", PagePolicy.exceedsSafe(4, 3));
        check("tanpa peringatan sama", !PagePolicy.exceedsSafe(3, 3));

        // waktu
        eq("hibernasi otomatis RAM kecil", PagePolicy.hibernateMinutes(0, 2), 3);
        eq("hibernasi otomatis RAM besar", PagePolicy.hibernateMinutes(0, 4), 5);
        eq("hibernasi idx 1", PagePolicy.hibernateMinutes(1, 4), 1);
        eq("hibernasi idx 7", PagePolicy.hibernateMinutes(7, 4), 30);
        eq("hibernasi idx di luar", PagePolicy.hibernateMinutes(99, 4), 30);
        eq("tutup idx 5 = 24 jam", PagePolicy.closeHours(5), 24);
        eq("tutup idx 0 dijepit", PagePolicy.closeHours(0), 1);
        eq("tutup idx 9 dijepit", PagePolicy.closeHours(9), 72);

        // kelebihan batas: yang paling lama dulu, bukan yang sedang dibuka
        long[] la = {100, 50, 300, 10, 200};
        boolean[] live = {true, true, true, true, true};
        eq("kelebihan 2 (limit 3), cur=3", PagePolicy.overLimit(la, live, 3, 3), Arrays.asList(1, 0));
        eq("cur tidak pernah dipilih", PagePolicy.overLimit(la, live, 1, 1), Arrays.asList(3, 0, 4, 2));
        eq("tidak berlebih", PagePolicy.overLimit(la, live, 0, 5), Arrays.<Integer>asList());
        boolean[] live2 = {true, false, true, false, false};
        eq("hibernasi tidak dihitung", PagePolicy.overLimit(la, live2, 0, 2), Arrays.<Integer>asList());
        eq("limit 0 diperlakukan 1", PagePolicy.overLimit(la, live2, 2, 0), Arrays.asList(0));
        eq("hanya satu page aktif", PagePolicy.overLimit(new long[]{1}, new boolean[]{true}, 0, 1), Arrays.<Integer>asList());

        // idle
        long now = 1_000_000_000_000L;
        long[] la2 = {now - 10 * 60_000L, now - 1 * 60_000L, now - 20 * 60_000L, now - 6 * 60_000L};
        boolean[] live3 = {true, true, true, false};
        eq("idle 5 menit, cur=2", PagePolicy.idleToHibernate(la2, live3, 2, now, 5 * 60_000L), Arrays.asList(0));
        eq("idle: cur tidak dihibernasi", PagePolicy.idleToHibernate(la2, live3, 0, now, 5 * 60_000L), Arrays.asList(2));
        eq("idle: sudah hibernasi dilewati", PagePolicy.idleToHibernate(la2, live3, 1, now, 1_000L), Arrays.asList(0, 2));

        // tutup otomatis
        long h = 3_600_000L;
        long[] hz = {now - 30 * h, 0, now - 2 * h, now - 50 * h};
        boolean[] lv = {false, true, false, false};
        eq("tutup yang kedaluwarsa", PagePolicy.expiredToClose(hz, lv, 1, now, 24 * h), Arrays.asList(0, 3));
        eq("page aktif tidak ditutup", PagePolicy.expiredToClose(hz, lv, 3, now, 24 * h), Arrays.asList(0));
        eq("sisakan minimal satu", PagePolicy.expiredToClose(new long[]{now - 99 * h, now - 99 * h}, new boolean[]{false, false}, 0, now, h), Arrays.asList(1));
        eq("satu-satunya tidak ditutup", PagePolicy.expiredToClose(new long[]{now - 99 * h}, new boolean[]{false}, -1, now, h), Arrays.<Integer>asList());
        eq("semua hibernasi tanpa cur, sisakan satu", PagePolicy.expiredToClose(new long[]{now - 99 * h, now - 99 * h, now - 99 * h}, new boolean[]{false, false, false}, -1, now, h), Arrays.asList(0, 1));
        eq("baru hibernasi tidak ditutup", PagePolicy.expiredToClose(new long[]{now - 1000, now - 1000}, new boolean[]{false, false}, 0, now, h), Arrays.<Integer>asList());

        // urutan
        eq("geser atas dari 0", PagePolicy.moveTarget(0, -1, 3), -1);
        eq("geser bawah dari akhir", PagePolicy.moveTarget(2, 1, 3), -1);
        eq("geser bawah", PagePolicy.moveTarget(0, 1, 3), 1);
        eq("geser atas", PagePolicy.moveTarget(2, -1, 3), 1);
        eq("indeks buruk", PagePolicy.moveTarget(5, 1, 3), -1);
        eq("setelah tutup tengah", PagePolicy.nextAfterClose(1, 2), 1);
        eq("setelah tutup terakhir", PagePolicy.nextAfterClose(2, 2), 1);
        eq("setelah tutup semua", PagePolicy.nextAfterClose(0, 0), -1);

        // urutan situs dengan sematan: [A(p), B, C(p), D, E]
        boolean[] pin = {true, false, true, false, false};
        eq("sematan A turun -> C", PagePolicy.siteSwapTarget(pin, 0, 1), 2);
        eq("sematan C naik -> A", PagePolicy.siteSwapTarget(pin, 2, -1), 0);
        eq("sematan C turun: ujung kelompok", PagePolicy.siteSwapTarget(pin, 2, 1), -1);

        // urut tekan-tahan-geser: pindah ke indeks tertentu
        java.util.List<String> mv = new java.util.ArrayList<>(java.util.Arrays.asList("A", "B", "C", "D"));
        check("moveToIndex 0->2 berhasil", PagePolicy.moveToIndex(mv, 0, 2));
        eq("moveToIndex 0->2 hasil", mv.toString(), "[B, C, A, D]");
        check("moveToIndex 3->1 berhasil", PagePolicy.moveToIndex(mv, 3, 1));
        eq("moveToIndex 3->1 hasil", mv.toString(), "[B, D, C, A]");
        check("moveToIndex ke ujung depan", PagePolicy.moveToIndex(mv, 2, 0));
        eq("moveToIndex ke ujung depan hasil", mv.toString(), "[C, B, D, A]");
        check("moveToIndex ke ujung belakang", PagePolicy.moveToIndex(mv, 0, 3));
        eq("moveToIndex ke ujung belakang hasil", mv.toString(), "[B, D, A, C]");
        check("moveToIndex indeks sama = tidak berubah", !PagePolicy.moveToIndex(mv, 1, 1));
        check("moveToIndex indeks di luar rentang ditolak", !PagePolicy.moveToIndex(mv, -1, 2) && !PagePolicy.moveToIndex(mv, 0, 4) && !PagePolicy.moveToIndex(mv, 4, 0));
        eq("moveToIndex penolakan tidak mengubah daftar", mv.toString(), "[B, D, A, C]");
        eq("B turun -> D", PagePolicy.siteSwapTarget(pin, 1, 1), 3);
        eq("B naik: ujung kelompok", PagePolicy.siteSwapTarget(pin, 1, -1), -1);
        eq("E naik -> D", PagePolicy.siteSwapTarget(pin, 4, -1), 3);
        eq("E turun: ujung", PagePolicy.siteSwapTarget(pin, 4, 1), -1);
        eq("indeks buruk situs", PagePolicy.siteSwapTarget(pin, 9, 1), -1);

        System.out.println("PagePolicySelfTest: " + pass + " lulus, " + fail + " gagal");
        if (fail > 0) System.exit(1);
    }
}
