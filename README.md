# Web App

Aplikasi Android (Kotlin + WebView) untuk membaca situs web favorit dalam layar penuh, dengan pemblokir iklan,
perlindungan privasi, gulir otomatis, dan banyak halaman (Page) seperti di peramban. Dibuat ringan: tanpa
pustaka pihak ketiga selain AndroidX.

- Paket: `com.myweb.app`
- Android 7.0 (API 24) ke atas, target API 34
- Bahasa antarmuka: Indonesia

## Fitur

**Membaca**
- Layar penuh dengan tombol setengah lonjong di tepi kanan: Menu, Penyesuaian warna, Gulir, dan Page. Situs, Page, dan Penyesuaian warna terbuka sebagai panel melayang (halaman tetap terlihat, panel bisa digeser).
- Gulir otomatis (kecepatan diatur) dan gulir ketuk-tepi (ketuk tepi layar untuk turun/naik satu layar).
- Lanjut membaca: halaman dan posisi terakhir diingat, bahkan setelah aplikasi dimatikan sistem.
- Pramuat gambar di depan posisi baca, menyesuaikan koneksi dan RAM.
- Penyesuaian warna (kecerahan, kontras, saturasi, hangat) dan efek gambar (haluskan, ketajaman).
- Tarik ke bawah untuk menyegarkan.

**Page (tab)**
- Buka beberapa halaman sekaligus; urutan diatur sendiri (▲▼). Bisa dimatikan di Pengaturan > Page.
- Saat membuka situs tersimpan atau alamat baru muncul pilihan **Page sekarang** atau **Page baru**.
- **Hibernasi:** page yang lama tidak dibuka dibekukan (WebView dibuang, alamat, judul, dan posisi baca tetap
  tersimpan) lalu dimuat ulang saat dibuka. Page di latar juga dihibernasi saat sistem melaporkan memori menipis.
- **Tutup otomatis:** page yang lama hibernasi ditutup sendiri (bisa dimatikan, minimal satu page tersisa).
- **Batas page aktif** menyesuaikan RAM HP (bisa diatur sendiri, ada peringatan bila melebihi batas aman).
- Setelah aplikasi crash atau proses render mati, semua page kembali sebagai hibernasi kecuali yang terakhir dibuka.

**Situs tersimpan**
- Tampilan daftar atau kotak, dengan logo (favicon) tiap situs.
- Tombol Edit: sematkan situs, atur urutan, ubah nama/alamat, hapus.
- Simpan halaman yang sedang dibuka, atau tulis alamat sendiri.

**Privasi dan keamanan**
- Pemblokir iklan dan pelacak (daftar filter diperbarui berkala), pemblokir popup, pembersih parameter pelacak,
  perlindungan tautan AMP, GPC, perlindungan sidik jari dasar, blokir cookie pihak ketiga, paksa HTTPS.
- DNS aman (DNS-over-HTTPS) lewat proxy lokal: Cloudflare, Google, Quad9, atau alamat sendiri (format standar RFC 8484 maupun JSON, dipilih otomatis).
- Penyembunyi elemen iklan: tandai bagian halaman yang mengganggu.
- Masuk dengan Google di dalam aplikasi, dan daftar sesi situs untuk keluar per situs.
- Log diagnosa lokal (tanpa alamat situs, cookie, atau isi halaman) dan spanduk merah saat aplikasi sebelumnya berhenti.

## Logo situs

Logo diambil dari ikon yang diterima WebView saat halaman dibuka, atau dari `https://situs/favicon.ico` milik situs
itu sendiri. Tidak ada layanan pihak ketiga, jadi daftar situs Anda tidak dikirim ke siapa pun. Situs tanpa logo
tampil sebagai huruf awal pada lingkaran berwarna.

## Membangun APK

Build dijalankan oleh GitHub Actions (`.github/workflows/build.yml`) setiap push, atau manual lewat
**Actions > Build APK > Run workflow**. Hasilnya ada di artefak `WebApp-apk`.

Workflow menandatangani APK dengan kunci milik Anda. Isi empat secret di
**Settings > Secrets and variables > Actions**:

| Secret | Isi |
| --- | --- |
| `KEYSTORE_BASE64` | berkas keystore (`.jks`) dalam base64 |
| `KEYSTORE_PASSWORD` | sandi keystore |
| `KEY_ALIAS` | alias kunci |
| `KEY_PASSWORD` | sandi kunci |

Tanpa keempat secret itu, langkah "Check signing secrets" menghentikan workflow dengan pesan yang jelas.

Membuat keystore baru:

```bash
keytool -genkeypair -v -keystore webapp.jks -alias webapp -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 webapp.jks   # tempel hasilnya ke secret KEYSTORE_BASE64
```

> Simpan keystore dengan aman dan **jangan di-commit** (`*.jks` sudah ada di `.gitignore`). Mengganti kunci membuat
> APK baru tidak bisa menimpa aplikasi yang sudah terpasang.

Build lokal (JDK 17, Android SDK, Gradle 8.7): `gradle assembleRelease`. Tanpa secret, build lokal memakai kunci debug.

## Pengujian

Logika murni ditulis dalam Java tanpa API Android supaya bisa diuji di JVM dan dijalankan otomatis oleh workflow
sebelum APK dibuat:

| Uji | Isi |
| --- | --- |
| `FilterEngineSelfTest` | parser dan evaluasi daftar filter iklan |
| `ReadingSelfTest` | posisi baca dan matematika gulir |
| `DohResolverSelfTest` | resolver DNS-over-HTTPS |
| `UrlRulesSelfTest` | penulisan ulang alamat (AMP, parameter pelacak, HTTPS) |
| `PagePolicySelfTest` | batas page menurut RAM, hibernasi, tutup otomatis, urutan |

Ada juga `app/src/test/js/reading-flow.test.js` (Node + Playwright) untuk alur pemulihan posisi baca, dijalankan manual.

## Struktur kode

```
app/src/main/java/com/myweb/app/
  MainActivity.kt        activity utama, klien WebView, menu, pengaturan
  Pages.kt, PagePolicy   Page: data, penyimpanan, batas, hibernasi, tampilan daftar
  Sites.kt, Favicons.kt  situs tersimpan (daftar/kotak, edit) dan logo situs
  QuickTools.kt          tombol di tepi kanan
  FabController.kt       ketuk tepi / tampil-sembunyi tombol
  AutoScroll.kt          gulir otomatis
  FilterEngine.java      mesin filter iklan   PublicSuffix.java  domain terdaftar
  DnsProxy.kt, DohResolver.java   DNS aman
  Preload.kt             pramuat gambar dan pemulihan posisi baca
  Diag.kt                log dan diagnosa
CATATAN_SESI.md          catatan perubahan tiap sesi
```

## Lisensi

Belum ditentukan. Tambahkan berkas `LICENSE` sesuai pilihan Anda.
