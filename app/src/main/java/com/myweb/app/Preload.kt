package com.myweb.app

// Skrip yang disuntikkan ke halaman: memuat gambar di depan layar lebih awal (lazy-load dipaksa jalan)
// dan membuka koneksi lebih dulu (preconnect) ke server gambar.
// AHEAD = berapa gambar di depan posisi baca yang dimuat duluan. Perbesar = lebih mulus tapi boros kuota.
const val PRELOAD_JS = """
(function(){
if(window.__pre)return;window.__pre=1;
var AHEAD=6,A=['data-src','data-lazy-src','data-original','data-lazy','data-echo'],done=new WeakSet(),origins={},timer=0;
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
function run(){
 timer=0;if(window.__preOff)return;
 var im=document.images,n=im.length,vh=window.innerHeight||800,f=-1;
 for(var i=0;i<n;i++){if(im[i].getBoundingClientRect().bottom>-vh){f=i;break;}}
 if(f<0)return;
 for(var j=f;j<n&&j<f+AHEAD;j++)bump(im[j]);
}
function sched(){if(!timer)timer=setTimeout(run,150);}
addEventListener('scroll',sched,{passive:true});
addEventListener('resize',sched);
try{new MutationObserver(sched).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
run();setTimeout(run,600);setTimeout(run,1800);
})();
"""
