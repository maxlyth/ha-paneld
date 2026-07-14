// Automatic entity-learning diagnostics. Same-origin/LAN guards are enforced server-side.
(function(){
  'use strict';
  var status=document.getElementById('entity-status'),search=document.getElementById('entity-search'),timer,pageSize=100;
  function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
  function age(ts){if(!ts)return 'never';var s=Math.max(0,Math.floor((Date.now()-ts)/1000));if(s<60)return s+'s ago';if(s<3600)return Math.floor(s/60)+'m ago';if(s<86400)return Math.floor(s/3600)+'h ago';return Math.floor(s/86400)+'d ago'}
  function rate(n){n=Number(n)||0;if(n<1024)return n.toFixed(n<10?1:0)+' B/s';if(n<1048576)return (n/1024).toFixed(1)+' KiB/s';return (n/1048576).toFixed(1)+' MiB/s'}
  async function loadStatus(){
    try{var s=await fetch('/api/v1/dashboard/entities/sync',{cache:'no-store'}).then(function(r){return r.json()});
      status.innerHTML='<b>'+esc(s.sync_running?'synchronizing':s.state)+'</b> · '+s.candidate_count+' candidates · '+s.stream_entity_count+' entities in '+esc(s.stream_mode)+' stream / '+s.catalog_count+' catalogued · last sync '+age(s.last_sync_at)+(s.unresolved_count?' · <span class="hot">'+s.unresolved_count+' unresolved</span>':'')+(s.error?' · <span class="hot">'+esc(s.error)+'</span>':'')+' · DB '+Math.round((s.db_bytes||0)/1024)+' KiB';
    }catch(e){status.textContent='Status unavailable'}
  }
  function value(e,key){if(key==='override')return e.excluded?'excluded':e.pinned?'pinned':'auto';if(key==='reasons')return e.reasons||'';return e[key]==null?'':e[key]}
  function controller(card){
    var body=card.querySelector('tbody'),msg=card.querySelector('.entity-msg'),prev=card.querySelector('.entity-prev'),next=card.querySelector('.entity-next');
    var state={items:[],total:0,offset:0,sortKey:'entity_id',sortDir:1,busy:false};
    function render(){
      var items=state.items.slice().sort(function(a,b){var x=value(a,state.sortKey),y=value(b,state.sortKey);if(typeof x==='number'||typeof y==='number')return (Number(x)-Number(y))*state.sortDir;return String(x).localeCompare(String(y))*state.sortDir});body.innerHTML='';
      items.forEach(function(e){var tr=document.createElement('tr'),reason=e.reasons||((e.static?'dashboard ':'')+(e.runtime?'runtime':''));
        tr.innerHTML='<td><code>'+esc(e.entity_id)+'</code></td><td title="1 minute / 1 hour / 1 day">'+e.access_1m+' / '+e.access_1h+' / '+e.access_1d+'</td><td class="'+(e.rate_1h_bps?'hot':'')+'" title="1 minute / 1 hour / 1 day">'+rate(e.rate_1m_bps)+' / '+rate(e.rate_1h_bps)+' / '+rate(e.rate_1d_bps)+'</td><td>'+esc(reason)+'</td><td>'+age(e.last_access)+'</td><td><select data-id="'+esc(e.entity_id)+'"><option value="auto">Auto</option><option value="pinned"'+(e.pinned?' selected':'')+'>Pinned</option><option value="forced_exclude"'+(e.excluded?' selected':'')+'>Excluded</option></select></td>';body.appendChild(tr)});
      card.querySelectorAll('th button').forEach(function(b){b.classList.toggle('sorted',b.dataset.sort===state.sortKey);b.dataset.dir=b.dataset.sort===state.sortKey?(state.sortDir>0?'asc':'desc'):''});
      var start=state.total?state.offset+1:0,end=Math.min(state.offset+state.items.length,state.total);msg.textContent='Showing '+start+'–'+end+' of '+state.total;
      prev.disabled=state.offset===0;next.disabled=state.offset+state.items.length>=state.total;
    }
    async function load(){if(state.busy)return;state.busy=true;try{var u='/api/v1/dashboard/entities?limit='+pageSize+'&offset='+state.offset+'&filter='+encodeURIComponent(card.dataset.filter)+'&q='+encodeURIComponent(search.value.trim());var d=await fetch(u,{cache:'no-store'}).then(function(r){return r.json()});state.items=d.items||[];state.total=Number(d.total)||0;render()}catch(e){msg.textContent='Entity list unavailable'}finally{state.busy=false}}
    body.addEventListener('change',async function(ev){var sel=ev.target;if(!sel.matches('select[data-id]'))return;var id=sel.getAttribute('data-id'),choice=sel.value,force=choice==='forced_exclude'&&confirm('Exclude '+id+' even if the dashboard depends on it?');if(choice==='forced_exclude'&&!force){loadAll();return}var r=await fetch('/api/v1/dashboard/entities/override',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({entity_id:id,override:choice,force:force})});if(!r.ok)alert(await r.text());await Promise.all([loadStatus(),loadAll()])});
    card.querySelector('thead').addEventListener('click',function(ev){var b=ev.target.closest('button[data-sort]');if(!b)return;var key=b.dataset.sort;if(state.sortKey===key)state.sortDir=-state.sortDir;else{state.sortKey=key;state.sortDir=(key==='entity_id'||key==='reasons'||key==='override')?1:-1}render()});
    prev.addEventListener('click',function(){state.offset=Math.max(0,state.offset-pageSize);load()});next.addEventListener('click',function(){state.offset+=pageSize;load()});
    return {load:load,reset:function(){state.offset=0;return load()}};
  }
  var tables=Array.prototype.map.call(document.querySelectorAll('.entity-list'),controller);
  function loadAll(){return Promise.all(tables.map(function(t){return t.load()}))}
  function resetAll(){return Promise.all(tables.map(function(t){return t.reset()}))}
  document.getElementById('entity-sync').addEventListener('click',async function(){this.disabled=true;status.textContent='Synchronization started…';var r=await fetch('/api/v1/dashboard/entities/sync',{method:'POST'});if(!r.ok&&r.status!==409)status.textContent=await r.text();this.disabled=false;loadStatus()});
  document.getElementById('entity-activate').addEventListener('click',async function(){if(!confirm('Replace the current entity filter with the learned candidate set? Keep the dashboard visible so you can restore manual mode if a dynamic dependency is missing.'))return;this.disabled=true;var r=await fetch('/api/v1/dashboard/entities/activate',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"confirm":true}'});if(!r.ok)alert(await r.text());this.disabled=false;loadStatus();resetAll()});
  search.addEventListener('input',function(){clearTimeout(timer);timer=setTimeout(resetAll,250)});
  loadStatus();loadAll();setInterval(loadStatus,5000);setInterval(function(){if(!document.hidden&&!document.querySelector('.entity-list select:focus'))loadAll()},10000);
})();
