// ha-paneld info page — live performance, responsiveness and learned proximity status.
// Served as a static asset (linted by CI: `node --check`) rather than embedded in a Kotlin
// string, so syntax errors (e.g. an apostrophe in a single-quoted string) are caught at build.
var cpuH=[],ramH=[],gpuH=[],MAX=120,perfMode='',topMode='cpu',topCpu=null,topRam=null;  // ~4 min at 2s
var TOP_PROCESS_MEMORY_FALLBACK_POLLS=12,topProcessMemoryPolls=0,topProcessMemoryPopulated=false;
// The camera source is already satisfied on a panel that has no camera card: the server omits the card
// on a board whose profile declares no camera, and a source that can never report would stall the
// remembered card sizes for every other card on the page.
var cardSizeSources={info:document.body.getAttribute('data-hydrate')!=='1',perf:false,topProcesses:false,sensors:false,inspect:false,camera:!document.getElementById('camtbl')};
function cardSizeSourcesReady(){return cardSizeSources.info&&cardSizeSources.perf&&cardSizeSources.topProcesses&&cardSizeSources.sensors&&cardSizeSources.inspect&&cardSizeSources.camera;}
function settleCardSizeMemory(){if(cardSizeSourcesReady()&&window.CardSizeMemory)window.CardSizeMemory.settle('dashboard-cards',1200);}
function cardSizeSourceReady(source){if(cardSizeSources[source])return;cardSizeSources[source]=true;settleCardSizeMemory();}
// A successful /perf response does not mean Top processes is ready. After a service restart its CPU
// ranking needs two heavy sampler passes (~10s apart); releasing the remembered card height on the
// first null response collapses the masonry, then expands it again when rows arrive. Keep the hint until
// rows exist, with a bounded successful-poll fallback for panels that cannot produce a ranking.
function topProcessMemoryReady(top){
 var populated=Array.isArray(top)&&top.length>0;
 if(populated&&!topProcessMemoryPopulated){topProcessMemoryPopulated=true;
  if(cardSizeSources.topProcesses)settleCardSizeMemory();else cardSizeSourceReady('topProcesses');return;}
 if(!cardSizeSources.topProcesses&&++topProcessMemoryPolls>=TOP_PROCESS_MEMORY_FALLBACK_POLLS)cardSizeSourceReady('topProcesses');
}
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
  var b=document.createElement('th');b.className='num';hr.appendChild(a);hr.appendChild(b);
  t.textContent='';t.appendChild(hr);t._h=true;}
 t.children[0].children[1].textContent=topMode==='ram'?'RAM':'% CPU';
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
  var cv=topMode==='ram'?(p.ramMb==null?'':p.ramMb+' MB'):(p.cpu==null?'':p.cpu+'%');if(cp.textContent!==cv)cp.textContent=cv;}
 while(t.children.length>data.length+1)t.removeChild(t.lastChild);
}
function setTopMode(mode){
 if(mode!=='cpu'&&mode!=='ram')return;topMode=mode;
 document.querySelectorAll('.top-process-mode').forEach(function(button){var on=button.dataset.mode===mode;button.classList.toggle('on',on);button.setAttribute('aria-pressed',on?'true':'false');});
 paintTop(mode==='ram'?topRam:topCpu,mode==='ram'?'RAM data unavailable':'needs root (su)');hwm('topproc');
}
// Noisy state-stream contributors: one sortable-looking data shape instead of repeating the same
// "Noisy entity" key/value label for every contributor.
function paintNoisy(entities,identified){
 var t=document.getElementById('noisyentities');if(!t)return;
 var rows=entities||[],empty=!rows.length;
  if(!t._h){var hr=document.createElement('tr');
  ['Top entities','Rate','Payload'].forEach(function(label,index){var h=document.createElement('th');
   h.textContent=label;if(index)h.className=index===1?'num rate':'num payload';hr.appendChild(h);});
  t.textContent='';t.appendChild(hr);t._h=true;}
 t.children[0].style.display=empty?'none':'';
 var data=empty?[{entityId:identified?'No noisy contributors in this sample':'Aggregate only — enable automatic entity learning to identify contributors'}]:rows;
 for(var i=0;i<data.length;i++){var e=data[i],tr=t.children[i+1];
  if(!tr){tr=document.createElement('tr');tr.appendChild(document.createElement('td'));
   var updates=document.createElement('td');updates.className='num rate';tr.appendChild(updates);
   var payload=document.createElement('td');payload.className='num payload';tr.appendChild(payload);t.appendChild(tr);}
  var id=tr.children[0],up=tr.children[1],pl=tr.children[2];
  if(id.textContent!==e.entityId)id.textContent=e.entityId;id.title=empty?'':e.entityId;
  var uv=empty?'':String(e.updates1h)+'/hr';if(up.textContent!==uv)up.textContent=uv;
  var pv=empty?'':fmtByteTotal(e.payloadBytes1h||0)+'/hr';if(pl.textContent!==pv)pl.textContent=pv;
  id.style.color=empty?'#888':'';up.style.color='';pl.style.color='';}
 while(t.children.length>data.length+1)t.removeChild(t.lastChild);
}
// Optional metric latch: once seen, the row stays (showing '–' when momentarily absent) for stable height.
var pseen={};
function opt(k,label,ok,val,suf){if(ok&&!pseen[k]){pseen[k]=true;settleCardSizeMemory();}if(!pseen[k])return null;
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
function fmtBytes(n){if(n>=1048576)return (n/1048576).toFixed(1)+' MB/s';if(n>=1024)return (n/1024).toFixed(1)+' KB/s';return Math.round(n)+' B/s';}
function fmtByteTotal(n){if(n>=1048576)return (n/1048576).toFixed(1)+' MB';if(n>=1024)return (n/1024).toFixed(1)+' KB';return Math.round(n)+' B';}
function causeLabel(c){return ({ha_network_path:'Network path to Home Assistant',state_stream:'State stream pressure',dashboard_script:'Dashboard/card JavaScript',
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
  topCpu=d.top;topRam=d.topRam;paintTop(topMode==='ram'?topRam:topCpu,topMode==='ram'?'RAM data unavailable':null);
  topProcessMemoryReady(topCpu);
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
   paintNoisy(dash.topEntities||[],!!filter.active);
   if(stream.droppedFrames)sr.push({label:'Measurement drops',val:''+stream.droppedFrames,col:'#d9a528'});
  }else{
   sr.push({label:'State stream',val:direct?'waiting for Home Assistant state traffic':'available with the built-in renderer',col:'#888'});
   paintNoisy([],false);
  }
  paint('streamtbl',sr);
  hwm('smtbl');hwm('streamtbl');hwm('topproc');
  document.getElementById('perfage').textContent='· live';
  cardSizeSourceReady('perf');
 }catch(e){document.getElementById('perfage').textContent='· unavailable';}
}
perf();setInterval(perf,2000);
// Live Sensors card — REUSABLE: sensorsCard(tableId, ageId) mounts the same card on any tab that
// includes a table + age element; polls /api/v1/sensors every 2s, pauses while the tab is hidden.
function formatBrightness(raw){
 if(typeof raw!=='number'||!isFinite(raw)||raw<0)return null;
 var value=Math.max(0,Math.min(255,Math.round(raw)));
 return Math.round(value*100/255)+'% ('+value+')';
}
function sensorsCard(tbl,age){
 function fA(a){return a==null?'':(a<90?'· '+a+'s ago':(a<5400?'· '+Math.round(a/60)+'m ago':'· '+Math.round(a/3600)+'h ago'));}
 async function s(){
  if(document.hidden)return;
  try{
   var d=await (await fetch('/api/v1/sensors')).json(),rows=[];
   if(d.light&&d.light.present)rows.push({label:'Ambient light',val:d.light.lux!=null?d.light.lux+' lx':'no reading yet',suf:fA(d.light.age_s)});
   if(d.proximity&&d.proximity.present){var p=d.proximity,phase=(p.learning||p.phase||'waiting').replace(/_/g,' ');
    rows.push({label:'Proximity',val:p.near==null?phase:(p.near?'near':'far'),suf:p.normalizedLevel==null?'· '+phase:'· '+p.normalizedLevel+'% normalized · '+phase});}
   if(d.temperature&&d.temperature.present)rows.push({label:'Temperature',val:d.temperature.c!=null?d.temperature.c+' °C':'no reading yet',suf:fA(d.temperature.age_s)});
   if(d.humidity&&d.humidity.present)rows.push({label:'Humidity',val:d.humidity.pct!=null?d.humidity.pct+' %':'no reading yet',suf:fA(d.humidity.age_s)});
   if(d.volume_pct!=null&&d.volume_pct>=0)rows.push({label:'Volume',val:d.volume_pct+' %'});
   var brightness=formatBrightness(d.brightness);
   if(brightness!=null)rows.push({label:'Brightness',val:brightness});
   if(!rows.length)rows.push({label:'',val:'no sensors on this panel',col:'#888'});
   paint(tbl,rows);
   var a=document.getElementById(age);if(a)a.textContent='· live';
   cardSizeSourceReady('sensors');
  }catch(e){var a=document.getElementById(age);if(a)a.textContent='· unavailable';}
 }
 s();setInterval(s,2000);
}
sensorsCard('senstbl','sensage');
// Live Camera stream card — what the encode session was ASKED for, beside what it is DELIVERING.
// Mounted only where the server rendered the card (a board whose profile declares a camera), and fed
// by /api/v1/camera/status, which is byte-for-byte the object /api/v1/status carries under `camera`.
//
// Two things this card deliberately does NOT do. It never quotes a CPU figure: a hardware encode runs
// in the codec HAL and through the compositor as much as in this app, so no single process is "what the
// camera costs" and naming one would be a comfortable lie — the panel's whole load is on the
// Performance and Top processes cards instead. And it never turns a shortfall into a ceiling: nothing
// here clamps, rewrites or hides what was requested. The request stands, the panel reports what it
// managed, and the person reading it makes the trade — which is the entire reason for showing it.
// A delivered rate below this share of the requested one is reported as a shortfall. It is a display
// tolerance, not a cap: a couple of frames' slack is ordinary pacing jitter and not worth an alarm.
var CAMERA_SHORTFALL_RATIO=0.85;
// Consecutive polls, two seconds apart, before the verdict flips — in either direction, so a recovery
// is trusted no faster than a fault. Three of them outrun the panel's own five-second delivery window,
// so one slow sample cannot raise the verdict and one good one cannot clear it.
var CAMERA_VERDICT_POLLS=3;
function cameraCard(tbl,hdr){
 if(!document.getElementById(tbl))return;
 // Three verdicts, not two: until a run reaches the threshold the honest answer is that the card does
 // not yet know, and saying "delivering what it was asked for" beside a rate that is plainly short
 // would be the card contradicting itself in the two polls before it makes up its mind.
 var shortRun=0,okRun=0,verdict='measuring';
 function n(v){return typeof v==='number'&&isFinite(v)?v:null;}
 function one(v){return Math.round(v*10)/10;}
 function head(text){var h=document.getElementById(hdr);if(h)h.textContent=text;}
 // A gap in the facts is not evidence of anything. Absence must never leave a verdict standing, and it
 // must never age into one either, so the runs reset with it.
 function forget(){shortRun=0;okRun=0;verdict='measuring';}
 function watchers(d){
  var c=n(d.clients)||0,s=n(d.stream_clients)||0;
  if(!c)return 'nobody is watching';
  return c+(c===1?' client':' clients')+(s?', '+s+' streaming':'');
 }
 function session(d){
  switch(d.state){
   case 'live':return 'open';
   case 'opening':return 'opening';
   case 'idle':return 'closed';
   case 'disabled':return 'off';
   case 'permission_needed':return 'waiting for the Android camera permission';
   case 'degraded':return 'stopped retrying after '+(n(d.consecutive_failures)||0)+' failures';
   case 'stopping':return 'stopping';
   case 'absent':return 'no camera on this panel';
   default:return 'unavailable';
  }
 }
 async function poll(){
  if(document.hidden)return;
  try{
   var d=await (await fetch('/api/v1/camera/status',{cache:'no-store'})).json();
   var rows=[{label:'Session',val:session(d),suf:'· '+watchers(d)}];
   var enc=(typeof d.encoder==='string'&&d.encoder)?d.encoder:null;
   var w=n(d.encode_width),h=n(d.encode_height),want=n(d.encode_fps),cap=n(d.encode_kbps);
   var got=n(d.delivered_fps),gotKbps=n(d.delivered_kbps);
   var act=(typeof d.action==='string'&&d.action&&d.action!=='none')?d.action:null;
   if(!enc){
    // Nothing is encoding, so there is nothing to compare and no verdict to keep. WHY nothing is
    // encoding differs, though, and "idle costs nothing" is true only of a camera that is actually
    // closed — a snapshot client holds it open and pays for every frame it converts, so saying the
    // panel is spending nothing there would be exactly the comfortable lie this card exists to avoid.
    if(d.state==='idle')rows.push({label:'Encoding',val:'nothing is being encoded',col:'#888',suf:'· an idle camera costs the panel nothing'});
    else if(d.state==='live'||d.state==='opening')rows.push({label:'Encoding',val:'no stream is encoding',col:'#888',suf:'· the camera is open for a snapshot'});
    else rows.push({label:'Encoding',val:'no stream is encoding',col:'#888',suf:act?'· '+act:''});
    forget();
    head('· '+session(d));
   }else{
    rows.push({label:'Encoder',val:enc,suf:(w&&h)?'· '+w+'×'+h:'· output size unavailable'});
    // An encoder bound to a session that has stopped receiving frames reports no delivered rate at
    // all, exactly as one that has not started yet does. Reading both as "starting…" would leave a
    // stalled stream looking like a warming-up one for as long as it stayed broken, so a classified
    // fault, or a captured frame older than the panel's own five-second delivery window, is named.
    var age=n(d.last_frame_age_ms);
    var fault=(typeof d.fault==='string'&&d.fault&&d.fault!=='none')?d.fault.replace(/_/g,' '):null;
    var stalled=got!=null?null:(fault?'stopped — '+fault:((age!=null&&age>5000)?'no frames for '+Math.round(age/1000)+'s':null));
    if(stalled){
     rows.push({label:'Frame rate',val:stalled,col:'#d9a528',bold:true,suf:want?'· asked for '+want+' fps':''});
     forget();
     head('· '+stalled);
    }else if(got==null){
     rows.push({label:'Frame rate',val:'starting…',col:'#888',suf:want?'· asked for '+want+' fps':'· requested rate unavailable'});
     forget();
     head('· starting');
    }else if(!want){
     rows.push({label:'Frame rate',val:one(got)+' fps delivered',suf:'· requested rate unavailable',col:'#888'});
     forget();
     head('· '+one(got)+' fps');
    }else{
     if(got<want*CAMERA_SHORTFALL_RATIO){okRun=0;if(++shortRun>=CAMERA_VERDICT_POLLS)verdict='short';}
     else{shortRun=0;if(++okRun>=CAMERA_VERDICT_POLLS)verdict='ok';}
     rows.push({label:'Frame rate',val:one(got)+' of '+want+' fps',bold:verdict==='short',col:verdict==='short'?'#d9a528':'',
      suf:'· delivered against what was asked for'});
     head('· '+one(got)+' of '+want+' fps');
    }
    // Bitrate is a cap, never a target: a still scene needs fewer bits and using fewer is the encoder
    // working, not failing. It is shown for the trade — resolution and rate are what spend it — and it
    // is deliberately kept out of the shortfall verdict, which is about frame rate alone.
    if(stalled)rows.push({label:'Bitrate',val:'nothing delivered',col:'#888',suf:cap?'· cap '+cap+' kbps':'· cap unavailable'});
    else if(gotKbps==null)rows.push({label:'Bitrate',val:'starting…',col:'#888',suf:cap?'· cap '+cap+' kbps':'· cap unavailable'});
    else if(!cap)rows.push({label:'Bitrate',val:gotKbps+' kbps',col:'#888',suf:'· cap unavailable'});
    // Measured on a WF1589T at 1080p: 4817 kbps against a 2000 kbps cap. The encoder can overshoot the
    // bitrate it was given, so the row cannot assume the delivered figure sits under the cap and call
    // every reading normal — that would print reassurance over the one number that had gone wrong.
    else if(gotKbps>cap)rows.push({label:'Bitrate',val:gotKbps+' of '+cap+' kbps cap',col:'#d9a528',bold:true,
     suf:'· over the cap it was given; the encoder is spending more of the network than it was allowed'});
    else rows.push({label:'Bitrate',val:gotKbps+' of '+cap+' kbps cap',suf:'· under the cap is normal for a still scene'});
    rows.push(stalled
     ?{label:'Delivery',val:'not delivering',col:'#d9a528',bold:true,
       suf:act?'· '+act:'· the encoder is bound to the session but no frames are arriving'}
     :verdict==='short'
     ?{label:'Delivery',val:'not keeping up',col:'#d9a528',bold:true,
       suf:'· lower the frame rate or the resolution on Configure → Camera, or accept the rate this panel can give; a dim room can also hold the sensor below its target'}
     :verdict==='ok'
      ?{label:'Delivery',val:'delivering what it was asked for',col:'#48c774'}
      :{label:'Delivery',val:'measuring…',col:'#888',suf:'· waiting for enough consecutive readings to judge'});
   }
   paint(tbl,rows);hwm(tbl);
  }catch(e){
   // The panel did not answer, so every figure on this card is now of unknown age. Showing the last
   // ones under a live heading would be the reassuring-but-wrong answer; drop them and say so.
   forget();paint(tbl,[{label:'Camera',val:'status unavailable',col:'#888',suf:'· the panel did not answer'}]);hwm(tbl);
   head('· unavailable');
  }
  cardSizeSourceReady('camera');
 }
 poll();setInterval(poll,2000);
}
cameraCard('camtbl','camhdr');
// High-water-mark: the two live cards whose heights swing most — Responsiveness (the Rendering-load row
// flips between a long "X% janky…" line and a short "idle") and Top processes (process names wrap to 1–2
// lines) — never shrink below the tallest they've been (latched min-height on the card). They stop jumping
// between 2s polls; grow occasionally, then settle. Reset on resize (column width changes re-wrap content,
// voiding the old max). Other cards keep wrapping freely — only these two opted in.
function hwm(id){var t=document.getElementById(id);if(!t)return;var c=t.parentNode,hint=c.getAttribute('data-card-size-hint');
 if(hint&&c.style.minHeight===hint+'px')c.style.minHeight='';
 var h=c.offsetHeight;if(h>(c._hwm||0))c._hwm=h;
 c.style.minHeight=hint?hint+'px':(c._hwm?c._hwm+'px':'');}
window.addEventListener('resize',function(){['smtbl','streamtbl','topproc','camtbl'].forEach(function(id){var t=document.getElementById(id),c=t&&t.parentNode;if(c){c._hwm=0;c.style.minHeight='';}});});

// Controls panel: at compact desktop widths collapse the labelled action row to icons-only when it would
// wrap. A narrow panel (600px or below) uses the CSS two-column, 48px labelled grid instead so Dashboard remains
// recognisable. Runs on load, resize, and after controls re-render (hydrate + the 2s status refresh).
function fitControls(){
 var row=document.querySelector('#ctlzone .ctlrow');
 if(!row)return;
 if(window.matchMedia&&window.matchMedia('(max-width:600px)').matches){row.classList.remove('collapsed');return;}
 row.classList.remove('collapsed');                 // show labels, let it wrap naturally
 var btn=row.querySelector('.pbtn');
 var oneLine=btn?btn.offsetHeight:32;
 var wrapped=row.offsetHeight>oneLine+4;             // taller than one button => it wrapped to 2+ lines
 row.classList.toggle('collapsed',wrapped);
}
window.addEventListener('resize',fitControls);
if(document.readyState!=='loading')fitControls();else document.addEventListener('DOMContentLoaded',fitControls);
function approvalMessage(body){return body&&body.message||'Approve this request on the panel, then retry it.';}
function responseBody(response){return response.text().then(function(text){
 var body={};try{body=text?JSON.parse(text):{};}catch(_){body={message:text};}
 if(response.status===202&&body&&body.error==='approval-required'){
  var error=new Error(approvalMessage(body));error.approvalRequired=true;error.body=body;throw error;
 }
 return {response:response,body:body};
});}
function inspApply(d){
 var hdr=document.getElementById('insthdr'),hint=document.getElementById('insthint'),start=document.getElementById('inspstart');
 var hp='<b>'+location.hostname+':'+d.port+'</b>';
 if(start)start.disabled=d.start_allowed===false;
 hdr.textContent=d.running?'· on':'· off';
 if(d.status==='hardened-disabled')hint.textContent='Unavailable while Hardened mode is enabled. Switch to Relaxed mode before exposing WebView developer tools to the LAN.';
 else if(d.status==='no-socket')hint.innerHTML='Enable it on the dashboard first: Companion → Settings → Troubleshooting → "WebView remote debugging", then relaunch the dashboard and press Enable again.';
 else if(d.status==='needs-root')hint.textContent='Needs root (su) on this panel.';
 else if(d.status==='failed'||d.status==='no-binary')hint.textContent='Could not start the relay.';
 else if(d.running)hint.innerHTML='Relay on. In <b>chrome://inspect</b> the dashboard now appears under Remote Target — click <b>inspect</b>. If it does not show, the host must be added <i>before</i> enabling — add '+hp+' in Configure…, then refresh chrome://inspect. Exposes DevTools to the LAN while on; press Stop when done.';
 else hint.innerHTML=(perfMode==='builtin_direct'?'The performance cards above use direct built-in instrumentation without DevTools. ':'')+'For deeper inspection, open <b>chrome://inspect</b> → <b>Configure…</b>, add '+hp+', then press <b>Enable</b>. Companion also requires Settings → Troubleshooting → <b>WebView remote debugging</b> and a dashboard relaunch. The relay needs root.';
 cardSizeSourceReady('inspect');
}
async function insp(){try{var d=await (await fetch('/api/v1/inspect')).json();inspApply(d);}catch(e){}}
function inspStart(){var hint=document.getElementById('insthint');
 fetch('/api/v1/inspect/start',{method:'POST'}).then(responseBody).then(function(result){inspApply(result.body);})
 .catch(function(error){if(hint)hint.textContent=error&&error.message?error.message:'Could not start the relay.';});}
function inspStop(){fetch('/api/v1/inspect/stop',{method:'POST'}).then(function(r){return r.json();}).then(inspApply).catch(function(){});}
insp();

var scheduleDashboardColumnAlignment=window.CardColumnAlignment
 ?window.CardColumnAlignment.attach('dashboard-cards')
 :function(){};
scheduleDashboardColumnAlignment();

// Build watch moved to the shared /assets/buildwatch.js (loaded by EVERY page, not just the dashboard).

// Controls card actions: POST /action a=<back|recents|launcher|admin_launcher|dashboard|reload|reboot>.
// Dashboard only foregrounds the effective renderer; Reload is the separate recovery action. Reboot confirms first.
function controlMessage(text){var zone=document.getElementById('ctlzone');if(!zone)return;
 var note=document.getElementById('ctlmsg');if(!note){note=document.createElement('p');note.id='ctlmsg';note.className='note';note.setAttribute('role','status');note.setAttribute('aria-live','polite');zone.appendChild(note);}note.textContent=text||'';}
function act(a){if(a==='reboot'){
 var warning='Reboot this panel now?';
 if(document.body.dataset.hardened==='1')warning+='\n\nHardened mode requires physical approval on this panel; it cannot be approved remotely.';
 if(!confirm(warning))return;
 }
 fetch('/api/v1/action',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'a='+a})
 .then(responseBody).then(function(result){
  if(!result.response.ok){
   if(result.body&&result.body.error==='remote-input-disabled')controlMessage('Remote tap input is disabled for network clients in Hardened mode.');
   else controlMessage(result.body.message||result.body.error||('Action failed (HTTP '+result.response.status+').'));
  }else controlMessage('');
 }).catch(function(error){controlMessage(error&&error.approvalRequired?error.message:'Action failed (network).');});}

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
function screenshotState(card){return card._screenshotState||(card._screenshotState={generation:0,tapping:false,fallbackUsed:false,objectUrl:null});}
function screenshotStatus(text,error){var status=document.getElementById('screenshot-dialog-status');if(!status)return;
 status.textContent=text||'';status.classList.toggle('error',!!error);}
function installScreenshotBlob(card,blob,generation,headers,seedCache){
 var state=screenshotState(card),sc=card.querySelector('.shot'),im=sc&&sc.querySelector('img');
 if(!im||generation!==state.generation)return Promise.resolve(false);
 var objectUrl=URL.createObjectURL(blob);
 return new Promise(function(resolve){var next=new Image();
  next.onload=function(){
   if(generation!==state.generation){URL.revokeObjectURL(objectUrl);resolve(false);return;}
   var previous=state.objectUrl;state.objectUrl=objectUrl;
   im.onload=function(){sc.classList.remove('failed');sc.classList.add('loaded');};
   im.onerror=function(){sc.classList.add('failed');};im.src=objectUrl;
   var dialogImage=document.getElementById('screenshot-dialog-image');
   if(dialogImage)dialogImage.src=objectUrl;
   var id=seedCache&&headers&&headers.get('X-ha-paneld-Screenshot-Id');
   if(id&&/^[0-9a-f]{64}$/.test(id)){var seed=new Image();seed.src='/api/v1/screenshot.png?cached='+id;}
   // Keep the displayed URL alive for modal reopen and slow target-image decode; release only the
   // previously displayed blob after this replacement has been predecoded successfully.
   if(previous)URL.revokeObjectURL(previous);resolve(true);
  };
  next.onerror=function(){URL.revokeObjectURL(objectUrl);resolve(false);};next.src=objectUrl;
 });
}
function refreshScreenshot(card){
 var sc=card&&card.querySelector('.shot'),im=sc&&sc.querySelector('img');if(!im||im.dataset.refreshing==='1')return;
 var state=screenshotState(card);if(state.tapping)return;
 var generation=++state.generation;im.dataset.refreshing='1';var url='/api/v1/screenshot.png?t='+Date.now();
 fetch(url,{cache:'no-store'}).then(function(r){if(!r.ok)throw new Error('capture failed');
  return r.blob().then(function(blob){return installScreenshotBlob(card,blob,generation,r.headers,true);});})
 .then(function(){if(generation===state.generation)im.dataset.refreshing='0';})
 .catch(function(){im.dataset.refreshing='0';});
}
function showAndRefreshScreenshot(card,cachedUrl){
 var im=card&&card.querySelector('img');if(!im)return;
 if(!im.getAttribute('src')&&cachedUrl)im.src=cachedUrl;
 card.style.display='';refreshScreenshot(card);scheduleDashboardColumnAlignment();
}

// The loaded screenshot opens in a native modal dialog. The original anchor remains the deliberately
// boring fallback for missing/broken images, modifier clicks and browsers without <dialog> support.
function screenshotLoaded(shot,im){return !!(shot&&im&&shot.classList.contains('loaded')&&im.complete&&im.naturalWidth&&im.naturalHeight);}
function screenshotTapPoint(ev,im){var r=im&&im.getBoundingClientRect(),nw=im&&im.naturalWidth,nh=im&&im.naturalHeight;
 if(!r||!nw||!nh||r.width<=0||r.height<=0)return null;
 var px=ev.clientX-r.left,py=ev.clientY-r.top;
 if(px<0||py<0||px>=r.width||py>=r.height)return null;
 return{x:Math.max(0,Math.min(nw-1,Math.floor(px*nw/r.width))),y:Math.max(0,Math.min(nh-1,Math.floor(py*nh/r.height)))};
}
function screenshotTrace(dialog,headers){
 [['inputId','X-ha-paneld-Input-Id'],['inputRoute','X-ha-paneld-Input-Route'],
  ['screenshotRoute','X-ha-paneld-Screenshot-Route'],['screenshotId','X-ha-paneld-Screenshot-Id']]
  .forEach(function(item){var value=headers.get(item[1]);if(value)dialog.dataset[item[0]]=value;});
}
function clearScreenshotTrace(dialog){['inputId','inputRoute','screenshotRoute','screenshotId'].forEach(function(key){delete dialog.dataset[key];});}
function fallbackScreenshot(card,dialog,generation,tapConfirmed){var state=screenshotState(card);if(state.fallbackUsed)return Promise.resolve(false);
 state.fallbackUsed=true;screenshotStatus(tapConfirmed?'Tap completed; capturing a fresh screenshot…':'Tap outcome unknown; capturing the current panel…',false);
 return new Promise(function(resolve){setTimeout(resolve,250);}).then(function(){
  return fetch('/api/v1/screenshot.png?t='+Date.now(),{cache:'no-store'});
 }).then(function(r){if(!r.ok)throw new Error('capture failed');screenshotTrace(dialog,r.headers);return r.blob().then(function(blob){
   return installScreenshotBlob(card,blob,generation,r.headers);});
 }).then(function(updated){screenshotStatus(updated?(tapConfirmed?'Screenshot updated.':'Tap outcome unknown; current screenshot refreshed.'):
  (tapConfirmed?'The tap completed, but the screenshot could not be updated.':'Tap outcome unknown; the screenshot could not be updated.'),!updated);return updated;})
 .catch(function(){screenshotStatus(tapConfirmed?'The tap completed, but the screenshot could not be updated.':'Tap outcome unknown; the screenshot could not be updated.',true);return false;});
}
function sendScreenshotTap(ev,card,dialog,image){
 if(!ev||ev.detail<=0||ev.button!==0||ev.ctrlKey||ev.metaKey||ev.shiftKey||ev.altKey)return;
 if(document.body.getAttribute('data-hardened')==='1')return;
 var point=screenshotTapPoint(ev,image);if(!point)return;ev.preventDefault();
 var state=screenshotState(card);if(state.tapping)return;
 state.tapping=true;state.fallbackUsed=false;var generation=++state.generation;
 clearScreenshotTrace(dialog);
 var cardImage=card.querySelector('.shot img');if(cardImage)cardImage.dataset.refreshing='0';
 image.classList.add('pending');screenshotStatus('Sending tap…',false);
 var controller=typeof AbortController==='function'?new AbortController():null;
 // The server owns the tap/capture completion deadline and response grace. Do not abandon a slow helper or
 // accessibility route early and race its eventual tap with the safe fallback screenshot.
 var timeout=controller&&setTimeout(function(){controller.abort();},65000);
 fetch('/api/v1/input',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
  body:'x='+point.x+'&y='+point.y+'&capture=1',signal:controller?controller.signal:undefined})
 .then(function(r){if(timeout)clearTimeout(timeout);screenshotTrace(dialog,r.headers);
  var route=r.headers.get('X-ha-paneld-Input-Route');
  if(!r.ok)return r.text().then(function(body){var e=new Error('tap request failed');e.status=r.status;e.tapConfirmed=!!route;
   try{e.code=JSON.parse(body).error;}catch(_ignored){}throw e;});
  var type=(r.headers.get('Content-Type')||'').toLowerCase();if(type.indexOf('image/png')<0){
   var bad=new Error('capture response was not a PNG');bad.tapConfirmed=true;throw bad;}
  return r.blob().then(function(blob){return installScreenshotBlob(card,blob,generation,r.headers);});
 }).then(function(updated){screenshotStatus(updated?'Screenshot updated.':'The tap completed, but the screenshot could not be updated.',!updated);
  if(!updated)return fallbackScreenshot(card,dialog,generation,true);return true;
 }).catch(function(error){if(timeout)clearTimeout(timeout);
  // A route header proves execution reached a tap route; completion-unknown and network/abort failures
  // are ambiguous. Queue expiry is explicitly not a tap and must not masquerade as success.
  if(error.tapConfirmed||error.code==='completion-unknown')return fallbackScreenshot(card,dialog,generation,!!error.tapConfirmed);
  if(error.status==null)return fallbackScreenshot(card,dialog,generation,false);
  screenshotStatus(error.status===403?'Remote input is disabled in Hardened mode.':'Tap was not accepted.',true);return false;
 }).then(function(){if(generation===state.generation){state.tapping=false;image.classList.remove('pending');}});
}
function createScreenshotDialog(card,shot){
 var dialog=document.createElement('dialog');dialog.id='screenshot-dialog';dialog.className='screenshot-dialog';
 var bar=document.createElement('div');bar.className='screenshot-dialog-bar';
 var title=document.createElement('h2');title.id='screenshot-dialog-title';title.textContent='Screenshot · live panel';bar.appendChild(title);
 var refresh=document.createElement('button');refresh.type='button';refresh.className='pbtn screenshot-dialog-refresh';refresh.textContent='Refresh';
 refresh.addEventListener('click',function(){if(dialog.classList.contains('view-only')){refreshScreenshot(card);return;}refreshScreenshot(card);screenshotStatus('Refreshing screenshot…',false);});
 bar.appendChild(refresh);
 var close=document.createElement('button');close.type='button';close.className='pbtn screenshot-dialog-close';close.textContent='Close';
 close.addEventListener('click',function(){dialog.close();});bar.appendChild(close);dialog.appendChild(bar);
 var frame=document.createElement('div');frame.className='screenshot-dialog-frame';
 var image=document.createElement('img');image.id='screenshot-dialog-image';image.alt='Live panel screenshot';image.draggable=false;
 image.addEventListener('click',function(ev){sendScreenshotTap(ev,card,dialog,image);});frame.appendChild(image);dialog.appendChild(frame);
 dialog.setAttribute('aria-labelledby',title.id);
 var status=document.createElement('p');status.id='screenshot-dialog-status';status.className='screenshot-dialog-status';
 status.setAttribute('role','status');status.setAttribute('aria-live','polite');dialog.appendChild(status);
 dialog.addEventListener('close',function(){shot.focus();});document.body.appendChild(dialog);card._screenshotDialog=dialog;return dialog;
}
function destroyScreenshotOverlay(card){
 var dialog=card&&card._screenshotDialog;
 if(dialog){if(dialog.open)dialog.close();dialog.remove();card._screenshotDialog=null;}
}
function setupScreenshotOverlay(){var card=document.getElementById('shotcard'),shot=card&&card.querySelector('.shot'),im=shot&&shot.querySelector('img');
 if(!card||!shot||!im||card._screenshotDialog||typeof HTMLDialogElement==='undefined'||typeof HTMLDialogElement.prototype.showModal!=='function')return;
 var dialog=createScreenshotDialog(card,shot);
 shot.addEventListener('click',function(ev){
  if(ev.button!==0||ev.ctrlKey||ev.metaKey||ev.shiftKey||ev.altKey||!screenshotLoaded(shot,im))return;
  ev.preventDefault();document.getElementById('screenshot-dialog-image').src=im.currentSrc||im.src;
  var hardened=document.body.getAttribute('data-hardened')==='1';dialog.classList.toggle('view-only',hardened);
  screenshotStatus(hardened?'View only — remote input is disabled in Hardened mode.':'Click the screenshot to tap the panel.',false);
  if(!dialog.open){dialog.showModal();dialog.querySelector('.screenshot-dialog-close').focus();}
 });
}
setupScreenshotOverlay();

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
   if(d.cards[id]&&d.cards[id].trim()){if(id==='shotcard')destroyScreenshotOverlay(card);el.innerHTML=d.cards[id];if(card)card.style.display='';}
   else if(card){card.style.display='none';}});          // probe says this card has nothing to show
  var cz=document.getElementById('ctlzone');if(cz&&d.controls){cz.innerHTML=d.controls;fitControls();}
  var sc=document.getElementById('shotcard');
  if(sc){if(d.shot){showAndRefreshScreenshot(sc,d.shotCached);}
   else{sc.style.display='none';}}
  setupScreenshotOverlay();scheduleDashboardColumnAlignment();
  cardSizeSourceReady('info');
 }
 function hydrate(tries){fetch('/api/v1/info').then(function(r){return r.json();}).then(apply)
  .catch(function(){if(tries>0)setTimeout(function(){hydrate(tries-1);},3000);});}
 hydrate(10);
})();
// A warm server render does not run hydration, so explicitly start its background refresh too. A
// cold shell may already show its disk-persisted placeholder, but hydration must confirm capture
// access before that shell starts a privileged request.
(function(){var sc=document.getElementById('shotcard');
 if(sc&&sc.style.display!=='none'&&sc.getAttribute('data-capture-ok')==='1')showAndRefreshScreenshot(sc,'');})();
// Dismiss a component-update banner for its current version (POST the label+version; the server keeps
// it hidden until a newer release ships — see Config.ignoreUpdate). Removes the banner on success.
function ignoreUpdate(btn){var b=btn.closest('.setup');if(!b)return;
 var label=b.getAttribute('data-update')||'',version=b.getAttribute('data-version')||'';
 btn.disabled=true;
 fetch('/api/v1/updates/ignore',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
  body:'label='+encodeURIComponent(label)+'&version='+encodeURIComponent(version)})
  .then(function(r){if(r.ok)b.remove();else btn.disabled=false;})
  .catch(function(){btn.disabled=false;});}
