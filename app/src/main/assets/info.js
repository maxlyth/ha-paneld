// ha-paneld info page — live perf/responsiveness/proximity polling.
// Served as a static asset (linted by CI: `node --check`) rather than embedded in a Kotlin
// string, so syntax errors (e.g. an apostrophe in a single-quoted string) are caught at build.
var cpuH=[],ramH=[],gpuH=[],MAX=120;  // ~4 min at 2s
// Reconcile a key/value table IN PLACE — stable DOM + textContent, never innerHTML. Rebuilding innerHTML
// each poll destroyed the scroll-anchor nodes (the page jumped) and thrashed layout; updating text on
// persistent nodes lets the browser hold scroll position and skips reflow. rows=[{label,val,suf,col,bold}].
function paint(id,rows){
 var t=document.getElementById(id); if(!t)return;
 if(!t._p){t.textContent='';t._p=true;}            // drop the server-rendered placeholder once, then reuse
 for(var i=0;i<rows.length;i++){var r=rows[i],tr=t.children[i];
  if(!tr){tr=document.createElement('tr');tr.appendChild(document.createElement('th'));
   var td=document.createElement('td');td.appendChild(document.createElement('span'));
   td.appendChild(document.createTextNode(' '));var s=document.createElement('span');s.style.color='#888';
   td.appendChild(s);tr.appendChild(td);t.appendChild(tr);}
  var th=tr.children[0],v=tr.children[1].children[0],sf=tr.children[1].children[1];
  if(th.textContent!==r.label)th.textContent=r.label;
  if(v.textContent!==r.val)v.textContent=r.val;
  v.style.color=r.col||'';v.style.fontWeight=r.bold?'600':'';
  var suf=r.suf||'';if(sf.textContent!==suf)sf.textContent=suf;
  tr.children[1].title=r.val+(suf?' '+suf:'');}            // full value on hover (cells truncate)
 while(t.children.length>rows.length)t.removeChild(t.lastChild);
}
// Top-processes: persistent header + reconciled data rows (or a single muted message row).
function paintTop(top,msg){
 var t=document.getElementById('topproc');
 if(!t._h){var hr=document.createElement('tr'),a=document.createElement('th');a.textContent='Process';
  var b=document.createElement('th');b.className='num';b.textContent='% CPU';hr.appendChild(a);hr.appendChild(b);
  t.textContent='';t.appendChild(hr);t._h=true;}
 var empty=!top||!top.length,data=empty?[{name:msg||'needs root (su)'}]:top;
 t.children[0].style.display=empty?'none':'';
 for(var i=0;i<data.length;i++){var p=data[i],tr=t.children[i+1];
  if(!tr){tr=document.createElement('tr');tr.appendChild(document.createElement('td'));
   var c=document.createElement('td');c.className='num';tr.appendChild(c);t.appendChild(tr);}
  var nm=tr.children[0],cp=tr.children[1];
  if(nm.textContent!==p.name)nm.textContent=p.name;nm.style.color=empty?'#888':'';nm.title=empty?'':p.name;
  var cv=p.cpu==null?'':p.cpu+'%';if(cp.textContent!==cv)cp.textContent=cv;}
 while(t.children.length>data.length+1)t.removeChild(t.lastChild);
}
// Optional metric latch: once seen, the row stays (showing '–' when momentarily absent) for stable height.
var pseen={};
function opt(k,label,ok,val,suf){if(ok)pseen[k]=true;if(!pseen[k])return null;
 return {label:label,val:ok?val:'–',suf:ok?(suf||''):'',col:ok?'':'#888'};}
function draw(){
 var c=document.getElementById('perfchart'),x=c.getContext('2d'),W=c.width,H=c.height;
 x.clearRect(0,0,W,H);
 x.lineWidth=1;x.font='11px system-ui,sans-serif';x.textBaseline='middle';
 [25,50,75].forEach(function(p){var y=H-p/100*H;
  x.strokeStyle='#383838';x.beginPath();x.moveTo(0,y);x.lineTo(W,y);x.stroke();
  x.fillStyle='#0d0d0d';x.fillRect(0,y-7,26,14);x.fillStyle='#888';x.fillText(p+'%',2,y);});
 function line(a,col){
  if(a.length<2)return;
  x.strokeStyle=col;x.lineWidth=2;x.beginPath();
  var n=a.length,sx=W/(MAX-1);
  for(var i=0;i<n;i++){var px=W-(n-1-i)*sx,py=H-(a[i]/100)*H;i?x.lineTo(px,py):x.moveTo(px,py);}
  x.stroke();
 }
 line(gpuH,'#f5a623');line(ramH,'#48c774');line(cpuH,'#4a9eff');
}
function drawSm(hist){
 var c=document.getElementById('smchart'),x=c.getContext('2d'),W=c.width,H=c.height;
 x.clearRect(0,0,W,H);
 x.font='11px system-ui,sans-serif';x.textBaseline='middle';x.lineWidth=1;
 [[50,'#3a5a42'],[85,'#6a5526']].forEach(function(t){var y=H-(t[0]/100)*H;x.strokeStyle=t[1];x.beginPath();x.moveTo(0,y);x.lineTo(W,y);x.stroke();x.fillStyle='#0d0d0d';x.fillRect(0,y-7,26,14);x.fillStyle='#888';x.fillText(t[0]+'%',2,y);});
 if(!hist||hist.length<2)return;
 var n=hist.length,sx=W/(MAX-1),last=hist[n-1];
 var col=last<5?'#48c774':(last<15?'#d9a528':'#d04a3b');
 function PX(i){return W-(n-1-i)*sx;}function PY(i){return H-(Math.min(100,hist[i])/100)*H;}
 x.beginPath();x.moveTo(PX(0),H);for(var i=0;i<n;i++)x.lineTo(PX(i),PY(i));x.lineTo(PX(n-1),H);x.closePath();x.fillStyle=col+'22';x.fill();
 x.beginPath();for(var i=0;i<n;i++){i?x.lineTo(PX(i),PY(i)):x.moveTo(PX(i),PY(i));}x.lineWidth=2;x.strokeStyle=col;x.stroke();
}
async function perf(){
 if(document.hidden)return;   // a hidden/background tab must not keep the sampler (or panel) busy
 try{
  var d=await (await fetch('/api/v1/perf')).json();
  setInstr(d.enabled!==false);
  if(d.enabled===false){paint('perf',[{label:'',val:'instrumentation off — turn it on to measure',col:'#888'}]);
   paintTop(null,'instrumentation off');paint('smtbl',[{label:'',val:'instrumentation off',col:'#888'}]);
   document.getElementById('perfage').textContent='· off';return;}
  if(d.hist){cpuH=d.hist.cpu||[];ramH=d.hist.ram||[];gpuH=d.hist.gpu||[];}  // server FIFO
  draw();
  var ramPct=d.memTotalMb?Math.round(d.memUsedMb*100/d.memTotalMb):0;
  var peak=(d.cores&&d.cores.length)?Math.max.apply(null,d.cores):d.cpu;
  var fok=!!(d.freqMhz&&d.freqMhz.length),cur=fok?Math.max.apply(null,d.freqMhz):0,mx=d.freqMaxMhz||0;
  var rows=[{label:'CPU',val:d.cpu+'%',suf:'peak core '+peak+'%'},
   opt('clk','CPU clock',fok,(cur/1000).toFixed(2)+' GHz',mx?'/ '+(mx/1000).toFixed(2)+' GHz max':''),
   opt('gpu','GPU',d.gpu!=null,d.gpu+'%',d.gpuMhz?d.gpuMhz+' MHz':''),
   {label:'RAM',val:d.memUsedMb+' / '+d.memTotalMb+' MB ('+ramPct+'%)'},
   opt('load','Load avg',!!(d.load&&d.load.length),d.load?d.load.join('  '):''),
   opt('temp','Temperature',d.tempC!=null,d.tempC!=null?d.tempC.toFixed(1)+' °C':'')];
  paint('perf',rows.filter(Boolean));
  paintTop(d.top);
  var r=d.render,smh=document.getElementById('smhdr');
  if(r==null){smh.textContent='· needs root';paint('smtbl',[{label:'Responsiveness',val:'needs root to measure',col:'#888'}]);drawSm([]);}
  else if(r.status==='no-renderer'){smh.textContent='· waiting';drawSm(r.hist||[]);paint('smtbl',[{label:'Responsiveness',val:'no dashboard WebView detected yet',col:'#888'}]);}
  else{
   drawSm(r.hist);
   var col=r.verdict==='smooth'?'#48c774':(r.verdict==='occasional'?'#d9a528':'#d04a3b');
   var vv=r.verdict==='smooth'?'Snappy':(r.verdict==='occasional'?'Sluggish':'Laggy');
   smh.textContent='· '+(/homeassistant|companion/i.test(r.pkg)?'HA Companion App UI':r.pkg.split('.').pop());smh.title='measuring '+r.pkg;
   var sm=[{label:'How it feels',val:'● '+vv,col:col,bold:true},
    {label:'Dashboard main-thread',val:r.mainPct+'% of one core',suf:'(100% = event processing maxed out)',bold:true}];
   if(r.jankPct!=null){pseen.jank=true;sm.push({label:'Rendering load',val:r.jankPct+'% janky',suf:'· only counts when actively drawing (e.g. video) — worst frame '+r.p99+' ms'});}
   else if(pseen.jank)sm.push({label:'Rendering load',val:'idle — not drawing',col:'#888'});
   paint('smtbl',sm);
  }
  hwm('smtbl');hwm('topproc');
  document.getElementById('perfage').textContent='· live';
 }catch(e){document.getElementById('perfage').textContent='· unavailable';}
}
perf();setInterval(perf,2000);
// Live Sensors card — REUSABLE: sensorsCard(tableId, ageId) mounts the same card on any tab that
// includes a table + age element; polls /api/v1/sensors every 2s, pauses while the tab is hidden.
function sensorsCard(tbl,age){
 function fA(a){return a==null?'':(a<90?'· '+a+'s ago':(a<5400?'· '+Math.round(a/60)+'m ago':'· '+Math.round(a/3600)+'h ago'));}
 async function s(){
  if(document.hidden)return;
  try{
   var d=await (await fetch('/api/v1/sensors')).json(),rows=[];
   if(d.light&&d.light.present)rows.push({label:'Ambient light',val:d.light.lux!=null?d.light.lux+' lx':'no reading yet',suf:fA(d.light.age_s)});
   if(d.proximity&&d.proximity.present)rows.push({label:'Proximity',val:d.proximity.near==null?'no reading yet':(d.proximity.near?'near':'far'),suf:d.proximity.raw!=null?'· raw '+d.proximity.raw:''});
   if(d.temperature&&d.temperature.present)rows.push({label:'Temperature',val:d.temperature.c!=null?d.temperature.c+' °C':'no reading yet',suf:fA(d.temperature.age_s)});
   if(d.humidity&&d.humidity.present)rows.push({label:'Humidity',val:d.humidity.pct!=null?d.humidity.pct+' %':'no reading yet',suf:fA(d.humidity.age_s)});
   if(d.volume_pct!=null&&d.volume_pct>=0)rows.push({label:'Volume',val:d.volume_pct+' %'});
   if(d.brightness!=null&&d.brightness>=0)rows.push({label:'Brightness',val:d.brightness+' / 255'});
   if(!rows.length)rows.push({label:'',val:'no sensors on this panel',col:'#888'});
   paint(tbl,rows);
   var a=document.getElementById(age);if(a)a.textContent='· live';
  }catch(e){var a=document.getElementById(age);if(a)a.textContent='· unavailable';}
 }
 s();setInterval(s,2000);
}
sensorsCard('senstbl','sensage');
// High-water-mark: the two live cards whose heights swing most — Responsiveness (the Rendering-load row
// flips between a long "X% janky…" line and a short "idle") and Top processes (process names wrap to 1–2
// lines) — never shrink below the tallest they've been (latched min-height on the card). They stop jumping
// between 2s polls; grow occasionally, then settle. Reset on resize (column width changes re-wrap content,
// voiding the old max). Other cards keep wrapping freely — only these two opted in.
function hwm(id){var t=document.getElementById(id);if(!t)return;var c=t.parentNode,h=c.offsetHeight;if(h>(c._hwm||0)){c._hwm=h;c.style.minHeight=h+'px';}}
window.addEventListener('resize',function(){['smtbl','topproc'].forEach(function(id){var t=document.getElementById(id),c=t&&t.parentNode;if(c){c._hwm=0;c.style.minHeight='';}});});
function setInstr(on){var a=document.getElementById('instron'),b=document.getElementById('instroff');if(a)a.className='pbtn'+(on?' on':'');if(b)b.className='pbtn'+(on?'':' on');}
function instr(on){fetch('/api/v1/instrumentation',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'enabled='+on}).then(function(r){return r.json();}).then(function(){perf();}).catch(function(){});}
function inspApply(d){
 var hdr=document.getElementById('insthdr'),hint=document.getElementById('insthint');
 var hp='<b>'+location.hostname+':'+d.port+'</b>';
 hdr.textContent=d.running?'· on':'· off';
 if(d.status==='no-socket')hint.innerHTML='Enable it on the dashboard first: Companion → Settings → Troubleshooting → "WebView remote debugging", then relaunch the dashboard and press Enable again.';
 else if(d.status==='needs-root')hint.textContent='Needs root (su) on this panel.';
 else if(d.status==='failed'||d.status==='no-binary')hint.textContent='Could not start the relay.';
 else if(d.running)hint.innerHTML='Relay on. In <b>chrome://inspect</b> the dashboard now appears under Remote Target — click <b>inspect</b>. If it does not show, the host must be added <i>before</i> enabling — add '+hp+' in Configure…, then refresh chrome://inspect. Exposes DevTools to the LAN while on; press Stop when done.';
 else hint.innerHTML='Opens this panel’s dashboard DevTools in your browser — no adb. <b>Step 1:</b> on your computer open <b>chrome://inspect</b> → <b>Configure…</b> and add '+hp+' (chrome only polls hosts already in its list). <b>Step 2:</b> press <b>Enable</b>. Needs WebView debugging enabled + root.';
}
async function insp(){try{var d=await (await fetch('/api/v1/inspect')).json();inspApply(d);}catch(e){}}
function inspStart(){fetch('/api/v1/inspect/start',{method:'POST'}).then(function(r){return r.json();}).then(inspApply).catch(function(){});}
function inspStop(){fetch('/api/v1/inspect/stop',{method:'POST'}).then(function(r){return r.json();}).then(inspApply).catch(function(){});}
insp();
// Layout is pure CSS multi-column masonry now (.cards{columns:400px} in info.css) — the browser packs and
// height-balances the cards. No JS column packing (the old greedy mis-balanced on placeholder heights).

// Build watch moved to the shared /assets/buildwatch.js (loaded by EVERY page, not just the dashboard).

// Controls card actions: POST /action a=<back|recents|home|reboot|volup|voldn>. Reboot confirms first.
function act(a){if(a==='reboot'&&!confirm('Reboot this panel now?'))return;
 fetch('/api/v1/action',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'a='+a}).catch(function(){});}

// Reveal toggle for .secret fields (blurred by default). Auto-re-blurs after 20s so it can't be left
// revealed for a screenshot. Focusing a blurred input also un-blurs it (see info.css) so config stays editable.
function toggleReveal(){var on=document.body.classList.toggle('revealed');var b=document.getElementById('revbtn');
 if(b)b.textContent=on?'Hide':'Reveal';clearTimeout(window.__rev);
 if(on)window.__rev=setTimeout(function(){document.body.classList.remove('revealed');if(b)b.textContent='Reveal';},20000);}
// Click a single blurred value to reveal just it (toggle). Inputs reveal on focus already (info.css), so
// only handle non-input .secret spans here.
document.addEventListener('click',function(e){var s=e.target.closest&&e.target.closest('.secret');
 if(s&&s.tagName!=='INPUT')s.classList.toggle('shown');});

// Display-card diagonal: click to toggle inches <-> cm (W×H is in the title tooltip).
function diagToggle(el){el.textContent=(el.textContent.indexOf('cm')<0)?el.dataset.cm:el.dataset['in'];}

// Dashboard hydration: the shell now renders instantly (the probe-backed values used to block the
// whole page ~12s on PX30). When the server marked the page stale/cold (body data-hydrate="1"),
// fetch /api/v1/info — ready-to-inject HTML fragments rendered by the same Kotlin as the warm
// server render — and fill the facts/value/capabilities tables, banners, controls and screenshot.
(function(){
 if(document.body.getAttribute('data-hydrate')!=='1')return;
 function apply(d){
  var bz=document.getElementById('bannerzone');if(bz&&typeof d.banners==='string')bz.innerHTML=d.banners;
  Object.keys(d.cards||{}).forEach(function(id){var el=document.getElementById(id);if(!el)return;
   var card=el.closest('.card');
   if(d.cards[id]&&d.cards[id].trim()){el.innerHTML=d.cards[id];if(card)card.style.display='';}
   else if(card){card.style.display='none';}});          // probe says this card has nothing to show
  var cz=document.getElementById('ctlzone');if(cz&&d.controls)cz.innerHTML=d.controls;
  var sc=document.getElementById('shotcard');
  if(sc){if(d.shot){var im=sc.querySelector('img');
    if(im&&!im.getAttribute('src'))im.src=im.getAttribute('data-src');sc.style.display='';}
   else{sc.style.display='none';}}
 }
 function hydrate(tries){fetch('/api/v1/info').then(function(r){return r.json();}).then(apply)
  .catch(function(){if(tries>0)setTimeout(function(){hydrate(tries-1);},3000);});}
 hydrate(10);
})();
