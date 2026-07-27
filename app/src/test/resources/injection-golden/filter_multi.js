(()=>{
  if(window.top&&window.top!==window)return;
  const Native=window.WebSocket;
  if(!Native||Native.__haPaneldEntityFilter)return;
  const targetWsOrigins=["ws://ha.example","wss://ha.example"],targetWsPath="/api/websocket",entityIds=["light.alpha"],emptySubscriptionEntityId="ha_paneld_internal.empty_subscription_5f39d48b7a6c4e2a",filterKeys=["entity_ids","exclude","exclude_domains","exclude_entities","exclude_entity_globs","include","include_domains","include_entities","include_entity_globs"];
  function FilteredWebSocket(url,protocols){
    const socket=protocols===undefined?new Native(url):new Native(url,protocols);
    try{
      const u=new URL(String(url),location.href);
      if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
        Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
          let outgoing=data;
          if(typeof data==='string')try{
            const message=JSON.parse(data);
            if(message&&message.type==='subscribe_entities'){
              window.__haPaneldEntitySubscriptionId=message.id;
              if(!filterKeys.some(key=>Object.prototype.hasOwnProperty.call(message,key))){
                message.entity_ids=entityIds.length?entityIds:[emptySubscriptionEntityId];
                outgoing=JSON.stringify(message);
                try{if(window.haPaneldV2&&typeof window.haPaneldV2.postMessage==='function')window.haPaneldV2.postMessage(JSON.stringify({type:'entityFilterSubscriptionModified'}));}catch(e){}
              }
            }
          }catch(e){}
          return Native.prototype.send.call(this,outgoing);
        }});
      }
    }catch(e){}
    return socket;
  }
  Object.setPrototypeOf(FilteredWebSocket,Native);
  FilteredWebSocket.prototype=Native.prototype;
  for(const k of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(FilteredWebSocket,k,{value:Native[k]});
  Object.defineProperty(FilteredWebSocket,'__haPaneldEntityFilter',{value:true});
  window.WebSocket=FilteredWebSocket;
})();
