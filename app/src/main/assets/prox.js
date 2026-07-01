// ha-paneld proximity tuning — extracted from info.js so the Configure page hosts the card
// without pulling in the dashboard's perf/inspect polling. No-op when the card isn't present.
if(document.getElementById('proxbox')){
var proxMax=0,proxDrag=false;  // gauge scale — GROW-ONLY (sticky) so it settles then stays put, not rescaled per poll
function proxDraw(d){
 var c=document.getElementById('proxgauge'),x=c.getContext('2d'),W=c.width,H=c.height;
 x.clearRect(0,0,W,H);
 var want=Math.max(d.max||0,d.nearRaw||0,d.farRaw||0,d.raw||0,1)*1.1;
 if(want>proxMax)proxMax=want;            // grow-only: settle to the real range, then hold (no per-poll jiggle)
 var gm=proxMax;
 function px(v){return Math.max(0,Math.min(W,(v/gm)*W));}
 if(d.calibrated&&d.threshold!=null){
  var tx=px(d.threshold);
  x.fillStyle='rgba(74,158,255,0.13)';
  if(d.nearBelow)x.fillRect(0,0,tx,H);else x.fillRect(tx,0,W-tx,H);
  if(d.margin){x.fillStyle='rgba(245,166,35,0.20)';x.fillRect(px(d.threshold-d.margin),0,px(d.threshold+d.margin)-px(d.threshold-d.margin),H);}
  x.strokeStyle='#f5a623';x.lineWidth=2;x.beginPath();x.moveTo(tx,0);x.lineTo(tx,H);x.stroke();
 }
 // End anchors so the track reads as panel→object: mdi:tablet-dashboard (the panel) at the NEAR end,
 // mdi:walk (a person) at the FAR end. Orientation follows nearBelow (which raw direction means "near").
 // MDI glyph paths (24x24 viewBox) rendered via Path2D — render everywhere, no font dependency. Faint,
 // so the live dot reads clearly over them.
 var nearLeft=d.nearBelow!==false,cy=H/2,S=20;
 var TAB='M19,18H5V6H19M21,4H3C1.89,4 1,4.89 1,6V18A2,2 0 0,0 3,20H21A2,2 0 0,0 23,18V6C23,4.89 22.1,4 21,4M7,8H13V13H7V8M14,8H17V10H14V8M17,11V16H14V11H17M7,14H13V16H7V14Z';
 var WALK='M14.12,10H19V8.2H15.38L13.38,4.87C13.08,4.37 12.54,4.03 11.92,4.03C11.74,4.03 11.58,4.06 11.42,4.11L6,5.8V11H7.8V7.33L9.91,6.67L6,22H7.8L10.67,13.89L13,17V22H14.8V15.59L12.31,11.05L13.04,8.18M14,3.8C15,3.8 15.8,3 15.8,2C15.8,1 15,0.2 14,0.2C13,0.2 12.2,1 12.2,2C12.2,3 13,3.8 14,3.8Z';
 function mdi(dd,cx){x.save();x.translate(cx-S/2,cy-S/2);x.scale(S/24,S/24);x.fill(new Path2D(dd));x.restore();}
 x.save();x.globalAlpha=0.6;x.fillStyle='#aab6c2';
 mdi(nearLeft?TAB:WALK,14);mdi(nearLeft?WALK:TAB,W-14);
 x.restore();
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
 else if(!d.graded)hint.textContent='Binary sensor — near/far is reported directly; no tuning needed.';
 else hint.textContent=d.calibrated?'':'Hold your hand at the panel and press Capture near, then move away and press Capture far.';
}
async function prox(){
 if(document.hidden)return;   // pause 400ms polling when the tab isn't visible
 try{
  var d=await (await fetch('/api/v1/proximity')).json();
  var box=document.getElementById('proxbox'),st=document.getElementById('proxstate');
  if(!d.present){box.style.display='none';st.textContent='· not present';return;}
  box.style.display='block';
  document.getElementById('proxraw').textContent=d.raw==null?'–':d.raw.toFixed(1);
  var nb=document.getElementById('proxnear');nb.textContent=d.near?'NEAR':'FAR';nb.style.color=d.near?'#48c774':'#888';
  proxApply(d);   // draw first so proxMax (grow-only scale) is settled before we read it for the slider
  if(!proxDrag){var s=document.getElementById('proxslider');var sm=proxMax.toFixed(1);if(s.max!==sm)s.max=sm;if(d.threshold!=null)s.value=d.threshold;}
 }catch(e){}
}
function proxPost(u,b){fetch(u,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b}).then(function(r){return r.json();}).then(proxApply).catch(function(){});}
function proxCap(s){proxPost('/api/v1/proximity/capture','step='+s);}
function proxSen(s){proxPost('/api/v1/proximity/sensitivity','s='+s);}
function proxReset(){proxPost('/api/v1/proximity/reset','');}
function proxThSet(v){proxDrag=false;proxPost('/api/v1/proximity/threshold','v='+v);}
(function(){var s=document.getElementById('proxslider');s.addEventListener('mousedown',function(){proxDrag=true;});s.addEventListener('touchstart',function(){proxDrag=true;});})();
prox();setInterval(prox,400);
}
