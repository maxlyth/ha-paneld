        (()=>{
          if(window.top&&window.top!==window)return;
          if(window.__haPaneldEntityLearning)return;
          window.__haPaneldEntityLearning=true;
          const wrapped=new WeakSet(),id=/^[a-z0-9_]+\.[a-z0-9_]+$/;
          const seen=new Map(),missing=new Set(),metrics=new Map(); let enumerating=false;
          const maxBridgeChars=1000000,maxFrameChars=786432,
            maxIdChars=255,maxAccessIds=1024,
            maxMissingIds=1024,maxMetricIds=2048,maxCount=50000000;
          const sat=(left,right)=>Math.min(maxCount,left+Math.max(0,right));
          function measuredParse(value){return JSON.parse(value)}
function measuredStringify(value){const encoded=JSON.stringify(value);return typeof encoded==='string'?encoded:''}
          function validId(value){return typeof value==='string'&&value.length<=maxIdChars&&id.test(value)}
          function recordAccess(entityId,present){
            if(present){const count=seen.get(entityId);if(count!==undefined){seen.set(entityId,sat(count,1)); return}
              if(seen.size>=maxAccessIds){ return}seen.set(entityId,1);return}
            if(missing.has(entityId)){ return}
            if(missing.size>=maxMissingIds){ return}missing.add(entityId)
          }
          function recordMetric(entityId,value){
            if(!validId(entityId))return;
            const old=metrics.get(entityId);if(old){old[0]=sat(old[0],1);old[1]=sat(old[1],measuredStringify(value).length); return}
            if(metrics.size>=maxMetricIds){ return}
            metrics.set(entityId,[1,measuredStringify(value).length]);
          }
          function flush(){
            if(!seen.size&&!missing.size)return;
            const accessed={};seen.forEach((count,entityId)=>accessed[entityId]=count);
            const payload=JSON.stringify({accessed:accessed,missing:Array.from(missing)}),discarded=seen.size+missing.size;
            seen.clear();missing.clear();
            if(payload.length<maxBridgeChars){try{window.haPaneldV2&&window.haPaneldV2.postMessage(JSON.stringify({type:'entityLearningAccesses',payload:JSON.parse(payload)}))}catch(e){}}
            // Cost tracking disabled.
          }
          setInterval(flush,2000);
          setInterval(()=>{
            if(!metrics.size)return;
            const out={},discarded=metrics.size;metrics.forEach((v,k)=>out[k]=v);metrics.clear();
            // Cost envelope disabled.
            const payload=JSON.stringify(out);
            // Cost counters disabled.
            if(payload.length<maxBridgeChars){try{window.haPaneldV2&&window.haPaneldV2.postMessage(JSON.stringify({type:'entityLearningMetrics',payload:JSON.parse(payload)}))}catch(e){}}
            // Cost drop tracking disabled.
          },5000);
          const Parent=window.WebSocket,targetWsOrigins=["ws://ha.example","wss://ha.example"],targetWsPath="/api/websocket";
          function LearningWebSocket(url,protocols){
            const socket=protocols===undefined?new Parent(url):new Parent(url,protocols);
            try{const u=new URL(String(url),location.href);if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
              let hydrated=false,entitySubscriptionId=null;const send=socket.send;
              Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
                if(typeof data==='string'){if(data.length>maxFrameChars){}else try{const message=measuredParse(data);if(message&&message.type==='subscribe_entities')entitySubscriptionId=message.id}catch(e){}}
                return send.call(this,data)
              }});
              socket.addEventListener('message',ev=>{if(typeof ev.data!=='string')return;
                if(ev.data.length>maxFrameChars){ return}try{
                const decoded=measuredParse(ev.data);
                const messages=Array.isArray(decoded)?decoded:[decoded];messages.forEach(m=>{
                if(m.id!==entitySubscriptionId)return;const event=m&&m.type==='event'&&m.event;if(!event)return;
                if(event.a&&!hydrated){hydrated=true;for(const k in event.a)if(Object.prototype.hasOwnProperty.call(event.a,k)){ recordMetric(k,event.a[k])}return}const changed=event.c||{};
                for(const k in changed)if(Object.prototype.hasOwnProperty.call(changed,k)){ recordMetric(k,changed[k])}})
              }catch(e){}})
            }}catch(e){}return socket
          }
          Object.setPrototypeOf(LearningWebSocket,Parent);LearningWebSocket.prototype=Parent.prototype;
          for(const k of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(LearningWebSocket,k,{value:Parent[k]});
          window.WebSocket=LearningWebSocket;
          setInterval(()=>{
            try{
              const root=document.querySelector('home-assistant'),h=root&&root.hass,s=h&&h.states;
              if(!root||!h||!s||wrapped.has(s))return;
              const proxy=new Proxy(s,{
                ownKeys(t){enumerating=true;queueMicrotask(()=>enumerating=false);return Reflect.ownKeys(t)},
                get(t,p,r){
                  if(validId(p)&&!enumerating){
                    recordAccess(p,Reflect.has(t,p));
                  }
                  return Reflect.get(t,p,r)
                },
                has(t,p){
                  if(validId(p)&&!enumerating){
                    recordAccess(p,Reflect.has(t,p));
                  }
                  return Reflect.has(t,p)
                }
              });
              wrapped.add(proxy); root.hass=Object.assign({},h,{states:proxy});
            }catch(e){}
          },1000);
        })();
