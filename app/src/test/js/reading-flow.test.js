// Uji perilaku skrip PRELOAD_JS / RESTORE_JS / SAVE_POS_JS (Preload.kt) di Chromium dengan server lokal.
// Jalankan dari akar proyek:  node app/src/test/js/reading-flow.test.js   (butuh paket "playwright" + Chromium)
// Keluar dengan kode 1 bila ada pemeriksaan yang gagal.
const http = require('http');
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const kt = fs.readFileSync(path.resolve(__dirname, '../../main/java/com/myweb/app/Preload.kt'), 'utf8');
function konst(name) {
  const m = new RegExp('const val ' + name + ' = """([\\s\\S]*?)"""').exec(kt);
  if (!m) throw new Error('konstanta tidak ditemukan: ' + name);
  return m[1];
}
const PRELOAD = konst('PRELOAD_JS');
const RESTORE = konst('RESTORE_JS');
const SAVE = konst('SAVE_POS_JS');
const restoreJs = (y, budget) => RESTORE.replace('__Y__', String(y)).replace('__BUDGET__', String(budget));

// ---------- PNG kecil ----------
const T = new Uint32Array(256);
for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; T[n] = c >>> 0; }
function crc32(b) { let c = 0xffffffff; for (const x of b) c = T[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function chunk(t, d) {
  const l = Buffer.alloc(4); l.writeUInt32BE(d.length);
  const td = Buffer.concat([Buffer.from(t), d]);
  const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td));
  return Buffer.concat([l, td, c]);
}
function png(w, h) {
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  const row = Buffer.alloc(1 + w * 3, 0x80); row[0] = 0;
  const raw = Buffer.concat(Array(h).fill(row));
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
const PNG = png(8, 8);

// ---------- server ----------
let pages = {};            // path -> html
let cfg;                   // konfigurasi gambar per tes
let hits, inflight, maxInflight;
function reset(c) { cfg = Object.assign({ delay: 120, delays: {}, failFirst: new Set(), alwaysFail: new Set() }, c || {}); hits = {}; inflight = 0; maxInflight = 0; }
reset();
const server = http.createServer((req, res) => {
  const u = req.url.split('?')[0];
  if (pages[u]) { res.writeHead(200, { 'content-type': 'text/html' }); res.end(pages[u]); return; }
  if (u.startsWith('/img/')) {
    hits[u] = (hits[u] || 0) + 1;
    inflight++; maxInflight = Math.max(maxInflight, inflight);
    const done = () => { inflight--; };
    res.on('close', done);
    const n = hits[u];
    const delay = cfg.delays[u] !== undefined ? cfg.delays[u] : cfg.delay;
    setTimeout(() => {
      if (cfg.alwaysFail.has(u) || (cfg.failFirst.has(u) && n === 1)) { res.writeHead(500); res.end('x'); return; }
      res.writeHead(200, { 'content-type': 'image/png', 'cache-control': 'no-store' }); res.end(PNG);
    }, delay);
    return;
  }
  res.writeHead(404); res.end();
});

// ---------- pemeriksaan ----------
let pass = 0, fail = 0;
function check(name, ok, extra) {
  if (ok) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  GAGAL ' + name + (extra !== undefined ? '  -> ' + JSON.stringify(extra) : '')); }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(fn, timeout, step) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) { if (await fn()) return true; await sleep(step || 100); }
  return false;
}
const idx = (u) => parseInt(u.replace(/\D+/g, ''), 10);

function imgPage(n, extraHead) {
  let h = '<!doctype html><meta name=viewport content="width=400"><style>body{margin:0}.c{height:700px}img{display:block;width:100px}</style>' + (extraHead || '');
  for (let i = 0; i < n; i++) h += '<div class=c><img id=i' + i + ' data-src="/img/i' + i + '.png"></div>';
  return h;
}

async function main() {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + server.address().port;
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 400, height: 800 } });

  // =============== A. jendela, antrean, duplikasi, gagal ===============
  console.log('A. Preload: jendela AHEAD, antrean, duplikasi, gambar gagal');
  pages['/a'] = imgPage(30);
  reset({ delay: 150, failFirst: new Set(['/img/i1.png']), alwaysFail: new Set(['/img/i2.png']) });
  let p = await ctx.newPage();
  await p.goto(base + '/a');
  await p.evaluate(() => { window.__rects = 0; const o = Element.prototype.getBoundingClientRect; Element.prototype.getBoundingClientRect = function () { window.__rects++; return o.call(this); }; });
  await p.evaluate(PRELOAD);
  await sleep(8500);
  let asked = Object.keys(hits).map(idx).sort((a, b) => a - b);
  check('hanya 4 gambar pertama diminta (AHEAD=4) di posisi atas', asked.every((i) => i <= 3) && asked.length === 4, asked);
  check('maksimal 2 unduhan bersamaan', maxInflight <= 2, maxInflight);
  check('gambar sukses tidak diminta dua kali', hits['/img/i0.png'] === 1 && hits['/img/i3.png'] === 1, hits);
  check('gagal sekali lalu diulang dan berhasil (2 permintaan)', hits['/img/i1.png'] === 2, hits['/img/i1.png']);
  check('gagal terus: dicoba 1 + 2 ulang lalu berhenti (3 permintaan)', hits['/img/i2.png'] === 3, hits['/img/i2.png']);
  check('gambar yang diulang akhirnya termuat', await p.evaluate(() => document.getElementById('i1').naturalWidth > 0));
  check('gambar gagal permanen tetap tidak termuat', await p.evaluate(() => document.getElementById('i2').naturalWidth === 0));
  const before = JSON.stringify(hits);
  await sleep(2500);
  check('tidak ada permintaan baru setelah selesai (tanpa perulangan)', JSON.stringify(hits) === before);

  // biaya saat tenang: mutasi non-gambar tidak memicu pembacaan layout
  const r0 = await p.evaluate(() => window.__rects);
  await p.evaluate(() => { for (let i = 0; i < 300; i++) document.body.appendChild(document.createElement('span')); });
  await sleep(700);
  check('300 mutasi non-gambar: 0 pembacaan layout', (await p.evaluate(() => window.__rects)) - r0 === 0);

  // scroll jauh: jendela pindah, tidak memuat ke belakang/jauh ke depan
  const keys0 = new Set(Object.keys(hits));
  await p.evaluate(() => window.scrollTo(0, 7000));
  await sleep(2500);
  const fresh = Object.keys(hits).filter((k) => !keys0.has(k)).map(idx).sort((a, b) => a - b);
  check('setelah scroll ke ~gambar 10: yang baru diminta ada dan hanya di sekitar posisi', fresh.length >= 3 && fresh.every((i) => i >= 9 && i <= 13), fresh);

  // dimatikan lewat menu
  await p.evaluate(() => { window.__preOff = 1; });
  const k1 = Object.keys(hits).length;
  await p.evaluate(() => window.scrollTo(0, 14000));
  await sleep(1200);
  check('mode mati: tidak ada permintaan', Object.keys(hits).length === k1);
  await p.evaluate(() => { window.__preOff = 0; });
  await p.evaluate(PRELOAD);   // seperti injectPreload() saat menu dinyalakan lagi
  await sleep(1500);
  check('dinyalakan lagi: langsung berjalan tanpa menunggu scroll', Object.keys(hits).length > k1);
  await p.close();

  // =============== B. tidak merusak lazy-load situs ===============
  console.log('B. Preload: lazy-load situs, placeholder, <picture>, gambar dinamis');
  pages['/b'] =
    '<!doctype html><style>body{margin:0}img{display:block;width:100px}.c{height:150px}</style>' +
    '<div class=c><img id=site src="/img/site.png" data-src="/img/other.png"></div>' +
    '<div class=c><img id=ph src="data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==" data-src="/img/ph.png"></div>' +
    '<div class=c><picture><source id=src1 data-srcset="/img/pic.png"><img id=pic data-src="/img/picfb.png"></picture></div>' +
    '<div class=c><img id=late></div>';
  reset({ delays: { '/img/site.png': 900 } });
  p = await ctx.newPage();
  await p.goto(base + '/b');
  await p.evaluate(PRELOAD);
  await sleep(300);
  await p.evaluate(() => document.getElementById('late').setAttribute('data-src', '/img/late.png'));   // atribut diisi skrip situs belakangan
  await sleep(2500);
  check('src asli situs yang sedang dimuat tidak diganti (data-src lain tidak diminta)', !hits['/img/other.png'] && hits['/img/site.png'] === 1, hits);
  check('placeholder data: diganti data-src, diminta sekali', hits['/img/ph.png'] === 1 && (await p.evaluate(() => document.getElementById('ph').src)).endsWith('/img/ph.png'));
  check('<picture>: data-srcset pada <source> disalin', (await p.evaluate(() => document.getElementById('src1').getAttribute('srcset'))) === '/img/pic.png');
  check('<picture>: tepat satu gambar terunduh untuk elemen itu', (hits['/img/pic.png'] || 0) + (hits['/img/picfb.png'] || 0) === 1, hits);
  check('data-src yang diisi belakangan ikut diproses (timer awal)', hits['/img/late.png'] === 1, hits['/img/late.png']);
  await p.evaluate(() => { const d = document.createElement('div'); d.innerHTML = '<img id=dyn data-src="/img/dyn.png">'; document.body.insertBefore(d, document.body.firstChild); });
  await sleep(1200);
  check('gambar yang ditambahkan dinamis ikut dimuat (sekali)', hits['/img/dyn.png'] === 1, hits['/img/dyn.png']);
  await p.close();

  // batas preconnect
  console.log('B2. Preconnect dibatasi');
  let h = '<!doctype html><style>body{margin:0}.c{height:700px}img{display:block;width:100px}</style>';
  for (let i = 0; i < 14; i++) h += '<div class=c><img data-src="http://h' + i + '.invalid/x.png"></div>';
  pages['/pc'] = h;
  reset();
  p = await ctx.newPage();
  await p.goto(base + '/pc');
  await p.evaluate(PRELOAD);
  for (let s = 0; s < 14; s++) { await p.evaluate((y) => window.scrollTo(0, y), s * 700); await sleep(450); }
  const links = await p.evaluate(() => document.querySelectorAll('link[rel=preconnect]').length);
  check('preconnect <= 4 origin walau halaman menyebut 14 origin', links <= 4 && links >= 1, links);
  await p.close();

  // =============== C. pemulihan posisi pada halaman lazy-load ===============
  console.log('C. Pemulihan posisi: halaman komik lazy (tinggi nol sampai gambar masuk layar)');
  const lazy = (n, extra) => '<!doctype html><meta name=viewport content="width=400"><style>body{margin:0}img{display:block;width:100%;min-height:1px}</style><body>' +
    Array.from({ length: n }, (_, i) => '<img data-src="/img/c' + i + '.png">').join('') + '<script>' +
    'var io=new IntersectionObserver(function(es){es.forEach(function(e){if(e.isIntersecting){var m=e.target;io.unobserve(m);m.onload=function(){m.style.height="500px"};m.src=m.getAttribute("data-src");}})});' +
    'document.querySelectorAll("img").forEach(function(m){io.observe(m)});' + (extra || '') + '</script>';
  pages['/lazy'] = lazy(60);
  const scrollY = (pg) => pg.evaluate(() => Math.round(window.scrollY));

  // C0: bukti masalah pada logika lama (4 percobaan tetap 150/700/1500/2800 ms)
  reset({ delay: 100 });
  p = await ctx.newPage();
  await p.goto(base + '/lazy', { waitUntil: 'load' });
  await p.evaluate(() => { [150, 700, 1500, 2800].forEach((d) => setTimeout(() => { if (window.scrollY < 9000 - 5) window.scrollTo(0, 9000); }, d)); });
  await sleep(4500);
  const oldY = await scrollY(p);
  check('[logika lama] gagal mencapai posisi 9000 pada halaman lazy (y=' + oldY + ')', Math.abs(oldY - 9000) > 50);
  await p.close();

  // C1: logika baru
  reset({ delay: 100 });
  p = await ctx.newPage();
  await p.goto(base + '/lazy', { waitUntil: 'load' });
  await p.evaluate(PRELOAD);
  await p.evaluate(restoreJs(9000, 12000));
  await sleep(250);
  check('SAVE_POS_JS = -1 selama pemulihan berjalan', (await p.evaluate(SAVE)) === -1);
  const reached = await waitFor(async () => Math.abs((await scrollY(p)) - 9000) <= 4, 10000, 150);
  check('posisi 9000 tercapai di halaman lazy', reached, await scrollY(p));
  const ended = await waitFor(() => p.evaluate(() => !window.__rsActive), 6000, 150);
  check('pemulihan selesai sendiri (flag __rsActive mati)', ended);
  await sleep(800);
  check('posisi tetap setelah selesai', Math.abs((await scrollY(p)) - 9000) <= 4, await scrollY(p));
  const above = await p.evaluate(() => { const r = []; document.querySelectorAll('img').forEach((m, i) => { if (m.getBoundingClientRect().bottom + window.scrollY <= 9000 + 1 && m.naturalWidth === 0) r.push(i); }); return r; });
  check('semua gambar lazy di ATAS posisi sudah termuat (tata letak sama dengan saat disimpan)', above.length === 0, above);
  const saved = await p.evaluate(SAVE);
  check('SAVE_POS_JS mengembalikan posisi nyata setelah selesai', Math.abs(saved - 9000) <= 4, saved);
  await p.close();

  // C2: sentuhan pengguna membatalkan pemulihan
  reset({ delay: 100 });
  p = await ctx.newPage();
  await p.goto(base + '/lazy', { waitUntil: 'load' });
  await p.evaluate(restoreJs(9000, 12000));
  await sleep(450);
  await p.evaluate(() => document.dispatchEvent(new Event('touchstart', { bubbles: true })));
  const yAtTouch = await scrollY(p);
  await sleep(2500);
  check('setelah disentuh: pemulihan berhenti (flag mati)', await p.evaluate(() => !window.__rsActive));
  check('setelah disentuh: halaman tidak ditarik lagi (y ' + yAtTouch + ' -> ' + (await scrollY(p)) + ')', Math.abs((await scrollY(p)) - yAtTouch) <= 2);
  await p.close();

  // C3: posisi mustahil (halaman lebih pendek) -> batas waktu, kembali ke posisi awal, tidak berulang
  pages['/short'] = lazy(8);
  reset({ delay: 60 });
  p = await ctx.newPage();
  await p.goto(base + '/short', { waitUntil: 'load' });
  await p.evaluate(restoreJs(300000, 2000));
  await sleep(3200);
  check('batas waktu: pemulihan berhenti', await p.evaluate(() => !window.__rsActive));
  check('posisi mustahil (jauh > dokumen): kembali ke posisi awal 0', (await scrollY(p)) === 0, await scrollY(p));
  const hb = Object.keys(hits).length;
  await sleep(1500);
  check('tidak ada pekerjaan lanjutan setelah berhenti', Object.keys(hits).length === hb && (await scrollY(p)) === 0);
  await p.close();

  // C3b: halaman sedikit lebih pendek dari posisi (>= 60%) -> ke dasar terdekat
  pages['/mid'] = lazy(14);   // ~7000px
  reset({ delay: 60 });
  p = await ctx.newPage();
  await p.goto(base + '/mid', { waitUntil: 'load' });
  await p.evaluate(restoreJs(9000, 3000));
  await sleep(4200);
  const midY = await scrollY(p);
  const midMax = await p.evaluate(() => document.documentElement.scrollHeight - innerHeight);
  check('halaman lebih pendek tapi >= 60%: berhenti di dasar (' + midY + ' dari maks ' + midMax + ')', midY > 5400 && Math.abs(midY - midMax) <= 4);
  await p.close();

  // C4: pindah halaman saat pemulihan berjalan -> tidak menimpa halaman berikutnya
  pages['/other'] = '<!doctype html><body style="margin:0"><div style="height:5000px">lain</div>';
  reset({ delay: 100 });
  p = await ctx.newPage();
  await p.goto(base + '/lazy', { waitUntil: 'load' });
  await p.evaluate(restoreJs(9000, 12000));
  await sleep(300);
  await p.goto(base + '/other');
  await sleep(2500);
  check('pindah halaman: halaman baru tetap di atas (posisi lama tidak dipakai)', (await scrollY(p)) === 0 && (await p.evaluate(() => !window.__rsActive)));
  await p.close();

  // C5: halaman belum selesai dimuat -> posisi tidak disimpan
  pages['/slow'] = '<!doctype html><body style="margin:0"><div style="height:4000px">x</div><img src="/img/slow.png">';
  reset({ delays: { '/img/slow.png': 2500 } });
  p = await ctx.newPage();
  await p.goto(base + '/slow', { waitUntil: 'domcontentloaded' });
  const during = await p.evaluate(SAVE);
  check('SAVE_POS_JS = -1 saat halaman belum complete', during === -1, during);
  await p.waitForLoadState('load');
  await p.evaluate(() => window.scrollTo(0, 1234));
  check('SAVE_POS_JS membaca posisi setelah complete', (await p.evaluate(SAVE)) === 1234);
  await p.close();

  // C6: gambar di atas selesai dimuat belakangan (tinggi dokumen berubah saat pemulihan)
  pages['/shift'] = '<!doctype html><meta name=viewport content="width=400"><style>body{margin:0}img{display:block;width:100%;height:100px}</style><body>' +
    Array.from({ length: 30 }, (_, i) => '<img data-src="/img/s' + i + '.png">').join('') +
    '<script>document.querySelectorAll("img").forEach(function(m){m.onload=function(){m.style.height="500px"};m.src=m.getAttribute("data-src");});</script>';
  const dl = {}; for (let i = 0; i < 30; i++) dl['/img/s' + i + '.png'] = 50 + ((i * 97) % 700);
  reset({ delays: dl });
  p = await ctx.newPage();
  await p.goto(base + '/shift', { waitUntil: 'domcontentloaded' });
  await p.evaluate(restoreJs(3000, 12000));
  await sleep(3500);
  const allLoaded = await p.evaluate(() => [].every.call(document.images, (m) => m.naturalWidth > 0));
  const sy = await scrollY(p);
  check('tinggi dokumen berubah saat pemulihan: semua gambar termuat & posisi akhir 3000 (' + sy + ')', allLoaded && Math.abs(sy - 3000) <= 4);
  await p.close();

  await browser.close();
  server.close();
  console.log('\nLulus: ' + pass + '  Gagal: ' + fail);
  process.exit(fail ? 1 : 0);
}
main().catch((e) => { console.error(e); process.exit(2); });
