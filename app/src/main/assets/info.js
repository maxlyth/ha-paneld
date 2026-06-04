// ha-paneld info page — live perf/responsiveness/proximity polling.
// Served as a static asset (linted by CI: `node --check`) rather than embedded in a Kotlin
// string, so syntax errors (e.g. an apostrophe in a single-quoted string) are caught at build.
var cpuH=[],ramH=[],gpuH=[],MAX=120;  // ~4 min at 2s
function row(k,v){return '<tr><th>'+k+'</th><td>'+v+'</td></tr>';}
function draw(){
 var c=document.getElementById('perfchart'),x=c.getContext('2d'),W=c.width,H=c.height;
 x.clearRect(0,0,W,H);
 x.lineWidth=1;x.font='11px system-ui,sans-serif';x.textBaseline='middle';
 [25,50,75].forEach(function(p){var y=H-p/100*H;
  x.strokeStyle='#383838';x.beginPath();x.moveTo(0,y);x.lineTo(W,y);x.stroke();
  x.fillStyle='#181818';x.fillRect(0,y-7,26,14);x.fillStyle='#888';x.fillText(p+'%',2,y);});
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
 [[50,'#3a5a42'],[85,'#6a5526']].forEach(function(t){var y=H-(t[0]/100)*H;x.strokeStyle=t[1];x.beginPath();x.moveTo(0,y);x.lineTo(W,y);x.stroke();x.fillStyle='#181818';x.fillRect(0,y-7,26,14);x.fillStyle='#888';x.fillText(t[0]+'%',2,y);});
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
  var d=await (await fetch('/perf')).json();
  setInstr(d.enabled!==false);
  if(d.enabled===false){var off='<tr><td style="color:#888">instrumentation off — turn it on to measure</td></tr>';
   document.getElementById('perf').innerHTML=off;document.getElementById('topproc').innerHTML=off;
   var smt0=document.getElementById('smtbl');if(smt0)smt0.innerHTML=off;
   document.getElementById('perfage').textContent='· off';return;}
  if(d.hist){cpuH=d.hist.cpu||[];ramH=d.hist.ram||[];gpuH=d.hist.gpu||[];}  // server FIFO
  draw();
  var ramPct=d.memTotalMb?Math.round(d.memUsedMb*100/d.memTotalMb):0;
  var peak=(d.cores&&d.cores.length)?Math.max.apply(null,d.cores):d.cpu;
  var h=row('CPU',d.cpu+'%  <span style="color:#8a8">peak core '+peak+'%</span>');
  if(d.freqMhz&&d.freqMhz.length){var cur=Math.max.apply(null,d.freqMhz),mx=d.freqMaxMhz||0;
   h+=row('CPU clock',(cur/1000).toFixed(2)+' GHz'+(mx?'  <span style="color:#8a8">/ '+(mx/1000).toFixed(2)+' GHz max</span>':''));}
  if(d.gpu!=null)h+=row('GPU',d.gpu+'%'+(d.gpuMhz?'  <span style="color:#8a8">'+d.gpuMhz+' MHz</span>':''));
  h+=row('RAM',d.memUsedMb+' / '+d.memTotalMb+' MB ('+ramPct+'%)');
  if(d.load&&d.load.length)h+=row('Load avg',d.load.join('  '));
  if(d.tempC!=null)h+=row('Temperature',d.tempC.toFixed(1)+' °C');
  document.getElementById('perf').innerHTML=h;
  var tp=document.getElementById('topproc');
  if(d.top&&d.top.length){
   var t='<tr><th>Process</th><th class="num">% CPU</th></tr>';
   d.top.forEach(function(p){t+='<tr><td>'+p.name+'</td><td class="num">'+p.cpu+'%</td></tr>';});
   tp.innerHTML=t;
  }else tp.innerHTML='<tr><td style="color:#888">needs root (su)</td></tr>';
  var r=d.render,smh=document.getElementById('smhdr'),smt=document.getElementById('smtbl');
  if(r==null){smh.textContent='· needs root';smt.innerHTML=row('Responsiveness','<span style="color:#888">needs root to measure</span>');drawSm([]);}
  else if(r.status==='no-renderer'){smh.textContent='· waiting';drawSm(r.hist||[]);smt.innerHTML=row('Responsiveness','<span style="color:#888">no dashboard WebView detected yet</span>');}
  else{
   drawSm(r.hist);
   var col=r.verdict==='smooth'?'#48c774':(r.verdict==='occasional'?'#d9a528':'#d04a3b');
   var v=r.verdict==='smooth'?'Snappy':(r.verdict==='occasional'?'Sluggish':'Laggy');
   smh.textContent='· '+r.pkg.split('.').pop();
   var h=row('How it feels','<span style="color:'+col+'">●</span> <b>'+v+'</b>')
    +row('Dashboard main-thread','<b>'+r.mainPct+'%</b> of one core <span style="color:#888">(100% = event processing maxed out)</span>');
   if(r.jankPct!=null)h+=row('Rendering load',r.jankPct+'% janky <span style="color:#888">· only counts when actively drawing (e.g. video) — worst frame '+r.p99+' ms</span>');
   smt.innerHTML=h;
  }
  document.getElementById('perfage').textContent='· live';
 }catch(e){document.getElementById('perfage').textContent='· unavailable';}
}
perf();setInterval(perf,2000);
function setInstr(on){var a=document.getElementById('instron'),b=document.getElementById('instroff');if(a)a.className='pbtn'+(on?' on':'');if(b)b.className='pbtn'+(on?'':' on');}
function instr(on){fetch('/instrumentation',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'enabled='+on}).then(function(r){return r.json();}).then(function(){perf();}).catch(function(){});}
var proxMax=100,proxDrag=false;
function proxDraw(d){
 var c=document.getElementById('proxgauge'),x=c.getContext('2d'),W=c.width,H=c.height;
 x.clearRect(0,0,W,H);
 var gm=Math.max(d.max||0,d.nearRaw||0,d.farRaw||0,d.raw||0,1)*1.1;proxMax=gm;
 function px(v){return Math.max(0,Math.min(W,(v/gm)*W));}
 if(d.calibrated&&d.threshold!=null){
  var tx=px(d.threshold);
  x.fillStyle='rgba(74,158,255,0.13)';
  if(d.nearBelow)x.fillRect(0,0,tx,H);else x.fillRect(tx,0,W-tx,H);
  if(d.margin){x.fillStyle='rgba(245,166,35,0.20)';x.fillRect(px(d.threshold-d.margin),0,px(d.threshold+d.margin)-px(d.threshold-d.margin),H);}
  x.strokeStyle='#f5a623';x.lineWidth=2;x.beginPath();x.moveTo(tx,0);x.lineTo(tx,H);x.stroke();
 }
 if(d.raw!=null){var rx=px(d.raw);x.fillStyle=d.near?'#48c774':'#888';x.beginPath();x.arc(rx,H/2,7,0,7);x.fill();}
}
function proxApply(d){
 document.getElementById('proxbox').className=d.graded?'':'binary'; // hide gauge/slider/sensitivity on binary sensors
 proxDraw(d);
 document.getElementById('proxth').textContent=d.threshold==null?'–':d.threshold.toFixed(1);
 document.getElementById('proxstate').textContent=(d.graded?'· graded sensor':'· binary sensor')+(d.calibrated?' · calibrated':'');
 document.querySelectorAll('.psen').forEach(function(b){b.className='pbtn psen gradedonly'+(b.dataset.s===d.sensitivity?' on':'');});
 var hint=document.getElementById('proxhint');
 if(d.indistinct)hint.textContent='⚠ near and far captures are too close — recapture with a clearer gap.';
 else if(!d.graded)hint.textContent='Binary near/far sensor — nothing to tune. Press Capture near with your hand at the panel, then Capture far, to set which reading means "near".';
 else hint.textContent=d.calibrated?'':'Hold your hand at the panel and press Capture near, then move away and press Capture far.';
}
async function prox(){
 if(document.hidden)return;   // pause 400ms polling when the tab isn't visible
 try{
  var d=await (await fetch('/proximity')).json();
  var box=document.getElementById('proxbox'),st=document.getElementById('proxstate');
  if(!d.present){box.style.display='none';st.textContent='· not present';return;}
  box.style.display='block';
  document.getElementById('proxraw').textContent=d.raw==null?'–':d.raw.toFixed(1);
  var nb=document.getElementById('proxnear');nb.textContent=d.near?'NEAR':'FAR';nb.style.color=d.near?'#48c774':'#888';
  if(!proxDrag){var s=document.getElementById('proxslider');s.max=proxMax.toFixed(1);if(d.threshold!=null)s.value=d.threshold;}
  proxApply(d);
 }catch(e){}
}
function proxPost(u,b){fetch(u,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b}).then(function(r){return r.json();}).then(proxApply).catch(function(){});}
function proxCap(s){proxPost('/proximity/capture','step='+s);}
function proxSen(s){proxPost('/proximity/sensitivity','s='+s);}
function proxReset(){proxPost('/proximity/reset','');}
function proxThSet(v){proxDrag=false;proxPost('/proximity/threshold','v='+v);}
(function(){var s=document.getElementById('proxslider');s.addEventListener('mousedown',function(){proxDrag=true;});s.addEventListener('touchstart',function(){proxDrag=true;});})();
prox();setInterval(prox,400);
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
async function insp(){try{var d=await (await fetch('/inspect')).json();inspApply(d);}catch(e){}}
function inspStart(){fetch('/inspect/start',{method:'POST'}).then(function(r){return r.json();}).then(inspApply).catch(function(){});}
function inspStop(){fetch('/inspect/stop',{method:'POST'}).then(function(r){return r.json();}).then(inspApply).catch(function(){});}
insp();
// Masonry: distribute .card elements into N flex columns (N = width / ~418), shortest-column-first.
// Re-distributes only when the column COUNT changes (between breakpoints the flex columns just widen).
var _mN=-1;
function masonry(){
 var c=document.querySelector('.cards'); if(!c)return;
 var n=Math.max(1,Math.floor((c.clientWidth+18)/(400+18)));
 if(n===_mN)return; _mN=n;
 var cards=[].slice.call(c.querySelectorAll('.card'));
 cards.forEach(function(k){k.parentNode.removeChild(k);});
 [].slice.call(c.querySelectorAll('.col')).forEach(function(x){c.removeChild(x);});
 var cols=[];
 for(var i=0;i<n;i++){var d=document.createElement('div');d.className='col';c.appendChild(d);cols.push(d);}
 cards.forEach(function(k){var m=0;for(var i=1;i<n;i++){if(cols[i].offsetHeight<cols[m].offsetHeight)m=i;}cols[m].appendChild(k);});
}
window.addEventListener('resize',function(){clearTimeout(window._mt);window._mt=setTimeout(masonry,120);});
masonry();
