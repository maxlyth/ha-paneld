// ha-paneld info page — live perf/responsiveness/proximity polling.
// Served as a static asset (linted by CI: `node --check`) rather than embedded in a Kotlin
// string, so syntax errors (e.g. an apostrophe in a single-quoted string) are caught at build.
var cpuH=[],ramH=[],gpuH=[],MAX=120,perfMode='';  // ~4 min at 2s
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
  if(nm.textContent!==p.name)nm.textContent=p.name;nm.title=empty?'':p.name;
  // The observer's own row (ha-paneld + its sampling probes) is excluded from the ranking and shown
  // dimmed at the bottom, so the list reads as the panel's real workload.
  var dim=empty||p.self;nm.style.color=dim?'#888':'';cp.style.color=p.self?'#888':'';
  tr.style.borderTop=p.self?'1px solid #2a2a2a':'';
  nm.style.fontStyle=p.self?'italic':'';cp.style.fontStyle=p.self?'italic':'';
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
function drawResp(hist){
 var c=document.getElementById('respchart'),x=c.getContext('2d'),W=c.width,H=c.height;
 x.clearRect(0,0,W,H);
 x.font='10px system-ui,sans-serif';x.textBaseline='top';x.lineWidth=1;
 var lanes=[
  {name:'interaction',values:hist&&hist.interactionMs||[],max:1000,col:'#d04a3b',unit:'ms'},
  {name:'state updates',values:hist&&hist.updatesPerSec||[],max:Math.max(50,Math.max.apply(null,hist&&hist.updatesPerSec||[0])),col:'#4a9eff',unit:'/s'},
  {name:'main blocked',values:hist&&hist.blockedMsPerSec||[],max:1000,col:'#f5a623',unit:'ms/s'}];
 var lh=H/lanes.length;
 lanes.forEach(function(lane,index){
  var top=index*lh,bottom=top+lh-1;x.strokeStyle='#383838';x.beginPath();x.moveTo(0,bottom);x.lineTo(W,bottom);x.stroke();
  x.fillStyle='#0d0d0d';x.fillRect(0,top,116,14);x.fillStyle='#888';
  x.fillText(lane.name+' · '+Math.round(lane.max)+' '+lane.unit,3,top+2);
  var a=lane.values,n=a.length;if(n<2)return;var sx=W/47;
  x.beginPath();for(var i=0;i<n;i++){var px=W-(n-1-i)*sx,py=bottom-Math.min(1,a[i]/lane.max)*(lh-17);
   i?x.lineTo(px,py):x.moveTo(px,py);}x.strokeStyle=lane.col;x.lineWidth=2;x.stroke();
 });
}
function fmtRate(n,unit){return n<10?n.toFixed(1)+' '+unit:Math.round(n)+' '+unit;}
function fmtBytes(n){if(n>=1048576)return (n/1048576).toFixed(1)+' MiB/s';if(n>=1024)return (n/1024).toFixed(1)+' KiB/s';return Math.round(n)+' B/s';}
function causeLabel(c){return ({state_stream:'State stream pressure',dashboard_script:'Dashboard/card JavaScript',
 rendering_or_media:'Rendering, layout, or media',system_contention:'System process contention',
 memory_or_renderer_instability:'Renderer or memory instability',dashboard_or_state_proxy:'Dashboard or state load (proxy)',
 no_clear_dominant_cause:'No clear dominant cause'})[c]||'Collecting evidence';}
function rendererMeasurement(mode,r){
 if(mode==='builtin_unavailable')return {header:'· built-in observer unavailable',
  title:'The built-in renderer is active without its live browser observer',
  rows:[{label:'Measurement mode',val:'built-in live observer unavailable',col:'#888'},
   {label:'How to restore it',val:'reload the built-in dashboard to retry',
    suf:'· if this persists, update the panel WebView'}]};
 if(r&&r.status!=='no-renderer'){
  var rows=[{label:'Measurement mode',val:'renderer CPU proxy',suf:'· not actual tap latency',col:'#888'},
   {label:'Dashboard main-thread',val:r.mainPct+'% of one core',suf:'· 100% means the renderer thread is saturated',bold:true}];
  if(r.jankPct!=null)rows.push({label:'Rendering proxy',val:r.jankPct+'% janky',suf:'· worst frame '+r.p99+' ms'});
  return {header:'· external renderer proxy',title:'measuring '+r.pkg,rows:rows};
 }
 return {header:'',title:'',rows:[{label:'Measurement mode',val:'External renderer proxy needs root/helper access',col:'#888'}]};
}
async function perf(){
 if(document.hidden)return;   // a hidden/background tab must not keep the sampler (or panel) busy
 try{
  var d=await (await fetch('/api/v1/perf')).json();
  if(d.hist){cpuH=d.hist.cpu||[];ramH=d.hist.ram||[];gpuH=d.hist.gpu||[];}  // server FIFO
  draw();
  var ramOk=d.memTotalMb!=null&&d.memTotalMb>0&&d.memUsedMb!=null;
  var ramPct=ramOk?Math.round(d.memUsedMb*100/d.memTotalMb):null;
  var peak=(d.cores&&d.cores.length)?Math.max.apply(null,d.cores):d.cpu;
  var fok=!!(d.freqMhz&&d.freqMhz.length),cur=fok?Math.max.apply(null,d.freqMhz):0,mx=d.freqMaxMhz||0;
  var rows=[{label:'CPU',val:d.cpu==null?'–':d.cpu+'%',suf:peak==null?'waiting for sample':'peak core '+peak+'%'},
   opt('clk','CPU clock',fok,(cur/1000).toFixed(2)+' GHz',mx?'/ '+(mx/1000).toFixed(2)+' GHz max':''),
   opt('gpu','GPU',d.gpu!=null,d.gpu+'%',d.gpuMhz?d.gpuMhz+' MHz':''),
   {label:'RAM',val:ramOk?d.memUsedMb+' / '+d.memTotalMb+' MB ('+ramPct+'%)':'–'},
   opt('load','Load avg',!!(d.load&&d.load.length),d.load?d.load.join('  '):''),
   opt('temp','Temperature',d.tempC!=null,d.tempC!=null?d.tempC.toFixed(1)+' °C':'')];
  paint('perf',rows.filter(Boolean));
  paintTop(d.top);
  var r=d.render,smh=document.getElementById('smhdr'),dash=d.dashboard;
  perfMode=dash&&dash.mode||'';var direct=perfMode==='builtin_direct';
  // Built-in renderer self-measurement (time-to-interactive + involuntary-reload churn). It runs
  // in-process, so — unlike the root/daemon main-thread metric below — it shows even with no root.
  var bRows=[],b=d.builtin;
  if(b){
   if(b.ttiColdMs>=0)bRows.push({label:'Time to interactive',val:(b.ttiColdMs/1000).toFixed(1)+'s cold',suf:b.ttiWarmMedianMs>=0?'· reload '+(b.ttiWarmMedianMs/1000).toFixed(1)+'s':'· launch → dashboard ready'});
   bRows.push({label:'Renderer reloads (24h)',val:''+b.reloads24h,col:b.reloads24h>0?'#d9a528':'#888',suf:b.reloads24h>0?'· heap/OOM churn':'· stable'});
  }
  if(direct&&dash){
   smh.textContent='· built-in live instrumentation';var it=dash.interaction||{},bl=dash.blocking||{};
   var causeCol=dash.confidence==='high'?'#d04a3b':(dash.confidence==='medium'?'#d9a528':'#888');
   var sm=[{label:'Likely cause',val:causeLabel(dash.likelyCause),suf:'· '+dash.confidence+' confidence',col:causeCol,bold:true}];
   if(it.count)sm.push({label:'Tap response',val:'~p50 '+it.p50Ms+' ms · ~p95 '+it.p95Ms+' ms',
    suf:'· worst '+it.worstMs+' ms = input '+it.inputDelayMs+' + handler '+it.processingMs+' + presentation '+it.presentationMs});
   else sm.push({label:'Tap response',val:'interact with the dashboard to measure',col:'#888'});
   sm.push({label:'Main-thread blocking',val:fmtRate(bl.blockedMsPerSec||0,'ms/s'),
    suf:'· p95 '+Math.round(bl.blockedP95MsPerSec||0)+' ms/s · longest frame '+Math.round(bl.longestFrameMs||0)+' ms'});
   paint('smtbl',sm.concat(bRows));drawResp(dash.history||{});
  }else{
   drawResp({});
   var measurement=rendererMeasurement(perfMode,r);
   smh.textContent=measurement.header;smh.title=measurement.title;
   paint('smtbl',measurement.rows.concat(bRows));
  }
  var stream=dash&&dash.stateStream||{},filter=dash&&dash.filter||{},sr=[];
  if(direct&&dash.sampleCount){
   sr.push({label:'State updates',val:fmtRate(stream.updatesPerSec||0,'/s'),suf:'· p95 '+fmtRate(stream.updatesP95PerSec||0,'/s')});
   sr.push({label:'State payload',val:fmtBytes(stream.payloadBytesPerSec||0),suf:'· p95 '+fmtBytes(stream.payloadP95BytesPerSec||0)+' · uncompressed JSON'});
   sr.push({label:'State-event main thread',val:fmtRate(stream.mainThreadMsPerSec||0,'ms/s'),
    suf:'· p95 '+Math.round(stream.mainThreadP95MsPerSec||0)+' ms/s · longest '+Math.round(stream.longestStateTaskMs||0)+' ms'});
   sr.push({label:'Initial hydration',val:(stream.hydrationUpdates||0)+' entities'});
   sr.push({label:'Subscription',val:filter.active?'filtered to '+filter.entityCount+' entities':'unfiltered',
    col:filter.active?'#48c774':'#d9a528'});
   (dash.topEntities||[]).forEach(function(e){sr.push({label:'Noisy entity',val:e.entityId,
    suf:'· '+e.updates1m+' updates/min · '+fmtBytes(e.payloadBps1m||0)});});
   if(!(dash.topEntities||[]).length)sr.push({label:'Noisy entities',val:'aggregate only',
    suf:'· enable automatic entity learning to identify contributors',col:'#888'});
   if(stream.droppedFrames)sr.push({label:'Measurement drops',val:''+stream.droppedFrames,col:'#d9a528'});
  }else sr.push({label:'State stream',val:direct?'waiting for Home Assistant state traffic':'available with the built-in renderer',col:'#888'});
  paint('streamtbl',sr);
  hwm('smtbl');hwm('streamtbl');hwm('topproc');
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
window.addEventListener('resize',function(){['smtbl','streamtbl','topproc'].forEach(function(id){var t=document.getElementById(id),c=t&&t.parentNode;if(c){c._hwm=0;c.style.minHeight='';}});});

// Controls panel: collapse the labelled action buttons (Back/Recents/Launcher/Admin) to icons-only when
// the row would wrap to 2 lines. Force one line, check overflow, toggle .collapsed. Runs on load, resize,
// and after the controls re-render (hydrate + the 2s status refresh).
function fitControls(){
 var row=document.querySelector('#ctlzone .ctlrow');
 if(!row)return;
 row.classList.remove('collapsed');                 // show labels, let it wrap naturally
 var btn=row.querySelector('.pbtn');
 var oneLine=btn?btn.offsetHeight:32;
 var wrapped=row.offsetHeight>oneLine+4;             // taller than one button => it wrapped to 2+ lines
 row.classList.toggle('collapsed',wrapped);
}
window.addEventListener('resize',fitControls);
if(document.readyState!=='loading')fitControls();else document.addEventListener('DOMContentLoaded',fitControls);
function inspApply(d){
 var hdr=document.getElementById('insthdr'),hint=document.getElementById('insthint');
 var hp='<b>'+location.hostname+':'+d.port+'</b>';
 hdr.textContent=d.running?'· on':'· off';
 if(d.status==='no-socket')hint.innerHTML='Enable it on the dashboard first: Companion → Settings → Troubleshooting → "WebView remote debugging", then relaunch the dashboard and press Enable again.';
 else if(d.status==='needs-root')hint.textContent='Needs root (su) on this panel.';
 else if(d.status==='failed'||d.status==='no-binary')hint.textContent='Could not start the relay.';
 else if(d.running)hint.innerHTML='Relay on. In <b>chrome://inspect</b> the dashboard now appears under Remote Target — click <b>inspect</b>. If it does not show, the host must be added <i>before</i> enabling — add '+hp+' in Configure…, then refresh chrome://inspect. Exposes DevTools to the LAN while on; press Stop when done.';
 else hint.innerHTML=(perfMode==='builtin_direct'?'The performance cards above use direct built-in instrumentation without DevTools. ':'')+'For deeper inspection, open <b>chrome://inspect</b> → <b>Configure…</b>, add '+hp+', then press <b>Enable</b>. Companion also requires Settings → Troubleshooting → <b>WebView remote debugging</b> and a dashboard relaunch. The relay needs root.';
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

// Screenshot card: render the app-private last-successful capture immediately, then refresh in the
// background. Only swap after the new PNG has fully loaded, so a slow or failed capture never blanks
// the useful placeholder. The timestamp makes every Dashboard visit a genuinely fresh request.
function refreshScreenshot(card){
 var sc=card&&card.querySelector('.shot'),im=sc&&sc.querySelector('img');if(!im||im.dataset.refreshing==='1')return;
 im.dataset.refreshing='1';var url='/api/v1/screenshot.png?t='+Date.now();
 fetch(url,{cache:'no-store'}).then(function(r){if(!r.ok)throw new Error('capture failed');
  var id=r.headers.get('X-ha-paneld-Screenshot-Id');return r.blob().then(function(blob){return{id:id,blob:blob};});})
 .then(function(fresh){var objectUrl=URL.createObjectURL(fresh.blob);
  im.onload=function(){sc.classList.remove('failed');sc.classList.add('loaded');URL.revokeObjectURL(objectUrl);
   if(fresh.id&&/^[0-9a-f]{64}$/.test(fresh.id)){var seed=new Image();
    seed.src='/api/v1/screenshot.png?cached='+fresh.id;}};
  im.src=objectUrl;im.dataset.refreshing='0';})
 .catch(function(){im.dataset.refreshing='0';});
}
function showAndRefreshScreenshot(card,cachedUrl){
 var im=card&&card.querySelector('img');if(!im)return;
 if(!im.getAttribute('src')&&cachedUrl)im.src=cachedUrl;
 card.style.display='';refreshScreenshot(card);
}

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
  var cz=document.getElementById('ctlzone');if(cz&&d.controls){cz.innerHTML=d.controls;fitControls();}
  var sc=document.getElementById('shotcard');
  if(sc){if(d.shot){showAndRefreshScreenshot(sc,d.shotCached);}
   else{sc.style.display='none';}}
 }
 function hydrate(tries){fetch('/api/v1/info').then(function(r){return r.json();}).then(apply)
  .catch(function(){if(tries>0)setTimeout(function(){hydrate(tries-1);},3000);});}
 hydrate(10);
})();
// A warm server render does not run hydration, so explicitly start its background refresh too.
(function(){var sc=document.getElementById('shotcard');if(sc&&sc.style.display!=='none')showAndRefreshScreenshot(sc,'');})();
// Dismiss a component-update banner for its current version (POST the label+version; the server keeps
// it hidden until a newer release ships — see Config.ignoreUpdate). Removes the banner on success.
function ignoreUpdate(btn){var b=btn.closest('.setup');if(!b)return;
 var label=b.getAttribute('data-update')||'',version=b.getAttribute('data-version')||'';
 btn.disabled=true;
 fetch('/api/v1/updates/ignore',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
  body:'label='+encodeURIComponent(label)+'&version='+encodeURIComponent(version)})
  .then(function(r){if(r.ok)b.remove();else btn.disabled=false;})
  .catch(function(){btn.disabled=false;});}
