package com.myweb.app

val DEFAULT_AD_HOSTS = setOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "google-analytics.com", "googletagmanager.com", "adservice.google.com",
    "adnxs.com", "adsterra.com", "popads.net", "popcash.net",
    "propellerads.com", "exoclick.com", "juicyads.com", "trafficjunky.com",
    "clickadu.com", "hilltopads.net", "adcash.com", "mgid.com",
    "taboola.com", "outbrain.com", "revcontent.com", "onclickads.net",
    "onclasrv.com", "a-ads.com", "bidvertiser.com", "yllix.com",
    "admaven.com", "ad-maven.com", "monetag.com", "richads.com",
    "pubmatic.com", "rubiconproject.com", "criteo.com",
    "amazon-adsystem.com", "adsco.re", "tsyndicate.com",
    "profitablegatecpm.com", "profitablecpmrate.com",
    "highperformanceformat.com", "effectivegatecpm.com",
    "pushails.com", "adskeeper.com", "ezoic.net", "histats.com"
)

val LISTS = listOf(
    "https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/master/Filters/filter.txt",
    "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext",
    "https://easylist.to/easylist/easylist.txt",
    "https://raw.githubusercontent.com/ABPindo/indonesianadblockrules/master/subscriptions/abpindo.txt"
)

val BASE_CSS = """ins.adsbygoogle,[id^="google_ads"],[class*="adsbygoogle"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"]{opacity:0!important;pointer-events:none!important}"""

val PICKER_JS = """
(function(){
if(window.__pk){return;} window.__pk=true;
var sel=null;
var box=document.createElement('div');
box.style.cssText='position:fixed;z-index:2147483646;pointer-events:none;border:3px solid #f43f5e;background:rgba(244,63,94,.28);display:none;box-sizing:border-box';
var bar=document.createElement('div');
bar.style.cssText='position:fixed;left:0;right:0;top:0;z-index:2147483647;background:#111;color:#fff;font:15px sans-serif;padding:36px 12px 12px;text-align:center';
var msg=document.createElement('div');
msg.textContent='Ketuk elemen yang mau diblokir';
msg.style.marginBottom='8px';
bar.appendChild(msg);
function esc(s){return CSS.escape(s);}
function path(el){
  var parts=[];
  while(el&&el.nodeType===1&&el!==document.body&&el!==document.documentElement){
    var p=el.tagName.toLowerCase();
    if(el.id){parts.unshift(p+'#'+esc(el.id));break;}
    var cls=(el.getAttribute('class')||'').trim().split(/\s+/).filter(function(c){return c&&c.length<30;}).slice(0,2);
    if(cls.length){p+='.'+cls.map(esc).join('.');}
    var sib=el.parentNode?Array.prototype.filter.call(el.parentNode.children,function(x){return x.tagName===el.tagName;}):[];
    if(sib.length>1){p+=':nth-of-type('+(sib.indexOf(el)+1)+')';}
    parts.unshift(p);
    var s=parts.join('>');
    try{if(document.querySelectorAll(s).length===1){return s;}}catch(e){}
    el=el.parentNode;
  }
  return parts.join('>');
}
function show(el){
  sel=el;
  var r=el.getBoundingClientRect();
  box.style.left=r.left+'px';box.style.top=r.top+'px';
  box.style.width=r.width+'px';box.style.height=r.height+'px';
  box.style.display='block';
  msg.textContent='Dipilih: '+el.tagName.toLowerCase();
}
var evs=['touchstart','touchend','mousedown','mouseup','pointerdown','pointerup'];
function stopper(e){if(bar.contains(e.target)){return;}e.stopPropagation();}
function onClick(e){
  if(bar.contains(e.target)){return;}
  e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();
  show(e.target);
}
function cleanup(){
  document.removeEventListener('click',onClick,true);
  evs.forEach(function(n){document.removeEventListener(n,stopper,true);});
  if(box.parentNode){box.parentNode.removeChild(box);}
  if(bar.parentNode){bar.parentNode.removeChild(bar);}
  window.__pk=false;
}
function btn(t,f){
  var b=document.createElement('button');
  b.textContent=t;
  b.style.cssText='margin:4px;padding:10px 14px;font-size:15px;border:0;border-radius:8px;background:#2563eb;color:#fff';
  b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();f();});
  bar.appendChild(b);
}
btn('Induk',function(){
  if(sel&&sel.parentElement&&sel.parentElement!==document.body&&sel.parentElement!==document.documentElement){show(sel.parentElement);}
});
btn('Blokir',function(){
  if(!sel){return;}
  var s=path(sel);
  if(!s){return;}
  sel.style.setProperty('display','none','important');
  Android.addRule(s);
  cleanup();
});
btn('Batal',function(){Android.pickerDone();cleanup();});
document.documentElement.appendChild(box);
document.documentElement.appendChild(bar);
document.addEventListener('click',onClick,true);
evs.forEach(function(n){document.addEventListener(n,stopper,true);});
})();
"""
