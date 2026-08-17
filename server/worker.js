// ============================================================
// FindMyDevice - Cloudflare Worker
// 功能：前端看板 + REST API + 登录认证
// 绑定 KV: FMD_KV
// ============================================================

// ---- 工具函数 ----
async function sha256(message) {
  const msgUint8 = new TextEncoder().encode(message);
  const hashBuffer = await crypto.subtle.digest('SHA-256', msgUint8);
  return Array.from(new Uint8Array(hashBuffer))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

function generateSessionId() {
  return Date.now().toString(36) + '-' + Math.random().toString(36).substring(2, 10);
}

// ---- KV 操作 ----
async function getKV(env, key, def) {
  try { return await env.FMD_KV.get(key, 'json') || def; } catch (e) { return def; }
}
async function putKV(env, key, val, ttl) {
  const opts = { expirationTtl: ttl };
  // 如果 ttl 不存在或为 0，不使用过期
  if (!ttl) {
    await env.FMD_KV.put(key, JSON.stringify(val));
  } else {
    await env.FMD_KV.put(key, JSON.stringify(val), opts);
  }
}

async function isPasswordSet(env) {
  const pw = await env.FMD_KV.get('config:password_hash');
  return !!pw;
}

async function verifyPassword(env, password) {
  const stored = await env.FMD_KV.get('config:password_hash');
  if (!stored) return false;
  const hash = await sha256(password);
  return hash === stored;
}

async function checkSession(env, request) {
  const cookie = request.headers.get('Cookie') || '';
  // 从 Cookie 中提取 session_id
  const match = cookie.match(/fmd_session=([^;]+)/);
  if (!match) return null;
  const sessionId = match[1];
  const session = await env.FMD_KV.get('session:' + sessionId, 'json');
  if (!session) return null;
  // 检查是否过期（24 小时）
  if (Date.now() - session.createdAt > 86400000) {
    await env.FMD_KV.delete('session:' + sessionId);
    return null;
  }
  return session;
}

// ---- 响应辅助 ----
function json(data, status) {
  return new Response(JSON.stringify(data), {
    status: status || 200,
    headers: { 'Content-Type': 'application/json' }
  });
}

function html(body, status) {
  return new Response(body, {
    status: status || 200,
    headers: { 'Content-Type': 'text/html;charset=UTF-8' }
  });
}

function redirect(to) {
  return new Response(null, { status: 302, headers: { 'Location': to } });
}

// 设置 session cookie
function withSessionCookie(response, sessionId) {
  const newResp = new Response(response.body, response);
  newResp.headers.set('Set-Cookie',
    `fmd_session=${sessionId}; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400`);
  return newResp;
}

// 清除 session cookie
function withoutSessionCookie(response) {
  const newResp = new Response(response.body, response);
  newResp.headers.set('Set-Cookie',
    'fmd_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0');
  return newResp;
}

// ---- 需要登录的 API 路由（包装器） ----
async function requireSession(env, request, handler) {
  const session = await checkSession(env, request);
  if (!session) {
    return json({ error: '未登录', login: true }, 401);
  }
  return handler(session);
}

// ---- 前端页面模板 ----
const LOGIN_PAGE = `<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>FindMyDevice - 登录</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<link rel="stylesheet" href="/style.css"/>
<style>
.login-container{max-width:400px;margin:80px auto;padding:32px;background:#16213e;border-radius:12px;border:1px solid #0f3460}
.login-container h2{text-align:center;color:#e94560;margin-bottom:24px}
.login-container input{width:100%;padding:12px;margin-bottom:16px;border:1px solid #0f3460;border-radius:6px;background:#1a1a2e;color:#eee;font-size:16px}
.login-container button{width:100%;padding:12px;background:#e94560;color:#fff;border:none;border-radius:6px;font-size:16px;cursor:pointer}
.login-container button:hover{opacity:.85}
.login-container .error{color:#f44336;text-align:center;margin-top:12px;display:none}
.login-container .hint{color:#888;text-align:center;margin-top:16px;font-size:13px}
</style></head>
<body><div class="login-container">
<h2>🔒 FindMyDevice 登录</h2>
<input type="password" id="password" placeholder="管理员密码" onkeydown="if(event.key==='Enter')login()"/>
<button onclick="login()">登录</button>
<div class="error" id="error-msg">密码错误</div>
<div class="hint">首次使用？请先访问 /setup 设置密码</div>
</div>
<script>
async function login(){
  const pw=document.getElementById('password').value;
  const errEl=document.getElementById('error-msg');
  if(!pw){errEl.textContent='请输入密码';errEl.style.display='block';return;}
  const r=await fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:pw})});
  const d=await r.json();
  if(d.ok){window.location.href='/';}
  else{errEl.textContent=d.error||'登录失败';errEl.style.display='block';}
}
</script></body></html>`;

const SETUP_PAGE = `<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>FindMyDevice - 初始设置</title>
<link rel="stylesheet" href="/style.css"/>
<style>
.setup-container{max-width:400px;margin:80px auto;padding:32px;background:#16213e;border-radius:12px;border:1px solid #0f3460}
.setup-container h2{text-align:center;color:#4CAF50;margin-bottom:8px}
.setup-container .sub{text-align:center;color:#888;font-size:13px;margin-bottom:24px}
.setup-container input{width:100%;padding:12px;margin-bottom:16px;border:1px solid #0f3460;border-radius:6px;background:#1a1a2e;color:#eee;font-size:16px}
.setup-container button{width:100%;padding:12px;background:#4CAF50;color:#fff;border:none;border-radius:6px;font-size:16px;cursor:pointer}
.setup-container button:hover{opacity:.85}
.setup-container .error{color:#f44336;text-align:center;margin-top:12px;display:none}
.setup-container .success{color:#4CAF50;text-align:center;margin-top:12px;display:none}
</style></head>
<body><div class="setup-container">
<h2>🔐 初始设置</h2>
<div class="sub">请设置管理员密码，用于登录看板</div>
<input type="password" id="password" placeholder="设置密码（至少6位）" onkeydown="if(event.key==='Enter')setup()"/>
<input type="password" id="confirm" placeholder="确认密码" onkeydown="if(event.key==='Enter')setup()"/>
<button onclick="setup()">创建管理员</button>
<div class="error" id="error-msg"></div>
<div class="success" id="success-msg">设置成功！正在跳转...</div>
</div>
<script>
async function setup(){
  const pw=document.getElementById('password').value;
  const cf=document.getElementById('confirm').value;
  const errEl=document.getElementById('error-msg');
  const sucEl=document.getElementById('success-msg');
  errEl.style.display='none';sucEl.style.display='none';
  if(!pw||pw.length<6){errEl.textContent='密码至少6位';errEl.style.display='block';return;}
  if(pw!==cf){errEl.textContent='两次密码不一致';errEl.style.display='block';return;}
  const r=await fetch('/api/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:pw})});
  const d=await r.json();
  if(d.ok){sucEl.style.display='block';setTimeout(function(){window.location.href='/';},1500);}
  else{errEl.textContent=d.error||'设置失败';errEl.style.display='block';}
}
</script></body></html>`;

// ---- 嵌入前端文件（与之前相同，但取消所有注释以节省空间） ----
const STATIC_FILES = {
  '/app.js': {
    contentType: 'application/javascript;charset=UTF-8',
    body: `var SELECTED_TOKEN=null,DEVICE_MARKERS={},MAP=null,POLL_INTERVAL=5000,_usingChinaTiles=false;
function getTileUrl(){var u=localStorage.getItem('fmd_tile_url');return u||'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';}function getTileOpts(u){var o={attribution:'Map',maxZoom:u&&(u.indexOf('autonavi')>=0||u.indexOf('webrd')>=0||u.indexOf('wprd')>=0)?18:19};if(u.indexOf('{s}')>=0){if(u.indexOf('webrd')>=0||u.indexOf('wprd')>=0||u.indexOf('autonavi')>=0){if(u.indexOf('webrd01')>=0||u.indexOf('wprd01')>=0){o.subdomains=['01','02','03','04']}else{o.subdomains=['1','2','3','4']}}}return o}function initMap(){var u=getTileUrl();var mz=u&&(u.indexOf('autonavi')>=0||u.indexOf('webrd')>=0||u.indexOf('wprd')>=0)?18:19;MAP=L.map('map',{center:[35,105],zoom:5,zoomControl:true,maxZoom:mz});L.tileLayer(u,getTileOpts(u)).addTo(MAP);_usingChinaTiles=mz<19;}
function setOnline(v){var e=document.getElementById('connection-status');e.textContent=v?'在线 ✓':'离线 ✗';e.className=v?'online':'offline';}
function loadDevices(){fetch('/api/devices',{credentials:'same-origin'}).then(function(r){if(r.status===401){window.location.href='/login';return};return r.json();}).then(function(dev){if(!dev)return;setOnline(true);renderList(dev);dev.forEach(function(d){if(d.lastLat&&d.lastLng)updateMarker({token:d.token,lat:d.lastLat,lng:d.lastLng,accuracy:d.lastAccuracy,battery:d.lastBattery,model:d.model,online:d.online});});if(!SELECTED_TOKEN&&dev.length>0)selectDevice(dev[0].token);}).catch(function(){setOnline(false);});}
function renderList(dev){var c=document.getElementById('device-list');if(!dev||!dev.length){c.innerHTML='<p class="hint">等待设备上报数据...</p>';return;}
c.innerHTML=dev.map(function(d){var on=d.online&&isRecent(d.lastSeen),ago=timeAgo(d.lastSeen),sel=d.token===SELECTED_TOKEN?'selected':'';return'<div class="device-card '+sel+'" onclick="selectDevice(\\''+d.token+'\\')"><div class="device-name"><span class="'+(on?'online':'offline')+'">●</span> '+(d.model||d.token.substring(0,16))+'</div><div class="device-meta">'+(on?'在线':'离线')+' · '+ago+(d.lastBattery>0?' · 电量 '+d.lastBattery+'%':'')+'</div></div>';}).join('');}
function selectDevice(token){SELECTED_TOKEN=token;loadDevices();document.getElementById('device-detail').style.display='block';loadDetail(token);var m=DEVICE_MARKERS[token];if(m){MAP.setView(m.getLatLng(),15);m.openPopup();}loadHistory(token);if(!window._firstLocated){window._firstLocated=true;sendCmd('LOCATE','');}}
function loadDetail(token){fetch('/api/device/'+encodeURIComponent(token),{credentials:'same-origin'}).then(function(r){return r.json();}).then(function(info){document.getElementById('detail-token').textContent='📱 '+(info.model||'未知');var on=info.online&&isRecent(info.lastSeen);document.getElementById('detail-info').innerHTML='<div>状态: '+(on?'<span style="color:#4CAF50">在线</span>':'<span style="color:#f44336">离线</span>')+'</div><div>标识: <code style="font-size:11px;color:#2196F3">'+token+'</code></div><div>型号: '+(info.model||'未知')+'</div><div>最后位置: '+(info.lastLat?info.lastLat.toFixed(6)+', '+info.lastLng.toFixed(6):'未知')+'</div><div>精度: '+(info.lastAccuracy?info.lastAccuracy.toFixed(1)+'米':'未知')+'</div><div>定位源: '+(info.lastProvider||'未知')+'</div><div>电量: '+(info.lastBattery>0?info.lastBattery+'%':'未知')+'</div><div>首次: '+fmtTime(info.firstSeen)+'</div><div>最后活跃: '+fmtTime(info.lastSeen)+'</div>';updateBar({token:token,lat:info.lastLat,lng:info.lastLng,accuracy:info.lastAccuracy,battery:info.lastBattery,model:info.model,time:info.lastSeen,online:on});}).catch(function(){});}
function updateBar(d){document.getElementById('bar-device').textContent='📱 '+(d.model||'--');document.getElementById('bar-battery').textContent=d.battery>0?'🔋 '+d.battery+'%':'🔋 --';document.getElementById('bar-location').textContent=d.lat?'📍 '+d.lat.toFixed(6)+', '+d.lng.toFixed(6):'📍 未知';document.getElementById('bar-time').textContent=d.time?'🕐 '+fmtTime(d.time):'🕐 --';}
function updateMarker(d){if(!d.lat||!d.lng)return;var fc=fixCoords(d.lat,d.lng),ll=[fc[0],fc[1]];if(DEVICE_MARKERS[d.token]){DEVICE_MARKERS[d.token].setLatLng(ll);}else{var ic=L.divIcon({html:'<div style="background:#e94560;color:#fff;border-radius:50%;width:32px;height:32px;display:flex;align-items:center;justify-content:center;font-size:16px;border:3px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,.5)">📱</div>',className:'',iconSize:[32,32],iconAnchor:[16,16]});var m=L.marker(ll,{icon:ic}).addTo(MAP);m.bindPopup('<b>'+(d.model||d.token.substring(0,10))+'</b><br>精度: '+(d.accuracy?d.accuracy.toFixed(1)+'米':'未知')+'<br>电量: '+(d.battery>0?d.battery+'%':'未知'));DEVICE_MARKERS[d.token]=m;}if(d.token===SELECTED_TOKEN&&MAP.getZoom()<14)MAP.setView(ll,15);}
function loadHistory(token){var url=token?'/api/commands/history?token='+encodeURIComponent(token):'/api/commands/history';fetch(url,{credentials:'same-origin'}).then(function(r){return r.json();}).then(function(items){var c=document.getElementById('history-list');var hint=c.querySelector('.hint');if(hint)hint.remove();if(!items||!items.length){c.innerHTML='<p class="hint">暂无记录</p>';return;}c.innerHTML=items.slice(-30).reverse().map(function(h){return'<div class="history-item"><span class="h-time">'+fmtTime(h.time)+'</span><span class="h-device">'+((h.token||'').substring(0,10))+'</span><span class="h-action">'+(h.action||'--')+'</span><span class="h-result'+(h.result&&h.result.startsWith('error')?' error':'')+'">'+(h.result||'--')+'</span></div>';}).join('');}).catch(function(){});}
document.addEventListener('click',function(e){var btn=e.target.closest('.cmd-btn');if(!btn)return;if(!SELECTED_TOKEN){document.getElementById('cmd-result').textContent='请先选择设备';return;}sendCmd(btn.dataset.action,'');});
document.getElementById('custom-cmd-btn').addEventListener('click',function(){var inp=document.getElementById('custom-cmd-input'),txt=inp.value.trim();if(!txt||!SELECTED_TOKEN)return;var act=txt,par='',idx=txt.indexOf('#');if(idx>0){act=txt.substring(0,idx).trim();par=txt.substring(idx+1).trim();}sendCmd(act,par);inp.value='';});
function sendCmd(act,par){var el=document.getElementById('cmd-result');el.textContent='发送中...';fetch('/api/commands',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:SELECTED_TOKEN,action:act,parameter:par}),credentials:'same-origin'}).then(function(r){return r.json();}).then(function(d){if(d.ok){el.textContent='✓ 已发送';loadHistory();}else if(d.login){window.location.href='/login';}else{el.textContent='✗ 失败: '+(d.error||'未知');}}).catch(function(){el.textContent='✗ 网络错误';});}
function isRecent(ts){return ts&&(Date.now()-ts<300000);}
function fmtTime(ts){if(!ts)return'--';var d=new Date(ts);return('0'+(d.getMonth()+1)).slice(-2)+'/'+('0'+d.getDate()).slice(-2)+' '+('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)+':'+('0'+d.getSeconds()).slice(-2);}
function timeAgo(ts){if(!ts)return'--';var diff=Date.now()-ts;if(diff<60000)return'刚刚';if(diff<3600000)return Math.floor(diff/60000)+'分钟前';if(diff<86400000)return Math.floor(diff/3600000)+'小时前';return Math.floor(diff/86400000)+'天前';}
// WGS-84 转 GCJ-02 坐标纠偏（高德等国内地图需要）
function wgs84ToGcj02(wlat,wlng){var a=6378245,ee=0.00669342162296594323;if(wlng<72.004||wlng>137.8347||wlat<0.8293||wlat>55.8271)return[wlat,wlng];var x=wlng-105,y=wlat-35;function tL(xx){return-100+2*xx+3*y+.2*y*y+.1*xx*y+.2*Math.sqrt(Math.abs(xx))+(20*Math.sin(6*xx*Math.PI)+20*Math.sin(2*xx*Math.PI))*2/3+(20*Math.sin(y*Math.PI)+40*Math.sin(y/3*Math.PI))*2/3+(160*Math.sin(y/12*Math.PI)+320*Math.sin(y*Math.PI/30))*2/3}function tR(xx){return 300+xx+2*y+.1*xx*xx+.1*xx*y+.1*Math.sqrt(Math.abs(xx))+(20*Math.sin(6*xx*Math.PI)+20*Math.sin(2*xx*Math.PI))*2/3+(20*Math.sin(xx*Math.PI)+40*Math.sin(xx/3*Math.PI))*2/3+(150*Math.sin(xx/12*Math.PI)+300*Math.sin(xx/30*Math.PI))*2/3}var dlat=tL(x),dlng=tR(x);var rad=wlat/180*Math.PI;var magic=1-ee*Math.sin(rad)*Math.sin(rad);var sqrtMagic=Math.sqrt(magic);dlat=dlat*180/(a*(1-ee)/(magic*sqrtMagic)*Math.PI);dlng=dlng*180/(a/sqrtMagic*Math.cos(rad)*Math.PI);return[wlat+dlat,wlng+dlng]}
function fixCoords(lat,lng){if(!_usingChinaTiles||!lat||!lng)return[lat,lng];var f=wgs84ToGcj02(lat,lng);return[f[0],f[1]];}
function poll(){loadDevices();loadHistory();}
function openSettings(){document.getElementById('settings-modal').style.display='flex';document.getElementById('tile-url-input').value=getTileUrl();}function saveSettings(){var v=document.getElementById('tile-url-input').value;if(v){localStorage.setItem('fmd_tile_url',v);MAP.remove();document.getElementById('map').innerHTML='';initMap();}document.getElementById('settings-modal').style.display='none';}function closeSettings(){document.getElementById('settings-modal').style.display='none';}initMap();poll();setInterval(poll,POLL_INTERVAL);`
  },
  '/style.css': {
    contentType: 'text/css;charset=UTF-8',
    body: `*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#1a1a2e;color:#eee;display:flex;flex-direction:column;height:100vh}
header{background:#16213e;padding:12px 24px;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #0f3460}
header h1{font-size:20px;font-weight:600;color:#e94560}
#connection-status{font-size:13px;padding:4px 12px;border-radius:12px;background:#555;color:#fff}
#connection-status.online{background:#4CAF50}
#connection-status.offline{background:#f44336}
main{display:flex;flex:1;overflow:hidden}
#sidebar{width:320px;min-width:320px;background:#16213e;padding:16px;overflow-y:auto;border-right:1px solid #0f3460}
#sidebar h2{font-size:16px;margin-bottom:12px;color:#eee}
#sidebar h3{font-size:14px;margin:8px 0;color:#ccc}
#sidebar h4{font-size:13px;margin:10px 0 6px;color:#aaa}
.hint{color:#666;font-size:13px;text-align:center;padding:20px 0}
.device-card{background:#1a1a2e;border:1px solid #0f3460;border-radius:8px;padding:12px;margin-bottom:8px;cursor:pointer;transition:all .2s}
.device-card:hover{border-color:#e94560;background:#1f1f3e}
.device-card.selected{border-color:#4CAF50;background:#1a3a2e}
.device-card .device-name{font-weight:600;font-size:14px;color:#fff}
.device-card .device-meta{font-size:12px;color:#888;margin-top:4px}
.device-card .online{color:#4CAF50}
.device-card .offline{color:#f44336}
#command-panel{display:grid;grid-template-columns:1fr 1fr;gap:6px;margin:8px 0}
.cmd-btn{padding:10px 8px;border:none;border-radius:6px;font-size:13px;font-weight:500;cursor:pointer;transition:all .2s;color:#fff}
.cmd-btn:hover{opacity:.85;transform:scale(1.02)}
.cmd-btn:active{transform:scale(.98)}
.cmd-locate{background:#2196F3}
.cmd-alarm{background:#f44336}
.cmd-ring{background:#FF9800}
.cmd-lock{background:#9C27B0}
.cmd-silent{background:#607D8B}
.cmd-vibrate{background:#795548}
.cmd-stop{background:#f44336}
#custom-command{display:flex;gap:6px;margin:8px 0}
#custom-cmd-input{flex:1;padding:8px;border:1px solid #0f3460;border-radius:4px;background:#1a1a2e;color:#eee;font-size:12px}
#custom-cmd-btn{padding:8px 16px;background:#e94560;color:#fff;border:none;border-radius:4px;cursor:pointer}
#cmd-result{font-size:12px;color:#4CAF50;margin-top:6px;min-height:20px}
#detail-info{font-size:13px;color:#aaa;line-height:1.6}
#map-section{flex:1;display:flex;flex-direction:column;position:relative}
#map{flex:1;background:#111}
#device-info-bar{background:#16213e;padding:8px 16px;display:flex;gap:24px;font-size:13px;color:#aaa;border-top:1px solid #0f3460}
#device-info-bar span{white-space:nowrap}
footer{background:#16213e;padding:12px 24px;border-top:1px solid #0f3460;max-height:150px;overflow-y:auto}
footer h3{font-size:14px;margin-bottom:8px;color:#ccc}
#history-list{font-size:12px;color:#888;line-height:1.8}
.history-item{display:flex;gap:16px;padding:2px 0;border-bottom:1px solid #0f3460}
.history-item .h-time{color:#666;min-width:80px}
.history-item .h-device{color:#2196F3;min-width:100px}
.history-item .h-action{color:#FF9800;min-width:60px}
.history-item .h-result{color:#4CAF50}
.history-item .h-result.error{color:#f44336}
@media(max-width:768px){main{flex-direction:column}#sidebar{width:100%;min-width:auto;max-height:40vh;border-right:none;border-bottom:1px solid #0f3460}#command-panel{grid-template-columns:1fr 1fr 1fr}}`
  }
};

// ---- 静态主页（登录后看到的 Dashboard） ----
function renderDashboardHTML() {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>FindMyDevice - 设备查找看板</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<link rel="stylesheet" href="/style.css"/>
</head>
<body>
<header><h1>🔍 FindMyDevice 远程查找看板</h1>
<div style="display:flex;align-items:center;gap:12px">
  <div id="connection-status">检查中...</div>
  <span onclick="openSettings()" style="cursor:pointer;font-size:18px" title="地图设置">⚙️</span>
  <a href="/api/logout" style="color:#aaa;font-size:13px;text-decoration:none">退出</a>
</div></header>
<main>
<aside id="sidebar">
  <h2>📱 设备列表</h2>
  <div id="device-list"><p class="hint">等待设备上报数据...</p></div>
  <div id="device-detail" style="display:none">
    <h3 id="detail-token">设备详情</h3><div id="detail-info"></div><hr>
    <h4>📤 发送指令</h4>
    <div id="command-panel">
      <button class="cmd-btn cmd-locate" data-action="LOCATE">📍 刷新位置</button>
      <button class="cmd-btn cmd-alarm" data-action="ALARM">🔔 警报</button>
      <button class="cmd-btn cmd-ring" data-action="RING">📞 响铃</button>
      <button class="cmd-btn cmd-stop" data-action="STOP">🛑 停止声音</button>
      <button class="cmd-btn cmd-lock" data-action="LOCK">🔒 锁屏</button>
      <button class="cmd-btn cmd-silent" data-action="SILENT">🔇 静音</button>
      <button class="cmd-btn cmd-vibrate" data-action="VIBRATE">📳 震动</button>
    </div>
    <div id="custom-command">
      <input type="text" id="custom-cmd-input" placeholder="NOTIFY#你好 或 指令#参数"/>
      <button id="custom-cmd-btn">发送</button>
    </div>
    <div id="cmd-result"></div>
  </div>
</aside>
<section id="map-section">
  <div id="map"></div>
  <div id="device-info-bar">
    <span id="bar-device">未选择设备</span>
    <span id="bar-battery">--</span>
    <span id="bar-location">--</span>
    <span id="bar-time">--</span>
  </div>
</section>
</main>
<footer><h3>📋 指令执行历史</h3><div id="history-list"><p class="hint">暂无记录</p></div></footer>
<div id="settings-modal" style="display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.6);z-index:9999;align-items:center;justify-content:center" onclick="if(event.target===this)closeSettings()">
  <div style="background:#16213e;border-radius:12px;padding:24px;max-width:500px;width:90%;border:1px solid #0f3460">
    <h3 style="color:#eee;margin-bottom:16px">🗺️ 地图瓦片设置</h3>
    <p style="color:#888;font-size:13px;margin-bottom:12px;line-height:1.6">
      填入瓦片 URL 模板，{s}/{z}/{x}/{y} 会被自动替换。<br>
      不填则使用默认。<br><br>
      🔹 高德地图(国内快): <code style="background:#1a1a2e;padding:2px 4px;border-radius:2px;font-size:12px">https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}</code><br>
      🔹 天地图: 需要申请 key<br>
      🔹 OSM(国际): <code style="background:#1a1a2e;padding:2px 4px;border-radius:2px;font-size:12px">https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png</code>
    </p>
    <input type="text" id="tile-url-input" placeholder="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" style="width:100%;padding:10px;border:1px solid #0f3460;border-radius:6px;background:#1a1a2e;color:#eee;font-size:14px;margin-bottom:12px"/>
    <div style="display:flex;gap:8px;justify-content:flex-end">
      <button onclick="closeSettings()" style="padding:8px 16px;background:#555;color:#fff;border:none;border-radius:6px;cursor:pointer">取消</button>
      <button onclick="localStorage.removeItem('fmd_tile_url');document.getElementById('tile-url-input').value='';saveSettings()" style="padding:8px 16px;background:#607D8B;color:#fff;border:none;border-radius:6px;cursor:pointer">恢复默认</button>
      <button onclick="saveSettings()" style="padding:8px 16px;background:#4CAF50;color:#fff;border:none;border-radius:6px;cursor:pointer">保存</button>
    </div>
  </div>
</div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script src="/app.js"></script>
</body></html>`;
}

// ==================== API 处理 ====================

async function handleApiSetup(request, env) {
  if (await isPasswordSet(env)) {
    return json({ error: '密码已设置' }, 400);
  }
  const { password } = await request.json();
  if (!password || password.length < 6) {
    return json({ error: '密码至少6位' }, 400);
  }
  const hash = await sha256(password);
  await env.FMD_KV.put('config:password_hash', hash);
  return json({ ok: true });
}

async function handleApiLogin(request, env) {
  const { password } = await request.json();
  if (!password) return json({ error: '请输入密码' }, 400);

  if (!(await isPasswordSet(env))) {
    return json({ error: '尚未设置密码，请先访问 /setup' }, 400);
  }

  const valid = await verifyPassword(env, password);
  if (!valid) return json({ error: '密码错误' }, 401);

  const sessionId = generateSessionId();
  await putKV(env, 'session:' + sessionId, { createdAt: Date.now() }, 86400);

  const resp = json({ ok: true });
  return withSessionCookie(resp, sessionId);
}

async function handleApiLogout(env, request) {
  const cookie = request.headers.get('Cookie') || '';
  const match = cookie.match(/fmd_session=([^;]+)/);
  if (match) {
    await env.FMD_KV.delete('session:' + match[1]);
  }
  return withoutSessionCookie(redirect('/login'));
}

async function handleApiCheckSession(env, request) {
  const session = await checkSession(env, request);
  if (!session) return json({ ok: false }, 401);
  return json({ ok: true });
}

// ---- 设备数据 API（需要登录） ----

async function handleListDevices(env) {
  const list = [];
  try {
    const result = await env.FMD_KV.list({ prefix: 'device:' });
    for (const key of result.keys) {
      const device = await env.FMD_KV.get(key.name, 'json');
      if (device) {
        list.push({
          token: device.token, model: device.model || 'unknown',
          lastSeen: device.lastSeen, lastLat: device.lastLat, lastLng: device.lastLng,
          lastAccuracy: device.lastAccuracy, lastBattery: device.lastBattery,
          online: device.online, locationCount: (device.locations || []).length
        });
      }
    }
  } catch (e) {}
  return json(list);
}

async function handleGetDevice(env, token) {
  const device = await getKV(env, 'device:' + token, null);
  if (!device) return json({ error: '设备未找到' }, 404);
  return json(device);
}

async function handleCreateCommand(env, request) {
  const body = await request.json();
  const { token, action, parameter } = body;
  if (!token || !action) return json({ error: '缺少 token 或 action' }, 400);

  const device = await getKV(env, 'device:' + token, null);
  if (!device) return json({ error: '设备未找到' }, 404);

  const cmd = { id: String(Date.now()), action: action.toUpperCase(), parameter: parameter || '', createdAt: Date.now() };
  const cmds = await getKV(env, 'commands:' + token, []);
  cmds.push(cmd);
  await putKV(env, 'commands:' + token, cmds);

  return json({ ok: true, commandId: cmd.id });
}

async function handleGetHistory(env, url) {
  const history = await getKV(env, 'history', []);
  const t = url.searchParams.get('token');
  const limit = url.searchParams.get('limit');
  let result = history;
  if (t) result = result.filter(h => h.token === t);
  if (limit) result = result.slice(-parseInt(limit));
  return json(result);
}

// ---- 手机端 API（不需要登录，只需设备 token） ----

async function handleReport(request, env) {
  const body = await request.json();
  const { token, lat, lng, accuracy, time, provider, battery, model } = body;
  if (!token) return json({ error: '缺少 token' }, 400);

  const device = await getKV(env, 'device:' + token, { token, firstSeen: Date.now(), locations: [] });
  device.lastSeen = Date.now();
  device.lastLat = lat || 0; device.lastLng = lng || 0;
  device.lastAccuracy = accuracy || 0; device.lastProvider = provider || 'unknown';
  device.lastBattery = battery || 0; device.model = model || device.model || 'unknown';
  device.online = true;

  if (lat && lng) {
    device.locations.push({ lat, lng, accuracy, time: time || Date.now(), provider });
    if (device.locations.length > 200) device.locations = device.locations.slice(-200);
  }
  await putKV(env, 'device:' + token, device);
  return json({ ok: true });
}

async function handleGetCommands(env, url) {
  const token = url.searchParams.get('token');
  if (!token) return json({ error: '缺少 token' }, 400);

  // 设备轮询时更新在线状态（每 30 秒写一次 KV，省配额）
  const device = await getKV(env, 'device:' + token, null);
  if (device) {
    device.lastSeen = Date.now();
    device.online = true;
    // 距上次写入超过 30 秒才写，避免每 5 秒刷一次 KV
    if (!device._lastWrite || Date.now() - device._lastWrite > 30000) {
      device._lastWrite = Date.now();
      await putKV(env, 'device:' + token, device);
    }
  }

  const cmds = await getKV(env, 'commands:' + token, []);
  if (cmds.length > 0) {
    await putKV(env, 'commands:' + token, []); // 有指令才清空
  }
  return json(cmds.map(c => ({ id: c.id, action: c.action, parameter: c.parameter || '' })));
}

async function handleCommandResult(request, env) {
  const body = await request.json();
  const { token, commandId, result, time } = body;
  if (!token || !commandId) return json({ error: '缺少参数' }, 400);

  let history = await getKV(env, 'history', []);
  history.push({ token, commandId, action: '执行结果', result: result || 'unknown', time: time || Date.now() });
  if (history.length > 500) history = history.slice(-500);
  await putKV(env, 'history', history);
  return json({ ok: true });
}

// ==================== 路由 ====================

async function handleRequest(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;

  // ---- 静态文件（无需登录） ----
  const staticKey = path === '/' ? null : path; // 根路径由下面处理
  if (path !== '/' && STATIC_FILES[path]) {
    return new Response(STATIC_FILES[path].body, {
      headers: { 'Content-Type': STATIC_FILES[path].contentType }
    });
  }

  // ---- 登录/登出/设置页面（无需登录） ----
  if (path === '/login' && method === 'GET') {
    // 如果已经登录，跳转首页
    const session = await checkSession(env, request);
    if (session) return redirect('/');
    return html(LOGIN_PAGE);
  }

  if (path === '/setup' && method === 'GET') {
    if (await isPasswordSet(env)) return redirect('/login');
    return html(SETUP_PAGE);
  }

  if (path === '/api/logout' && method === 'GET') {
    return handleApiLogout(env, request);
  }

  // ---- 认证 API（无需 session） ----
  if (path === '/api/setup' && method === 'POST') {
    return handleApiSetup(request, env);
  }
  if (path === '/api/login' && method === 'POST') {
    return handleApiLogin(request, env);
  }
  if (path === '/api/check-session' && method === 'GET') {
    return handleApiCheckSession(env, request);
  }

  // ---- 手机端 API（无需 session，靠设备 token 识别） ----
  if (path === '/api/report' && method === 'POST') {
    return handleReport(request, env);
  }
  if (path === '/api/commands' && method === 'GET') {
    return handleGetCommands(env, url);
  }
  if (path === '/api/commands/result' && method === 'POST') {
    return handleCommandResult(request, env);
  }

  // ---- 以下 Web 端 API 需要登录 ----
  if (path === '/api/devices' && method === 'GET') {
    return requireSession(env, request, () => handleListDevices(env));
  }
  if (path.startsWith('/api/device/') && method === 'GET') {
    return requireSession(env, request, () => handleGetDevice(env, path.substring('/api/device/'.length)));
  }
  if (path === '/api/commands' && method === 'POST') {
    return requireSession(env, request, () => handleCreateCommand(env, request));
  }
  if (path === '/api/commands/history' && method === 'GET') {
    return requireSession(env, request, () => handleGetHistory(env, url));
  }

  // ---- 主页（需要登录） ----
  if (path === '/') {
    const session = await checkSession(env, request);
    if (!session) {
      if (await isPasswordSet(env)) return redirect('/login');
      return redirect('/setup');
    }
    return html(renderDashboardHTML());
  }

  return new Response('Not Found', { status: 404 });
}

// ==================== Worker 入口 ====================

export default {
  async fetch(request, env, ctx) {
    try {
      return await handleRequest(request, env);
    } catch (err) {
      return new Response('Internal Error: ' + err.message, { status: 500 });
    }
  }
};
