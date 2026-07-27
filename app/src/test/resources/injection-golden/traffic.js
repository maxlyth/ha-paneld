(()=>{
  if(window.top&&window.top!==window)return;
  if(window.__haPaneldEntityTraffic)return;
  window.__haPaneldEntityTraffic=true;
  const Parent=window.WebSocket,targetWsOrigins=["ws://ha.example","wss://ha.example"],targetWsPath="/api/websocket",
    maxFrameChars=1000000,maxCount=50000000,maxChars=1000000000,
    maxMicros=300000000,sampleMs=5000;
  if(!Parent)return;
  const now=()=>window.performance&&typeof window.performance.now==='function'?window.performance.now():0;
  const sat=(left,right,limit)=>Math.min(limit,left+Math.max(0,right));
  const interactionUpper=[16,50,100,200,300,500,1000,2000,5000,Infinity];
  let frames=0,payloadBytes=0,entityUpdates=0,hydrationUpdates=0,observerMs=0,droppedFrames=0,
    stateTaskMs=0,stateTaskMaxMs=0,interactionBins=interactionUpper.map(()=>0),
    interactionMaxMs=0,inputDelayMs=0,interactionProcessingMs=0,presentationMs=0,
    loafCount=0,blockingMs=0,loafMaxMs=0,scriptMs=0,renderMs=0,longTaskCount=0,lastFlush=now();
  const interactionPending=new Map();
  const pendingStateTasks=[],taskChannel=typeof MessageChannel==='function'?new MessageChannel():null;
  let fallbackTaskPending=false;
  const perfObservers=[];window.__haPaneldPerformanceObservers=perfObservers;
  if(taskChannel)taskChannel.port1.onmessage=()=>{
    const started=pendingStateTasks.shift();if(started===undefined)return;
    const elapsed=Math.max(0,now()-started);stateTaskMs=sat(stateTaskMs,elapsed,maxMicros/1000);
    stateTaskMaxMs=Math.max(stateTaskMaxMs,elapsed);
  };
  function utf8Bytes(value){
    let bytes=0;for(let i=0;i<value.length;i++){const code=value.charCodeAt(i);
      if(code<0x80)bytes++;else if(code<0x800)bytes+=2;
      else if(code>=0xd800&&code<=0xdbff&&i+1<value.length&&value.charCodeAt(i+1)>=0xdc00&&value.charCodeAt(i+1)<=0xdfff){bytes+=4;i++}
      else bytes+=3}return bytes;
  }
  function countOwn(value){
    let count=0;if(!value||typeof value!=='object')return count;
    for(const key in value)if(Object.prototype.hasOwnProperty.call(value,key))count=sat(count,1,maxCount);
    return count;
  }
  function inspect(message,subscriptionId){
    const effectiveId=subscriptionId===null||subscriptionId===undefined?window.__haPaneldEntitySubscriptionId:subscriptionId;
    if(!message||message.id!==effectiveId||message.type!=='event'||!message.event)return 0;
    const added=countOwn(message.event.a),changed=countOwn(message.event.c),removed=countOwn(message.event.r);
    if(added&&!window.__haPaneldEntityHydrated){window.__haPaneldEntityHydrated=true;hydrationUpdates=sat(hydrationUpdates,added,maxCount)}
    const total=sat(sat(added,changed,maxCount),removed,maxCount);entityUpdates=sat(entityUpdates,total,maxCount);return total;
  }
  try{
    if(typeof PerformanceObserver==='function'&&PerformanceObserver.supportedEntryTypes&&PerformanceObserver.supportedEntryTypes.includes('event')){
      const eventObserver=new PerformanceObserver(list=>{for(const entry of list.getEntries()){
        if(!entry.interactionId||entry.duration<=0)continue;
        const d=Math.max(0,entry.duration),input=Math.max(0,entry.processingStart-entry.startTime),
          processing=Math.max(0,entry.processingEnd-entry.processingStart),
          presentation=Math.max(0,entry.startTime+d-entry.processingEnd);
        const previous=interactionPending.get(entry.interactionId);
        if((!previous||d>previous[0])&&(previous||interactionPending.size<128)){
          interactionPending.set(entry.interactionId,[d,input,processing,presentation]);
        }
      }});eventObserver.observe({type:'event',buffered:true,durationThreshold:16});perfObservers.push(eventObserver);
    }
    const supported=typeof PerformanceObserver==='function'&&PerformanceObserver.supportedEntryTypes||[];
    if(supported.includes('long-animation-frame')){
      const loafObserver=new PerformanceObserver(list=>{for(const entry of list.getEntries()){
        const scripts=entry.scripts||[];let script=0;for(const item of scripts)script+=Math.max(0,item.duration||0);
        const duration=Math.max(0,entry.duration||0),blocking=Math.max(0,entry.blockingDuration||0),
          render=entry.renderStart>0?Math.max(0,entry.startTime+duration-entry.renderStart):Math.max(0,duration-script);
        loafCount=sat(loafCount,1,maxCount);blockingMs=sat(blockingMs,blocking,maxMicros/1000);
        loafMaxMs=Math.max(loafMaxMs,duration);scriptMs=sat(scriptMs,script,maxMicros/1000);
        renderMs=sat(renderMs,render,maxMicros/1000);
      }});loafObserver.observe({type:'long-animation-frame',buffered:true});perfObservers.push(loafObserver);
    }else if(supported.includes('longtask')){
      const taskObserver=new PerformanceObserver(list=>{for(const entry of list.getEntries()){
        longTaskCount=sat(longTaskCount,1,maxCount);const d=Math.max(0,entry.duration||0);
        blockingMs=sat(blockingMs,Math.max(0,d-50),maxMicros/1000);loafMaxMs=Math.max(loafMaxMs,d);
      }});taskObserver.observe({type:'longtask',buffered:true});perfObservers.push(taskObserver);
    }
  }catch(e){}
  function TrafficWebSocket(url,protocols){
    const socket=protocols===undefined?new Parent(url):new Parent(url,protocols);
    try{
      const u=new URL(String(url),location.href);
      if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
        let entitySubscriptionId=null;
        const send=socket.send;
        Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
          const started=now();
          try{
            if(typeof data==='string'&&data.length<=maxFrameChars){
              try{const message=JSON.parse(data);if(message&&message.type==='subscribe_entities')entitySubscriptionId=message.id}catch(e){}
            }
          }finally{observerMs=sat(observerMs,Math.max(0,now()-started),maxMicros/1000)}
          return send.call(this,data);
        }});
        socket.addEventListener('message',event=>{
          const started=now();
          try{
            if(typeof event.data!=='string')return;
            if(event.data.length>maxFrameChars){droppedFrames=sat(droppedFrames,1,maxCount);return}
            try{
              const decoded=JSON.parse(event.data);
              let updates=0;if(Array.isArray(decoded)){for(let i=0;i<decoded.length;i++)updates=sat(updates,inspect(decoded[i],entitySubscriptionId),maxCount)}
              else updates=inspect(decoded,entitySubscriptionId);
              if(updates){frames=sat(frames,1,maxCount);payloadBytes=sat(payloadBytes,utf8Bytes(event.data),maxChars);
                if(taskChannel&&!pendingStateTasks.length){pendingStateTasks.push(started);taskChannel.port2.postMessage(0)}
                else if(!taskChannel&&!fallbackTaskPending){fallbackTaskPending=true;setTimeout(()=>{fallbackTaskPending=false;const elapsed=Math.max(0,now()-started);stateTaskMs=sat(stateTaskMs,elapsed,maxMicros/1000);stateTaskMaxMs=Math.max(stateTaskMaxMs,elapsed)},0)}
              }
            }catch(e){droppedFrames=sat(droppedFrames,1,maxCount)}
          }finally{observerMs=sat(observerMs,Math.max(0,now()-started),maxMicros/1000)}
        });
      }
    }catch(e){}
    return socket;
  }
  Object.setPrototypeOf(TrafficWebSocket,Parent);TrafficWebSocket.prototype=Parent.prototype;
  for(const key of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(TrafficWebSocket,key,{value:Parent[key]});
  window.WebSocket=TrafficWebSocket;
  setInterval(()=>{
    const current=now(),elapsed=Math.max(0,current-lastFlush);lastFlush=current;
    interactionPending.forEach(value=>{
      const d=value[0],bin=interactionUpper.findIndex(limit=>d<=limit),index=bin<0?interactionBins.length-1:bin;
      interactionBins[index]=sat(interactionBins[index],1,maxCount);
      if(d>interactionMaxMs){interactionMaxMs=d;inputDelayMs=value[1];interactionProcessingMs=value[2];presentationMs=value[3]}
    });interactionPending.clear();
    const payload=[Math.min(maxCount,Math.round(elapsed)),frames,payloadBytes,entityUpdates,hydrationUpdates,
      Math.min(maxMicros,Math.round(observerMs*1000)),droppedFrames,
      Math.min(maxMicros,Math.round(stateTaskMs*1000)),Math.min(maxMicros,Math.round(stateTaskMaxMs*1000))]
      .concat(interactionBins)
      .concat([Math.round(interactionMaxMs*1000),Math.round(inputDelayMs*1000),
        Math.round(interactionProcessingMs*1000),Math.round(presentationMs*1000),loafCount,
        Math.round(blockingMs*1000),Math.round(loafMaxMs*1000),Math.round(scriptMs*1000),
        Math.round(renderMs*1000),longTaskCount]).join(',');
    frames=payloadBytes=entityUpdates=hydrationUpdates=observerMs=droppedFrames=stateTaskMs=stateTaskMaxMs=
      interactionMaxMs=inputDelayMs=interactionProcessingMs=presentationMs=loafCount=blockingMs=
      loafMaxMs=scriptMs=renderMs=longTaskCount=0;interactionBins.fill(0);
    try{if(window.haPaneldV2&&typeof window.haPaneldV2.postMessage==='function')window.haPaneldV2.postMessage(JSON.stringify({type:'entityFilterTrafficMetrics',payload:payload}))}catch(e){}
  },sampleMs);
})();
