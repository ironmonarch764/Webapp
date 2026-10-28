package com.myweb.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Parser daftar filter + evaluasi pemblokiran. Java murni (tanpa API Android) agar bisa diuji di JVM.
 *
 * Yang didukung:
 *  - Aturan jaringan berbasis domain: ||domain^ dan opsi third-party/3p/important/popup/all
 *  - Aturan pengecualian: @@||domain^  (menang atas aturan blokir, kecuali blokir bertanda $important)
 *  - Format hosts (0.0.0.0 domain), daftar domain polos (hanya untuk daftar bertipe KIND_DOMAIN)
 *  - Filter kosmetik CSS: umum (##sel), per-domain (a.com,~b.a.com##sel), pengecualian (a.com#@#sel)
 *
 * Yang sengaja TIDAK didukung (dilewati, bukan salah-terapkan): aturan dengan path/regex, opsi tipe
 * ($script, $image, $domain=, ~third-party, ...), filter prosedural (:has-text, :xpath, +js, ...).
 *
 * Hanya memakai API Java 8 / Android API 24.
 */
public final class FilterEngine {

    /** Daftar domain / hosts / sintaks adblock domain-saja (AdGuard DNS, yoyo). */
    public static final int KIND_DOMAIN = 0;
    /** Daftar gaya EasyList (baris polos adalah fragmen URL, BUKAN domain). */
    public static final int KIND_ABP = 1;

    public static final int R_SKIP = 0;
    public static final int R_BLOCK = 1;
    public static final int R_ALLOW = 2;
    public static final int R_POPUP = 3;
    public static final int R_COSMETIC = 4;
    public static final int R_UNSUPPORTED = 5;
    public static final int R_INVALID = 6;

    static final int MAX_LINE = 2000;
    static final int MAX_SELECTOR = 250;
    static final int MAX_GENERIC = 3000;          // hanya berlaku bila aturan umum diaktifkan (genericCosmetic)
    static final int MAX_DOMAIN_RULES = 60000;
    static final int MAX_PAGE_RULES = 500;        // batas aturan CSS yang disuntikkan ke SATU halaman
    static final int MAX_CSS_CHARS = 60000;
    static final int MIN_ACCEPTED = 50;

    private static final String[] BAD_SELECTOR = {
        ":-abp-", ":has-text", ":xpath", ":matches", ":style(", ":remove(", ":upward", ":contains(",
        "+js(", ":min-text-length", ":nth-ancestor", ":watch-attr", ":others", "url(", "expression(",
        "/*", "*/"
    };

    /** Baris boilerplate di awal file hosts; bukan target blokir. */
    private static final String[] HOSTS_BOILERPLATE = {
        "localhost.localdomain", "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts"
    };

    private static boolean isHostsBoilerplate(String h) {
        for (int i = 0; i < HOSTS_BOILERPLATE.length; i++) {
            if (HOSTS_BOILERPLATE[i].equals(h)) return true;
        }
        return false;
    }

    private FilterEngine() { }

    // ------------------------------------------------------------------
    // Validasi
    // ------------------------------------------------------------------

    /** Nama host valid (huruf kecil): >=2 label, label 1..63 [a-z0-9-] tidak diawali/diakhiri '-', TLD bukan angka. */
    static boolean isHostname(String s) {
        int n = s.length();
        if (n < 4 || n > 253) return false;
        int labelStart = 0;
        int labels = 0;
        for (int i = 0; i <= n; i++) {
            if (i == n || s.charAt(i) == '.') {
                int len = i - labelStart;
                if (len < 1 || len > 63) return false;
                if (s.charAt(labelStart) == '-' || s.charAt(i - 1) == '-') return false;
                labels++;
                labelStart = i + 1;
            } else {
                char c = s.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
                if (!ok) return false;
            }
        }
        if (labels < 2) return false;
        String tld = s.substring(s.lastIndexOf('.') + 1);
        if (tld.length() < 2) return false;
        for (int i = 0; i < tld.length(); i++) {
            char c = tld.charAt(i);
            if (c < '0' || c > '9') return true;
        }
        return false; // TLD angka semua = alamat IP
    }

    /**
     * Selector aman disisipkan ke dalam "sel{display:none!important}".
     * Menolak: prosedural/tidak standar, pemisah rule ({ } ;), '@', '<', komentar CSS, url()/expression(),
     * serta tanda kurung/kurung siku/kutip yang tidak seimbang (satu baris rusak tidak boleh menelan rule berikutnya).
     */
    static boolean selectorOk(String sel) {
        int n = sel.length();
        if (n == 0 || n > MAX_SELECTOR) return false;
        String low = sel.toLowerCase(Locale.ROOT);
        for (int i = 0; i < BAD_SELECTOR.length; i++) {
            if (low.indexOf(BAD_SELECTOR[i]) >= 0) return false;
        }
        char quote = 0;
        int paren = 0;
        int bracket = 0;
        for (int i = 0; i < n; i++) {
            char c = sel.charAt(i);
            if (c == '\n' || c == '\r' || c == 0) return false;
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            switch (c) {
                case '"':
                case '\'':
                    quote = c;
                    break;
                case '\\':
                    i++;
                    break;
                case '(':
                    paren++;
                    break;
                case ')':
                    paren--;
                    if (paren < 0) return false;
                    break;
                case '[':
                    bracket++;
                    break;
                case ']':
                    bracket--;
                    if (bracket < 0) return false;
                    break;
                case '{':
                case '}':
                case ';':
                case '<':
                case '@':
                    return false;
                default:
                    break;
            }
        }
        return quote == 0 && paren == 0 && bracket == 0;
    }

    private static boolean hasExtendedSeparator(String line) {
        return line.indexOf("#?#") >= 0 || line.indexOf("#$#") >= 0 || line.indexOf("#%#") >= 0
            || line.indexOf("#@?#") >= 0 || line.indexOf("#@$#") >= 0 || line.indexOf("#@%#") >= 0;
    }

    private static boolean looksCosmetic(String line) {
        return line.indexOf("##") >= 0 || line.indexOf("#@#") >= 0 || hasExtendedSeparator(line);
    }

    private static String normHost(String host) {
        if (host == null) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.isEmpty() ? null : h;
    }

    // ------------------------------------------------------------------
    // Aturan kosmetik
    // ------------------------------------------------------------------

    static final class Cosmetic {
        final String selector;
        final String[] excludes;
        Cosmetic(String selector, String[] excludes) {
            this.selector = selector;
            this.excludes = excludes;
        }
    }

    // ------------------------------------------------------------------
    // Hasil (read-only setelah build)
    // ------------------------------------------------------------------

    public static final class Rules {
        private final HashSet<String> block;
        private final HashSet<String> important;
        private final HashSet<String> allow;
        private final HashSet<String> popup;
        private final LinkedHashSet<String> generic;
        private final HashMap<String, ArrayList<Cosmetic>> byDomain;
        private final HashMap<String, HashSet<String>> unhideByDomain;
        private final HashSet<String> genericUnhide;
        private final String summary;

        Rules(Builder b) {
            block = b.block;
            important = b.important;
            allow = b.allow;
            popup = b.popup;
            generic = b.generic;
            byDomain = b.byDomain;
            unhideByDomain = b.unhideByDomain;
            genericUnhide = b.genericUnhide;
            summary = b.summaryText();
        }

        public int blockCount() { return block.size(); }

        public String summary() { return summary; }

        /**
         * Apakah permintaan ke host ini harus diblokir. Aturan pengecualian (@@) menang, kecuali blokirnya $important.
         * Host dicek beserta semua induknya (a.b.com, b.com), tidak pernah TLD saja.
         */
        public boolean isBlocked(String host) {
            String s = normHost(host);
            if (s == null) return false;
            boolean blocked = false;
            boolean imp = false;
            boolean allowed = false;
            while (true) {
                if (block.contains(s)) {
                    blocked = true;
                    if (important.contains(s)) imp = true;
                }
                if (allow.contains(s)) allowed = true;
                int dot = s.indexOf('.');
                if (dot < 0) break;
                String next = s.substring(dot + 1);
                if (next.indexOf('.') < 0) break;
                s = next;
            }
            return blocked && (!allowed || imp);
        }

        /** Aturan $popup: hanya berlaku untuk jendela popup / pengalihan otomatis, bukan untuk sumber daya biasa. */
        public boolean isPopupBlocked(String host) {
            String s = normHost(host);
            if (s == null) return false;
            boolean p = false;
            boolean allowed = false;
            while (true) {
                if (popup.contains(s)) p = true;
                if (allow.contains(s)) allowed = true;
                int dot = s.indexOf('.');
                if (dot < 0) break;
                String next = s.substring(dot + 1);
                if (next.indexOf('.') < 0) break;
                s = next;
            }
            return p && !allowed;
        }

        /** CSS penyembunyi elemen untuk halaman di host ini (umum + khusus domain, dikurangi pengecualian). */
        public String cssFor(String host) {
            String h = normHost(host);
            if (h == null) return "";
            ArrayList<String> chain = new ArrayList<String>();
            String s = h;
            while (true) {
                chain.add(s);
                int dot = s.indexOf('.');
                if (dot < 0) break;
                String next = s.substring(dot + 1);
                if (next.indexOf('.') < 0) break;
                s = next;
            }
            LinkedHashSet<String> out = new LinkedHashSet<String>(generic);
            for (int i = 0; i < chain.size(); i++) {
                ArrayList<Cosmetic> list = byDomain.get(chain.get(i));
                if (list == null) continue;
                for (int k = 0; k < list.size(); k++) {
                    Cosmetic c = list.get(k);
                    if (!excluded(c, h)) out.add(c.selector);
                }
            }
            out.removeAll(genericUnhide);
            for (int i = 0; i < chain.size(); i++) {
                HashSet<String> u = unhideByDomain.get(chain.get(i));
                if (u != null) out.removeAll(u);
            }
            StringBuilder css = new StringBuilder();
            int n = 0;
            for (String sel : out) {
                if (n >= MAX_PAGE_RULES || css.length() + sel.length() + 28 > MAX_CSS_CHARS) break;
                css.append(sel).append("{display:none!important}\n");
                n++;
            }
            return css.toString();
        }

        private static boolean excluded(Cosmetic c, String host) {
            for (int i = 0; i < c.excludes.length; i++) {
                String ex = c.excludes[i];
                if (host.equals(ex) || host.endsWith("." + ex)) return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    public static final class Builder {
        final HashSet<String> block = new HashSet<String>();
        final HashSet<String> important = new HashSet<String>();
        final HashSet<String> allow = new HashSet<String>();
        final HashSet<String> popup = new HashSet<String>();
        final LinkedHashSet<String> generic = new LinkedHashSet<String>();
        final HashMap<String, ArrayList<Cosmetic>> byDomain = new HashMap<String, ArrayList<Cosmetic>>();
        final HashMap<String, HashSet<String>> unhideByDomain = new HashMap<String, HashSet<String>>();
        final HashSet<String> genericUnhide = new HashSet<String>();
        private final boolean cosmetic;
        private boolean genericOn = false;
        private int domainRuleCount;
        private int nSkip, nBlock, nAllow, nPopup, nCosmetic, nUnsupported, nInvalid, nDropped, nCrashed;

        /** defaults boleh null. cosmetic=false membuat semua baris kosmetik dilewati (hemat memori). */
        public Builder(Collection<String> defaults, boolean cosmetic) {
            this.cosmetic = cosmetic;
            if (defaults != null) {
                for (String d : defaults) {
                    String h = normHost(d);
                    if (h != null) block.add(h);
                }
            }
        }

        /**
         * Aturan kosmetik UMUM (##selector tanpa domain) mati secara bawaan: ribuan selector atribut yang dievaluasi
         * ke setiap elemen membuat WebView boros CPU/memori (HP panas, renderer mati), dan juga ikut menyembunyikan
         * elemen umpan yang memicu popup anti-adblock. Aturan khusus domain tidak terpengaruh.
         */
        public Builder genericCosmetic(boolean on) {
            this.genericOn = on;
            return this;
        }

        /** Jumlah aturan jaringan yang diterima (untuk memvalidasi unduhan). */
        public int acceptedNetwork() { return nBlock + nAllow + nPopup; }

        public Rules build() { return new Rules(this); }

        /** Berapa baris yang memicu exception internal (seharusnya 0; dipakai pengujian). */
        int crashedCount() { return nCrashed; }

        String summaryText() {
            return String.format(Locale.ROOT,
                "blok=%d izin=%d popup=%d kosmetik(umum=%d,domain=%d,kecuali=%d) dilewati=%d tidak-didukung=%d rusak=%d terpotong=%d",
                block.size(), allow.size(), popup.size(), generic.size(), domainRuleCount,
                countUnhide(), nSkip, nUnsupported, nInvalid, nDropped);
        }

        private int countUnhide() {
            int n = genericUnhide.size();
            for (HashSet<String> s : unhideByDomain.values()) n += s.size();
            return n;
        }

        /** Memproses satu baris. Tidak pernah melempar exception: baris rusak hanya dihitung dan dilewati. */
        public int addLine(String raw, int kind) {
            int r;
            try {
                r = parseLine(raw, kind);
            } catch (RuntimeException e) {
                nCrashed++;
                r = R_INVALID;
            }
            switch (r) {
                case R_SKIP: nSkip++; break;
                case R_BLOCK: nBlock++; break;
                case R_ALLOW: nAllow++; break;
                case R_POPUP: nPopup++; break;
                case R_COSMETIC: nCosmetic++; break;
                case R_UNSUPPORTED: nUnsupported++; break;
                default: nInvalid++; break;
            }
            return r;
        }

        private int parseLine(String raw, int kind) {
            if (raw == null) return R_SKIP;
            String line = raw.trim();
            if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1).trim();
            if (line.isEmpty()) return R_SKIP;
            char c0 = line.charAt(0);
            if (c0 == '!' || c0 == '[') return R_SKIP;                      // komentar / header [Adblock Plus ...]
            if (kind == KIND_DOMAIN && c0 == '#') return R_SKIP;            // komentar gaya hosts
            if (line.length() > MAX_LINE) return R_INVALID;
            if (line.startsWith("@@||")) return addNetwork(line.substring(4), true);
            if (line.startsWith("@@")) return R_UNSUPPORTED;
            if (line.startsWith("||")) return addNetwork(line.substring(2), false);
            if (c0 == '|' || c0 == '/') return R_UNSUPPORTED;                // anchor URL / regex
            if (kind == KIND_ABP && looksCosmetic(line)) {
                return cosmetic ? addCosmetic(line) : R_UNSUPPORTED;
            }
            if (kind == KIND_DOMAIN) return addHostsLine(line);
            return R_UNSUPPORTED;                                           // fragmen URL polos pada daftar ABP
        }

        private int addNetwork(String body, boolean exception) {
            int end = -1;
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '^' || c == '$' || c == '/' || c == '*' || c == ':' || c == '|') { end = i; break; }
            }
            String dom = (end < 0 ? body : body.substring(0, end)).toLowerCase(Locale.ROOT);
            String opts;
            boolean hadOpts = false;
            if (end < 0) {
                opts = "";
            } else {
                char c = body.charAt(end);
                if (c == '^') {
                    String t = body.substring(end + 1);
                    if (t.isEmpty()) opts = "";
                    else if (t.charAt(0) == '$') { opts = t.substring(1); hadOpts = true; }
                    else opts = null;
                } else if (c == '$') {
                    opts = body.substring(end + 1);
                    hadOpts = true;
                } else {
                    opts = null;                                            // path, port, wildcard, anchor akhir
                }
            }
            if (opts == null) return R_UNSUPPORTED;
            if (hadOpts && opts.isEmpty()) return R_UNSUPPORTED;            // "$" tanpa opsi = baris rusak
            if (!isHostname(dom)) return dom.isEmpty() ? R_UNSUPPORTED : R_INVALID;

            boolean imp = false;
            boolean pop = false;
            boolean all = false;
            if (!opts.isEmpty()) {
                String[] parts = opts.split(",", -1);
                for (int i = 0; i < parts.length; i++) {
                    String p = parts[i].trim().toLowerCase(Locale.ROOT);
                    if (p.equals("third-party") || p.equals("3p")) continue;
                    if (p.equals("important")) { imp = true; continue; }
                    if (p.equals("popup")) { pop = true; continue; }
                    if (p.equals("all")) { all = true; continue; }
                    return R_UNSUPPORTED;                                   // $script, $domain=, ~third-party, dst.
                }
            }
            if (exception) {
                if (pop && !all) return R_UNSUPPORTED;
                allow.add(dom);
                return R_ALLOW;
            }
            if (pop && !all) {
                popup.add(dom);
                return R_POPUP;
            }
            block.add(dom);
            if (imp) important.add(dom);
            return R_BLOCK;
        }

        private int addHostsLine(String line) {
            String s = line;
            int hash = s.indexOf('#');
            if (hash >= 0) s = s.substring(0, hash).trim();                 // komentar di ujung baris
            if (s.isEmpty()) return R_SKIP;
            String[] t = s.split("\\s+");
            int start;
            if (t.length >= 2 && (t[0].equals("0.0.0.0") || t[0].equals("127.0.0.1"))) start = 1;
            else if (t.length == 1) start = 0;
            else return R_UNSUPPORTED;
            int added = 0;
            int boiler = 0;
            for (int i = start; i < t.length; i++) {
                String h = t[i].toLowerCase(Locale.ROOT);
                while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
                if (isHostsBoilerplate(h)) { boiler++; continue; }
                if (isHostname(h)) {
                    block.add(h);
                    added++;
                }
            }
            if (added > 0) return R_BLOCK;
            return boiler > 0 ? R_SKIP : R_INVALID;
        }

        private int addCosmetic(String line) {
            if (hasExtendedSeparator(line)) return R_UNSUPPORTED;
            int hide = line.indexOf("##");
            int show = line.indexOf("#@#");
            boolean unhide;
            int idx;
            int sep;
            if (show >= 0 && (hide < 0 || show < hide)) { unhide = true; idx = show; sep = 3; }
            else if (hide >= 0) { unhide = false; idx = hide; sep = 2; }
            else return R_UNSUPPORTED;

            String domPart = line.substring(0, idx).trim();
            String sel = line.substring(idx + sep).trim();
            if (!selectorOk(sel)) return R_UNSUPPORTED;

            ArrayList<String> inc = new ArrayList<String>();
            ArrayList<String> exc = new ArrayList<String>();
            if (!domPart.isEmpty()) {
                String[] ds = domPart.split(",", -1);
                for (int i = 0; i < ds.length; i++) {
                    String d = ds[i].trim().toLowerCase(Locale.ROOT);
                    boolean neg = d.startsWith("~");
                    if (neg) d = d.substring(1);
                    if (!isHostname(d)) return R_UNSUPPORTED;               // termasuk "example.*"
                    if (neg) exc.add(d); else inc.add(d);
                }
                if (inc.isEmpty()) return R_UNSUPPORTED;                    // hanya "~domain": tidak didukung
            }

            if (unhide) {
                if (domPart.isEmpty()) {
                    genericUnhide.add(sel);
                } else {
                    for (int i = 0; i < inc.size(); i++) {
                        HashSet<String> set = unhideByDomain.get(inc.get(i));
                        if (set == null) { set = new HashSet<String>(); unhideByDomain.put(inc.get(i), set); }
                        set.add(sel);
                    }
                }
                return R_COSMETIC;
            }
            if (domPart.isEmpty()) {
                if (!genericOn) return R_UNSUPPORTED;
                if (generic.size() >= MAX_GENERIC) { nDropped++; return R_UNSUPPORTED; }
                generic.add(sel);
                return R_COSMETIC;
            }
            if (domainRuleCount >= MAX_DOMAIN_RULES) { nDropped++; return R_UNSUPPORTED; }
            String[] ex = exc.toArray(new String[0]);
            for (int i = 0; i < inc.size(); i++) {
                ArrayList<Cosmetic> list = byDomain.get(inc.get(i));
                if (list == null) { list = new ArrayList<Cosmetic>(); byDomain.put(inc.get(i), list); }
                list.add(new Cosmetic(sel, ex));
            }
            domainRuleCount++;
            return R_COSMETIC;
        }
    }

    // ------------------------------------------------------------------
    // File
    // ------------------------------------------------------------------

    /** Membaca file daftar baris demi baris (streaming). IOException di tengah jalan: baris yang sudah terbaca tetap dipakai. */
    public static void addFile(Builder b, File f, int kind) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = r.readLine()) != null) b.addLine(line, kind);
        } finally {
            r.close();
        }
    }

    /**
     * Apakah file ini tampak seperti daftar filter. Unduhan yang bukan daftar (halaman login Wi-Fi, error HTML, kosong)
     * tidak boleh menimpa daftar yang sudah bagus. Lulus bila ada cukup aturan jaringan yang dikenali, ATAU berawal
     * dengan header khas daftar adblock ("[Adblock ..." / komentar "!") tanpa tanda HTML dan cukup panjang
     * (daftar yang isinya kebanyakan kosmetik/path, mis. ABPindo, hanya sedikit aturan domain murni).
     */
    public static boolean looksLikeList(File f, int kind) {
        if (countAccepted(f, kind) >= MIN_ACCEPTED) return true;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            boolean header = false;
            boolean html = false;
            int lines = 0;
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.length() > 0 && t.charAt(0) == '\uFEFF') t = t.substring(1).trim();
                if (t.isEmpty()) continue;
                lines++;
                if (lines == 1) {
                    String low = t.toLowerCase(Locale.ROOT);
                    header = (t.charAt(0) == '[' && low.startsWith("[adblock")) || t.charAt(0) == '!';
                }
                if (lines <= 20) {
                    String low = t.toLowerCase(Locale.ROOT);
                    if (low.contains("<html") || low.contains("<!doctype") || low.contains("<body")) html = true;
                }
            }
            return header && !html && lines >= MIN_ACCEPTED;
        } catch (IOException e) {
            return false;
        } finally {
            if (r != null) {
                try { r.close(); } catch (IOException e) { /* abaikan */ }
            }
        }
    }

    /** Jumlah aturan jaringan valid di file. */
    public static int countAccepted(File f, int kind) {
        Builder b = new Builder(null, false);
        try {
            addFile(b, f, kind);
        } catch (IOException e) {
            // hitung apa yang sempat terbaca
        }
        return b.acceptedNetwork();
    }
}
