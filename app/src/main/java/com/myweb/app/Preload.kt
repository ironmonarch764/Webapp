package com.myweb.app

// Skrip yang disuntikkan ke halaman: memuat gambar di depan posisi baca lebih awal dan membuka koneksi lebih dulu
// (preconnect) ke server gambar.
//
// Prinsip:
//  - Jendela = AHEAD gambar (yang tidak tersembunyi) mulai dari gambar pertama yang masih dekat layar. Jendela dihitung dari
//    posisi baca, jadi jumlah yang dimuat TIDAK bertambah terus walau gambar di depan sudah selesai.
//  - AHEAD otomatis lebih kecil pada koneksi 2G/3G, mode hemat data, atau RAM perangkat <= 2 GB.
//  - Paling banyak MAXI gambar diunduh sekaligus oleh preload, berurutan dari yang terdekat; sisanya menunggu event load/error.
//  - Memproses elemen BUKAN berarti berhasil dimuat: tiap gambar punya status (sedang diunduh / gagal ke-n). Gagal diulang
//    paling banyak MAXF kali dengan jeda, lalu dibiarkan.
//  - Mekanisme lazy-load situs tidak ditimpa: src hanya diisi bila kosong / data: / placeholder 1px; src asli yang sedang
//    dimuat tidak diganti, dan src yang sama tidak diset ulang (tidak ada permintaan ganda).
const val PRELOAD_JS = """
(function(){
if(window.__pre){if(window.__preRun)window.__preRun();return;}
window.__pre=1;
var A=['data-src','data-lazy-src','data-original','data-lazy','data-echo'],
 AHEAD=4,MAXI=2,MAXF=2,MAXO=4,RETRY=[1500,4000],
 st=new WeakMap(),origins={},nO=0,timer=0,first=0;
try{
 var cn=navigator.connection,et=(cn&&cn.effectiveType)||'';
 if(/2g/.test(et)||(cn&&cn.saveData)||(navigator.deviceMemory&&navigator.deviceMemory<=2))AHEAD=2;
 else if(et==='3g')AHEAD=3;
}catch(e){}
function pc(u){try{var o=new URL(u,location.href).origin;if(o===location.origin||origins[o]||nO>=MAXO)return;origins[o]=1;nO++;var l=document.createElement('link');l.rel='preconnect';l.href=o;(document.head||document.documentElement).appendChild(l);}catch(e){}}
function lazySrc(m){for(var i=0;i<A.length;i++){var v=m.getAttribute(A[i]);if(v&&v.indexOf(' ')<0&&v.charAt(0)!=='{')return v;}return '';}
function req(m){
 try{
  m.loading='eager';
  var p=m.parentNode;
  if(p&&p.nodeName==='PICTURE'){var so=p.getElementsByTagName('source');for(var k=0;k<so.length;k++){var d=so[k].getAttribute('data-srcset');if(d&&!so[k].getAttribute('srcset'))so[k].setAttribute('srcset',d);}}
  var ss=m.getAttribute('data-srcset');
  if(ss&&!m.getAttribute('srcset'))m.setAttribute('srcset',ss);
  var v=lazySrc(m),c=m.getAttribute('src');
  if(v){
   if(c!==v&&(!c||c.indexOf('data:')===0||(m.complete&&m.naturalWidth<2)))m.setAttribute('src',v);
   pc(v);
  }
  var u=m.currentSrc||m.getAttribute('src');if(u&&u.indexOf('data:')!==0)pc(u);
 }catch(e){}
}
function retry(m){
 try{
  var c=m.getAttribute('src');
  if(c&&c.indexOf('data:')!==0){m.removeAttribute('src');m.setAttribute('src',c);}
  else{var s=m.getAttribute('srcset');if(s){m.removeAttribute('srcset');m.setAttribute('srcset',s);}}
 }catch(e){}
}
// gambar "layak" = tidak tersembunyi (display:none, mis. oleh filter kosmetik) dan belum jauh di atas layar
function ok(img,vh){
 var r=img.getBoundingClientRect();
 if(r.width===0&&r.height===0&&img.offsetParent===null)return false;
 return r.bottom>-vh;
}
// 0 = tidak perlu, 1 = perlu permintaan, 2 = sedang diunduh (dihitung sebagai antrean)
function need(m,s,now){
 // masih placeholder: ada sumber asli (data-src) yang belum dipasang, sedangkan src kosong/data:/gambar 1px
 var v=lazySrc(m),c=m.getAttribute('src');
 var ph=!!v&&c!==v&&(!c||c.indexOf('data:')===0||(m.complete&&m.naturalWidth<2));
 if(m.complete&&m.naturalWidth>0&&!ph)return 0;
 if(s){
  if(s.p&&!m.complete&&now-s.t<15000)return 2;
  if(s.n>MAXF)return 0;
  if(s.n>0&&now-s.t<RETRY[s.n-1])return 0;
  return 1;
 }
 if(m.complete&&m.getAttribute('src')&&!lazySrc(m))return 0;
 return 1;
}
function run(){
 timer=0;if(window.__preOff||document.hidden)return;
 var im=document.images,n=im.length,vh=window.innerHeight||800;
 if(!n)return;
 // mulai dari posisi terakhir (bukan dari gambar pertama tiap kali): jumlah pembacaan layout ~ jarak scroll, bukan jumlah gambar
 var i=first<n?first:n-1;
 while(i>0&&ok(im[i-1],vh))i--;
 while(i<n&&!ok(im[i],vh))i++;
 first=i;if(i>=n)return;
 var win=[];
 for(var j=i;j<n&&win.length<AHEAD;j++){if(j===i||ok(im[j],vh))win.push(im[j]);}
 var pend=0,todo=[],now=Date.now();
 for(var k=0;k<win.length;k++){var r=need(win[k],st.get(win[k]),now);if(r===2)pend++;else if(r===1)todo.push(win[k]);}
 for(var q=0;q<todo.length&&pend<MAXI;q++){
  var m=todo[q],s=st.get(m);
  if(!s){s={n:0,t:0,p:false};st.set(m,s);}
  if(s.n>0)retry(m);else req(m);
  s.t=now;s.p=!m.complete;
  if(s.p)pend++;
 }
}
function sched(){if(!timer)timer=setTimeout(run,300);}
window.__preRun=sched;
// selesai/gagal: lepaskan antrean dan lanjut ke gambar berikutnya; gagal dicoba lagi setelah jeda (terbatas)
function ev(e){
 var t=e.target;if(!t||t.nodeName!=='IMG')return;
 var s=st.get(t);if(!s)return;
 s.p=false;
 if(e.type==='error'){
  s.n++;s.t=Date.now();
  if(s.n<=MAXF)setTimeout(sched,RETRY[s.n-1]+50);
 }else sched();
}
document.addEventListener('load',ev,true);
document.addEventListener('error',ev,true);
addEventListener('scroll',sched,{passive:true});
addEventListener('resize',sched);
document.addEventListener('visibilitychange',sched);
// hanya bereaksi bila ada <img> baru; iklan/penghitung yang terus mengubah DOM tidak memicu hitung ulang layout
try{new MutationObserver(function(ms){
 for(var a=0;a<ms.length;a++){
  var ad=ms[a].addedNodes;
  for(var b=0;b<ad.length;b++){
   var nd=ad[b];
   if(nd.nodeType===1&&(nd.nodeName==='IMG'||(nd.firstElementChild&&nd.querySelector('img')))){sched();return;}
  }
 }
}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
run();setTimeout(run,600);setTimeout(run,1800);
})();
"""

// Memulihkan posisi baca di halaman yang baru selesai dimuat. __Y__ = posisi (piksel CSS), __BUDGET__ = batas waktu (ms).
//  - Menunggu sampai dokumen cukup tinggi. Bila belum (gambar lazy belum memberi tinggi), bergeser ke dasar yang sudah
//    diketahui supaya gambar berikutnya mulai dimuat, lalu mencoba lagi.
//  - Setelah mencapai posisi, diperiksa beberapa kali (gambar di atas yang baru selesai dimuat bisa menggeser isi).
//  - Berhenti SEGERA bila pengguna menyentuh/scroll/menekan tombol, atau bila batas waktu habis. Tidak ada perulangan tanpa batas.
//  - Saat batas habis dan posisi tidak tercapai: bergeser ke dasar terdekat bila dokumen sudah >= 60% dari posisi
//    tersimpan; kalau jauh lebih pendek, kembali ke posisi awal sebelum pemulihan (posisi lama tidak masuk akal untuk halaman ini).
//  - window.__rsActive = true selama pemulihan berjalan (dipakai SAVE_POS_JS agar posisi sementara tidak tersimpan).
const val RESTORE_JS = """
(function(y,budget){
if(window.__rsStop)window.__rsStop();
var t0=Date.now(),good=0,tm=0,ev=['touchstart','pointerdown','mousedown','wheel','keydown'],s0=window.scrollY||document.documentElement.scrollTop||0;
function H(){var d=document.documentElement,b=document.body;return Math.max(d.scrollHeight,b?b.scrollHeight:0);}
function cur(){return window.scrollY||document.documentElement.scrollTop||0;}
function go(v){try{scrollTo({top:v,left:0,behavior:'instant'});}catch(e){scrollTo(0,v);}}
function stop(){
 window.__rsActive=false;window.__rsStop=null;clearInterval(tm);
 for(var i=0;i<ev.length;i++)removeEventListener(ev[i],stop,true);
 removeEventListener('popstate',stop);
}
function step(){
 var max=H()-(innerHeight||800);
 if(max>=y-2){
  if(Math.abs(cur()-y)>4){go(y);good=0;}else good++;
  if(good>=5)stop();
  return;
 }
 good=0;
 if(Date.now()-t0>budget){go(max>=y*0.6?max:s0);stop();return;}
 // belum cukup tinggi: geser ke dasar yang sudah diketahui; gambar lazy di sana mulai dimuat dan menambah tinggi dokumen
 go(Math.max(0,max));
}
window.__rsActive=true;window.__rsStop=stop;
for(var i=0;i<ev.length;i++)addEventListener(ev[i],stop,{capture:true,passive:true});
addEventListener('popstate',stop);
step();
if(window.__rsActive)tm=setInterval(step,200);
})(__Y__,__BUDGET__);
"""

// Posisi scroll saat ini, atau -1 bila belum layak disimpan (halaman belum selesai / pemulihan sedang berjalan)
const val SAVE_POS_JS = """
(function(){
if(window.__rsActive||document.readyState!=='complete')return -1;
return Math.round(window.scrollY||document.documentElement.scrollTop||0);
})()
"""
