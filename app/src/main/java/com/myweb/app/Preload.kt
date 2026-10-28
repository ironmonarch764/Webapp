package com.myweb.app

// Skrip yang disuntikkan ke halaman: memuat gambar di depan layar lebih awal (lazy-load dipaksa jalan)
// dan membuka koneksi lebih dulu (preconnect) ke server gambar.
// AHEAD = berapa gambar di depan posisi baca yang dimuat duluan. Perbesar = lebih mulus tapi boros kuota.
const val PRELOAD_JS = """
(function(){
if(window.__pre)return;window.__pre=1;
var AHEAD=4,A=['data-src','data-lazy-src','data-original','data-lazy','data-echo'],done=new WeakSet(),origins={},timer=0,first=0;
function pc(u){try{var o=new URL(u,location.href).origin;if(o===location.origin||origins[o])return;origins[o]=1;var l=document.createElement('link');l.rel='preconnect';l.href=o;(document.head||document.documentElement).appendChild(l);}catch(e){}}
function bump(img){
 if(done.has(img))return;done.add(img);
 try{
  img.loading='eager';
  var ss=img.getAttribute('data-srcset');
  if(ss&&!img.getAttribute('srcset'))img.setAttribute('srcset',ss);
  for(var i=0;i<A.length;i++){
   var v=img.getAttribute(A[i]);
   if(v&&v.indexOf(' ')<0&&v.charAt(0)!=='{'){
    var c=img.getAttribute('src');
    if(!c||c.indexOf('data:')===0||img.naturalWidth<2){img.setAttribute('src',v);}
    pc(v);break;
   }
  }
  var s=img.currentSrc||img.getAttribute('src');if(s)pc(s);
 }catch(e){}
}
// gambar "layak" = tidak tersembunyi (display:none, mis. oleh filter kosmetik) dan belum jauh di atas layar
function ok(img,vh){
 var r=img.getBoundingClientRect();
 if(r.width===0&&r.height===0&&img.offsetParent===null)return false;
 return r.bottom>-vh;
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
 for(var j=i,c=0;j<n&&c<AHEAD;j++){
  if(j>i&&!ok(im[j],vh))continue;
  bump(im[j]);c++;
 }
}
function sched(){if(!timer)timer=setTimeout(run,300);}
addEventListener('scroll',sched,{passive:true});
addEventListener('resize',sched);
document.addEventListener('visibilitychange',sched);
// hanya bereaksi bila ada <img> baru; iklan/penghitung yang terus mengubah DOM tidak lagi memicu hitung ulang layout
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
