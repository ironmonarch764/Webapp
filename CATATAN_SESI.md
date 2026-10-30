# Catatan sesi perbaikan WebApp

## Sesi 1 (audit dasar) - selesai
Diubah (semua kecil dan terisolasi):
- MainActivity.kt: DownloadListener dibungkus try/catch (cegah crash untuk blob:/data:); fallback bila restoreState gagal (cegah layar kosong); siteBase dibuat @Volatile.
- DnsProxy.kt: socket klien ditutup di jalur return awal (cegah kebocoran socket).
- MenuSheet.kt: dismiss() sheet lama dibungkus try/catch (cegah crash bila Activity lama sudah dihancurkan).

Belum diverifikasi dengan build: lingkungan sesi tidak punya Android SDK/Gradle/kotlinc. Jalankan workflow "Build APK" untuk memastikan.

## Temuan untuk sesi berikutnya (BELUM diubah)
1. AdData/MainActivity.parseLists: kondisi `cssCount < 0` membuat aturan kosmetik (##) tidak pernah dipakai. Kemungkinan sengaja - konfirmasi dulu.
2. [SELESAI] applyImageFx kini diterapkan ulang di onPageFinished bila slider haluskan/ketajaman tidak 0.
3. WebView.onPause()/onResume() tidak dipanggil, jadi video/audio/timer halaman tetap jalan di latar belakang. Perlu diuji di HP karena berdekatan dengan penyimpanan posisi baca.
4. Lanjut baca (resumeOrLoad) tidak jalan untuk situs yang redirect ke domain dasar berbeda dari alamat tersimpan.
5. WebView sementara di onCreateWindow (mode blokir pindah domain) tidak di-destroy bila popup tidak pernah memicu shouldOverrideUrlLoading.
6. DnsProxy.stop() di onDestroy bersifat global; Activity lama bisa mematikan proxy milik Activity baru (race langka).
7. [SELESAI sebagian] Keystore sudah dipindah ke GitHub Secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) dan build.yml memulihkannya. Sisa: keystore.yml masih ada (membuat kunci BARU dengan sandi publik dan meng-commit-nya) sebaiknya dihapus; kunci lama dan sandinya masih terbaca di riwayat git.
8. Tidak ada pengujian dan tidak ada gradle wrapper. [SELESAI] .gitignore ditambahkan (jks tidak diabaikan).
9. Tidak terpakai: izin ACCESS_NETWORK_STATE, ic_launcher.xml, konstanta CHROME_UA.

## Sesi 2 (keamanan dan stabilitas) - selesai
Diubah:
- MainActivity.kt: token sekali pakai untuk bridge pemilih elemen (addRule/pickerDone); file: hanya untuk halaman error aplikasi, content: diblokir; allowFileAccess/allowContentAccess dimatikan; http->https dinaikkan untuk situs tersimpan https (dengan pengaman perulangan); peringatan sekali per host untuk halaman HTTP; onReceivedSslError selalu cancel + toast; onRenderProcessGone (utama, popup, temp) agar app tidak mati saat renderer kehabisan memori; WebView sementara popup-diblokir kini benar-benar di-destroy (view.post tidak pernah jalan pada view yang tidak terpasang); guard perulangan halaman error; callback applyDns tidak jalan setelah Activity dihancurkan; unduhan hanya http/https.
- AdData.kt: PICKER_JS memakai token. FabController.kt: release(). MenuSheet.kt: dismissMenuSheet().
- app/build.gradle.kts: signing hanya dari environment; lokal tanpa secret memakai kunci debug; di GitHub Actions gagal dengan pesan jelas bila secret kurang.
- build.yml: secret hanya ke langkah yang butuh, cek secret, permissions read-only, if-no-files-found: error.
- keystore.yml dihapus (membuat kunci baru dengan sandi tertanam). .gitignore: *.jks, *.keystore dll.
- AndroidManifest.xml + res/xml/backup_rules.xml + data_extraction_rules.xml: data WebView (cookie/sesi) tidak ikut cadangan cloud.
Belum diverifikasi dengan build/perangkat (lihat laporan sesi).

## Sisa (untuk keputusan Anda / sesi berikutnya)
- Kunci lama masih ada di riwayat git repo publik. Mengganti kunci memutus update di atas app lama.
- Cookie pihak ketiga tetap aktif (dibutuhkan login Google/popup).
- usesCleartextTraffic tetap true (alamat situs bebas diisi, bisa http).
- WebView.onPause()/onResume() belum dipanggil (butuh uji di HP).

## Sesi 3 (ad blocker, parser, domain) - selesai
Catatan: build.yml dan .gitignore hasil Sesi 2 ternyata belum terpasang di repo (file berawalan titik/alur unggah); versi Sesi 2 dipulihkan di sini dan ditambah langkah tes.
Diubah:
- FilterEngine.java (baru): parser daftar filter + evaluasi blokir + filter kosmetik; Java murni, diuji.
- PublicSuffix.java (baru): domain terdaftar memakai algoritma Public Suffix List (tanpa dependensi); psl.dat diunduh berkala, ada daftar bawaan untuk offline.
- FilterEngineSelfTest.java (baru, app/src/test): 275 pemeriksaan; dijalankan otomatis di build.yml sebelum build APK.
- MainActivity.kt: parseLists/isAd/baseDomain memakai mesin baru; pengecualian @@; $popup hanya untuk popup/pengalihan; pihak pertama juga dikenali dari alamat tersimpan (configBase); unduhan divalidasi + rename atomik + batas 30 MB; log tanpa data sensitif; hasil parse dipakai bersama antar Activity; menu "Filter kosmetik: ON/OFF" (default OFF = perilaku sebelumnya).
- AdData.kt: PSL_URL.
Keterbatasan: lihat laporan sesi (tidak ada aturan bertipe/path/regex/procedural; kosmetik umum dibatasi 3000 aturan).

## Perbaikan setelah Sesi 3 (laporan: kosmetik ON bikin HP panas dan aplikasi keluar sendiri)
- Penyebab 1: aturan kosmetik UMUM (hingga 3000 selector atribut) dievaluasi ke setiap elemen halaman -> CPU/memori renderer. Kini aturan umum mati (hanya aturan khusus domain, maks 500 aturan / 60 KB per halaman).
- Penyebab 2: pada Sesi 2 saya menutup aplikasi (finish) setelah 3 kali renderer mati beruntun. Kini membuka halaman aman (error.html) dan aplikasi tetap hidup.
- Validasi unduhan daftar diperbaiki (looksLikeList): daftar bergaya ABPindo tidak lagi ditolak terus (yang membuatnya diunduh ulang tiap peluncuran).
- Baru: menu "Info diagnosa" (RAM, penyebab proses berhenti menurut sistem, renderer mati terakhir, crash terakhir) + Diag.kt.
- BELUM TERBUKTI: penyebab app masih keluar saat kosmetik OFF. Minta tangkapan layar "Info diagnosa" setelah kejadian.

## Log aplikasi dan spanduk merah (permintaan pengguna)
- Spanduk merah (ketuk = buka log) muncul bila: renderer WebView mati, aplikasi sebelumnya crash (error tercatat), atau sistem menghentikan proses saat masih di layar (memori habis/crash/ANR; API 30+). Aplikasi tidak lagi menutup diri.
- Log (applog.txt, maks 64 KB, lokal): mulai/tutup aplikasi, RAM, halaman selesai (+RAM), onTrimMemory (10/15/>=60), renderer mati, crash (nama exception + 6 baris stack), gagal muat halaman (kode saja), TLS ditolak (kode saja), unduhan daftar (berhasil/gagal/tidak valid), filter dimuat (jumlah aturan), kosmetik ON/OFF, penyebab proses sebelumnya berhenti.
- TIDAK dicatat: alamat situs, host, cookie, isi halaman.
- Lihat: menu > Info diagnosa > Lihat log (Salin / Hapus), atau ketuk spanduk merah.

## Perbaikan panas (kosmetik ON + muat gambar awal)
- Preload.kt: skrip tidak lagi membaca layout dari gambar pertama tiap 150 ms (mulai dari posisi terakhir), MutationObserver hanya bereaksi bila ada <img> baru (iklan/penghitung yang terus mengubah DOM tidak memicu hitung ulang), berhenti saat halaman tersembunyi, dan gambar display:none (mis. disembunyikan filter kosmetik) dilewati.
- FilterEngine.java: aturan kosmetik per-domain yang bagian kanannya hanya atribut/*/pseudo ([class*="ad"], :not(...)) dibuang (costlySelector) karena dicek ke setiap elemen. Uji ditambahkan (303 pemeriksaan lulus).
- MainActivity.kt: log "kosmetik halaman: N aturan, K KB" per halaman (tanpa host) untuk menilai beban.
- BELUM TERBUKTI di perangkat: log tidak merekam CPU renderer. Bandingkan rasa hangat setelah update; bila masih hangat, kirim log yang memuat baris "kosmetik halaman".

## Sesi 4 (preload, auto-scroll, posisi membaca) - selesai
Diubah:
- Preload.kt: PRELOAD_JS ditulis ulang (status per gambar: sedang diunduh / gagal ke-n; maks 2 unduhan bersamaan; gagal diulang maks 2x dengan jeda; src situs yang sedang dimuat tidak ditimpa; placeholder data:/1px diganti; <picture>; AHEAD turun di 2G/3G/hemat data/RAM kecil; preconnect maks 4 origin; berhenti saat halaman tersembunyi). Baru: RESTORE_JS (pemulihan posisi terbatas waktu, berhenti saat disentuh) dan SAVE_POS_JS.
- AutoScrollMath.java (baru, Java murni, diuji) + AutoScroll.kt: kecepatan berbasis waktu; dt maks 0.1 dtk (sebelumnya 0.05); di dasar tunggu 2 dtk, sampai 8 dtk bila gambar di ujung masih dimuat; berhenti bila WebView lepas dari layar.
- ReadingPos.java (baru, diuji): sameDocument() dan clampY().
- MainActivity.kt: posisi disimpan paling sering tiap 2 dtk saat scroll (bukan hanya onPause); tidak menyimpan posisi sementara (halaman belum complete / pemulihan berjalan); pemulihan hanya untuk dokumen yang sama dengan yang disimpan (bukan halaman perantara), kedaluwarsa 20 dtk; autoScroller.stop() di onDestroy dan saat menu dibuka.
- FabController.kt: parameter opsional onScroll.
- Tes: ReadingSelfTest.java (75 pemeriksaan, ikut build.yml), app/src/test/js/reading-flow.test.js (36 pemeriksaan di Chromium, dijalankan manual dengan node + playwright).
Belum diverifikasi: build Kotlin (tidak ada Android SDK di sesi ini) dan perilaku di perangkat nyata.

## Sesi 5 (DNS-over-HTTPS, proxy DNS, jaringan WebView) - selesai
Masalah terkonfirmasi (dari kode) dan perbaikan:
- NXDOMAIN / "tanpa alamat" / alamat 0.0.0.0 dari DoH dianggap gagal lalu DIAM-DIAM beralih ke DNS sistem (filter DNS kustom bisa ditembus, kueri bocor). Kini jawaban sah dari server pilihan dihormati; cadangan sistem HANYA untuk kegagalan transport (jaringan/TLS/timeout/HTTP bukan 200/isi bukan JSON DNS) dan diberitahukan (toast + log, tanpa nama host; label menu DNS ikut berubah).
- Data jawaban DoH diteruskan ke InetAddress.getByName tanpa validasi: data non-IP memicu pencarian DNS sistem (kebocoran/menggantung). Kini literal IPv4/IPv6 diurai ketat tanpa menyentuh DNS.
- Tanpa status DNS, tanpa batas ukuran respons, JSON longgar (org.json) -> kini JSON ketat, wajib field Status, maks 64 KB, batas total waktu baca, redirect tidak diikuti.
- Cache: TTL tetap 5 menit mengabaikan TTL jawaban; hasil lama dari konfigurasi sebelumnya bisa masuk cache setelah ganti DNS (race); tak terbatas. Kini TTL jawaban (min semua rekaman termasuk CNAME) dibatasi 10 dtk..5 menit, cache negatif 30 dtk, kegagalan 5 dtk, cadangan sistem 30 dtk, LRU 512 entri, satu resolver per konfigurasi (ganti DNS = cache baru + permintaan lama dibatalkan).
- IPv4 diutamakan (dipertahankan), tapi IPv6 tidak pernah dicoba bila A ada namun semua sambungan gagal. Kini AAAA dicari hanya bila A kosong / semua sambungan IPv4 gagal (tanpa tambahan kueri di jalur normal).
- DNS sistem sebagai cadangan tanpa batas waktu; sambungan maks 4 alamat x 10 dtk. Kini cadangan dibatasi 8 dtk, total sambungan 20 dtk, pemutus sirkuit (3 gagal beruntun -> DoH dilewati 30 dtk), kueri sama bersamaan digabung (singleflight).
- DnsProxy.stop() global (temuan #6): Activity lama bisa mematikan proxy Activity baru. Kini stop(owner) hanya oleh pemilik; stopAll() saat memilih DNS sistem. Semua soket aktif dilacak dan ditutup saat proxy berhenti. Port gagal dibuka kini diberitahukan dan override lama dilepas (sebelumnya diam).
- HTTP biasa yang gagal tidak dijawab sama sekali; kini 502/400 seperti CONNECT.
- Alamat DoH kustom hanya dicek awalan https://. Kini divalidasi (URI, host sah, tanpa userinfo/#, port). Alamat tersimpan yang tidak valid diberitahukan (dulu diam-diam DNS sistem tapi label "Kustom"). Label menu menampilkan keadaan nyata (WebView tidak mendukung, gagal dimulai, cadangan aktif). Uji server DoH otomatis setelah memilih DNS.
- Halaman error: "Coba lagi"/"Muat ulang" kini memuat ulang halaman yang gagal (dulu selalu halaman awal); menampilkan penyebab (kode) dan tombol "Pakai DNS sistem" bila DNS kustom aktif.
- normalize(): skema huruf besar dan karakter kontrol ditangani.
File: DohResolver.java (baru, diuji), DohResolverSelfTest.java (209 pemeriksaan, ikut build.yml), DnsProxy.kt, MainActivity.kt, Diag.kt (baris DNS di Info diagnosa), error.html, build.yml.
Belum diverifikasi: build Kotlin dan perilaku di perangkat (tidak ada Kotlin/Android SDK di sesi ini).
Keterbatasan: lihat laporan sesi (addDirect, DoH hanya JSON, nama LAN hanya lewat DNS sistem, trafik non-WebView memakai DNS sistem).
