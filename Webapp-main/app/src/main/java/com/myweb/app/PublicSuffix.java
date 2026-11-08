package com.myweb.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;

/**
 * Mengenali "registrable domain" (eTLD+1) memakai algoritma Public Suffix List.
 * Tanpa dependensi. Daftar lengkap diunduh aplikasi (psl.dat); sebelum itu dipakai daftar bawaan
 * yang lebih kecil (BUILTIN) sehingga hasilnya tetap masuk akal saat offline.
 *
 * Hanya memakai API Java 8 / Android API 24.
 */
public final class PublicSuffix {

    /** PSL asli berisi ribuan aturan; file di bawah ini dianggap rusak (HTML/terpotong). */
    static final int MIN_REAL_RULES = 1000;

    private static final String BUILTIN =
        // ccTLD dengan tingkat kedua yang umum
        "co.uk\norg.uk\nme.uk\nltd.uk\nplc.uk\nnet.uk\nsch.uk\nac.uk\ngov.uk\nnhs.uk\npolice.uk\nmod.uk\n" +
        "com.au\nnet.au\norg.au\nedu.au\ngov.au\nasn.au\nid.au\n" +
        "co.nz\nnet.nz\norg.nz\nac.nz\ngovt.nz\ngeek.nz\ngen.nz\nschool.nz\n" +
        "co.jp\nor.jp\nne.jp\nac.jp\nad.jp\ned.jp\ngo.jp\ngr.jp\nlg.jp\n" +
        "com.cn\nnet.cn\norg.cn\ngov.cn\nedu.cn\nac.cn\n" +
        "com.hk\nnet.hk\norg.hk\ngov.hk\nedu.hk\nidv.hk\n" +
        "com.tw\nnet.tw\norg.tw\nedu.tw\ngov.tw\nidv.tw\n" +
        "co.kr\nor.kr\nne.kr\ngo.kr\nre.kr\npe.kr\nac.kr\nhs.kr\nms.kr\nes.kr\nsc.kr\n" +
        "co.in\nnet.in\norg.in\nac.in\ngov.in\nres.in\nedu.in\nnic.in\nfirm.in\ngen.in\nind.in\n" +
        "co.id\nor.id\nweb.id\nac.id\ngo.id\nsch.id\nmy.id\nnet.id\nmil.id\ndesa.id\nbiz.id\nponpes.id\n" +
        "com.my\nnet.my\norg.my\ngov.my\nedu.my\nmil.my\nname.my\n" +
        "com.sg\nnet.sg\norg.sg\ngov.sg\nedu.sg\nper.sg\n" +
        "co.th\nor.th\nin.th\nac.th\ngo.th\nmi.th\nnet.th\n" +
        "com.vn\nnet.vn\norg.vn\ngov.vn\nedu.vn\nac.vn\nbiz.vn\ninfo.vn\nname.vn\npro.vn\nhealth.vn\nint.vn\n" +
        "com.ph\nnet.ph\norg.ph\ngov.ph\nedu.ph\nngo.ph\nmil.ph\ni.ph\n" +
        "com.br\nnet.br\norg.br\ngov.br\nedu.br\nmil.br\nart.br\nblog.br\neco.br\ntv.br\n" +
        "com.ar\nnet.ar\norg.ar\ngov.ar\nedu.ar\nint.ar\nmil.ar\n" +
        "com.mx\norg.mx\nnet.mx\nedu.mx\ngob.mx\n" +
        "co.za\norg.za\nnet.za\ngov.za\nac.za\nedu.za\nweb.za\n" +
        "com.tr\nnet.tr\norg.tr\ngov.tr\nedu.tr\nbiz.tr\ninfo.tr\nname.tr\ntv.tr\nweb.tr\n" +
        "com.ru\nnet.ru\norg.ru\npp.ru\n" +
        "com.ua\nnet.ua\norg.ua\ngov.ua\nedu.ua\nin.ua\n" +
        "co.il\norg.il\nnet.il\nac.il\ngov.il\nmuni.il\n" +
        "com.pk\nnet.pk\norg.pk\nedu.pk\ngov.pk\nweb.pk\n" +
        "com.bd\nnet.bd\norg.bd\nedu.bd\ngov.bd\nac.bd\n" +
        "com.eg\nnet.eg\norg.eg\nedu.eg\ngov.eg\n" +
        "com.sa\nnet.sa\norg.sa\ngov.sa\nedu.sa\nsch.sa\nmed.sa\n" +
        "co.ae\nnet.ae\norg.ae\ngov.ae\nac.ae\nsch.ae\n" +
        "com.ng\nnet.ng\norg.ng\ngov.ng\nedu.ng\nsch.ng\nname.ng\n" +
        "co.ke\nor.ke\nne.ke\ngo.ke\nac.ke\nsc.ke\nme.ke\nmobi.ke\ninfo.ke\n" +
        "uk.com\nus.com\neu.com\nde.com\ncn.com\n" +
        // hosting publik (private section): situs berbeda di bawahnya BUKAN satu situs
        "github.io\ngitlab.io\nbitbucket.io\nblogspot.com\nblogspot.co.id\nblogspot.co.uk\nblogspot.com.br\n" +
        "blogspot.in\nblogspot.jp\nblogspot.de\nblogspot.fr\nblogspot.com.au\nblogspot.sg\nblogspot.my\n" +
        "herokuapp.com\nvercel.app\nnetlify.app\npages.dev\nworkers.dev\nweb.app\nfirebaseapp.com\nappspot.com\n" +
        "azurewebsites.net\ncloudfront.net\ns3.amazonaws.com\nneocities.org\nglitch.me\nrepl.co\nonrender.com\n" +
        "fly.dev\nsurge.sh\nngrok.io\nduckdns.org\nweebly.com\nwixsite.com\n000webhostapp.com\nmyshopify.com\n" +
        "webflow.io\n";

    private static volatile PublicSuffix active = fromRules(BUILTIN);
    private static volatile String loadedSig = "";

    private final HashSet<String> rules;
    private final HashSet<String> wildcards;   // dasar dari aturan "*.dasar"
    private final HashSet<String> exceptions;  // aturan "!nama"

    private PublicSuffix(HashSet<String> rules, HashSet<String> wildcards, HashSet<String> exceptions) {
        this.rules = rules;
        this.wildcards = wildcards;
        this.exceptions = exceptions;
    }

    // ---------- API statis (dipakai aplikasi) ----------

    /** Registrable domain dari host memakai daftar yang aktif. Tidak pernah null. */
    public static String registrableDomain(String host) {
        return active.registrable(host);
    }

    /**
     * Memuat psl.dat bila ada dan valid. Aman dipanggil berulang: file yang sama tidak diparse ulang.
     * Hasil baru hanya dipakai bila berisi cukup aturan; jika tidak, daftar yang sedang aktif dipertahankan.
     */
    public static boolean loadFromFile(File f) {
        try {
            if (f == null || !f.exists()) return false;
            String sig = f.length() + ":" + f.lastModified();
            if (sig.equals(loadedSig)) return true;
            PublicSuffix p = parseFile(f);
            if (p.ruleCount() < MIN_REAL_RULES) return false;
            active = p;
            loadedSig = sig;
            return true;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Untuk memvalidasi unduhan sebelum menimpa psl.dat lama. */
    public static boolean isValidFile(File f) {
        try {
            return f != null && f.exists() && parseFile(f).ruleCount() >= MIN_REAL_RULES;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------- parsing ----------

    /** Membuat instance dari teks aturan format PSL (dipakai juga oleh pengujian). */
    public static PublicSuffix fromRules(String text) {
        try {
            return parse(new BufferedReader(new StringReader(text)));
        } catch (IOException e) {
            return new PublicSuffix(new HashSet<String>(), new HashSet<String>(), new HashSet<String>());
        }
    }

    private static PublicSuffix parseFile(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        try {
            return parse(r);
        } finally {
            r.close();
        }
    }

    private static PublicSuffix parse(BufferedReader r) throws IOException {
        HashSet<String> rules = new HashSet<String>();
        HashSet<String> wild = new HashSet<String>();
        HashSet<String> exc = new HashSet<String>();
        String line;
        while ((line = r.readLine()) != null) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            // aturan hanya dibaca sampai spasi pertama
            for (int i = 0; i < t.length(); i++) {
                if (Character.isWhitespace(t.charAt(i))) { t = t.substring(0, i); break; }
            }
            boolean isExc = t.startsWith("!");
            if (isExc) t = t.substring(1);
            boolean isWild = t.startsWith("*.");
            if (isWild) t = t.substring(2);
            t = toAscii(t.toLowerCase(Locale.ROOT));
            if (t == null || t.isEmpty()) continue;
            if (isExc) exc.add(t);
            else if (isWild) wild.add(t);
            else rules.add(t);
        }
        return new PublicSuffix(rules, wild, exc);
    }

    private static String toAscii(String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) { ascii = false; break; }
        }
        if (ascii) return s;
        try {
            return IDN.toASCII(s, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    int ruleCount() {
        return rules.size() + wildcards.size() + exceptions.size();
    }

    // ---------- inti algoritma ----------

    /**
     * Registrable domain: public suffix + satu label. Kasus khusus:
     * IP dan host tanpa titik dikembalikan apa adanya; host yang ITU SENDIRI public suffix
     * (mis. "co.uk") juga dikembalikan apa adanya supaya pembandingan situs tetap konsisten.
     */
    public String registrable(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return "";
        if (h.charAt(0) == '[' || h.indexOf(':') >= 0) return h;     // IPv6
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) return "";
        if (isIpv4(h)) return h;
        String[] labels = h.split("\\.", -1);
        int n = labels.length;
        if (n < 2) return h;
        for (int i = 0; i < n; i++) {
            if (labels[i].isEmpty()) return h;
        }

        int suffixLen = -1;
        // 1) aturan pengecualian ("!x.y") selalu menang
        for (int i = 0; i < n; i++) {
            if (exceptions.contains(join(labels, i, n))) { suffixLen = n - i - 1; break; }
        }
        // 2) aturan terpanjang yang cocok (biasa atau wildcard)
        if (suffixLen < 0) {
            for (int i = 0; i < n; i++) {
                if (rules.contains(join(labels, i, n))) { suffixLen = n - i; break; }
                if (i + 1 < n && wildcards.contains(join(labels, i + 1, n))) { suffixLen = n - i; break; }
            }
        }
        // 3) aturan bawaan "*": TLD tidak dikenal = satu label
        if (suffixLen < 0) suffixLen = 1;
        if (n <= suffixLen) return h;
        return join(labels, n - suffixLen - 1, n);
    }

    private static String join(String[] labels, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append('.');
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    static boolean isIpv4(String h) {
        String[] p = h.split("\\.", -1);
        if (p.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            String s = p[i];
            if (s.isEmpty() || s.length() > 3) return false;
            int v = 0;
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c < '0' || c > '9') return false;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return false;
        }
        return true;
    }
}
