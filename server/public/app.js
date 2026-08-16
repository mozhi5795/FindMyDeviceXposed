// ==================== FindMyDevice Web Dashboard ====================
// HTTP Polling 版，兼容 Node.js 服务器和 Cloudflare Workers

var SELECTED_TOKEN = null;
var DEVICE_MARKERS = {};
var MAP = null;
var POLL_INTERVAL = 5000; // 5秒轮询

// ---- 地图瓦片设置 ----
// 默认使用 OpenStreetMap，可在看板右上角 ⚙️ 中修改
// 高德地图(国内快): https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}
// 注意：高德用数字子域名 0-3，Leaflet 默认用字母 a-c，代码已自动适配
function getTileUrl() {
    var u = localStorage.getItem('fmd_tile_url');
    return u || 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';
}

/** 根据 URL 自动选择子域名（高德用数字，其他用字母 a-c） */
function getTileOpts(url) {
    var opts = { attribution: 'Map', maxZoom: 19 };
    if (url.indexOf('{s}') >= 0) {
        // 高德：webrd01/wprd01 等格式用 01/02/03/04；webrd0/wprd0 等格式用 0/1/2/3；通用格式用数字
        if (url.indexOf('webrd') >= 0 || url.indexOf('wprd') >= 0 || url.indexOf('autonavi') >= 0) {
            // 判断是两位数字(01)还是一位数字(0)
            if (url.indexOf('webrd01') >= 0 || url.indexOf('wprd01') >= 0) {
                opts.subdomains = ['01', '02', '03', '04'];
            } else {
                opts.subdomains = ['1', '2', '3', '4'];
            }
        }
    }
    return opts;
}

// ---- 初始化地图 ----
function initMap() {
    MAP = L.map('map', { center: [35, 105], zoom: 5, zoomControl: true });
    var url = getTileUrl();
    L.tileLayer(url, getTileOpts(url)).addTo(MAP);
}

// ---- 地图设置面板 ----
function openSettings() {
    document.getElementById('settings-modal').style.display = 'flex';
    document.getElementById('tile-url-input').value = getTileUrl();
}
function saveSettings() {
    var v = document.getElementById('tile-url-input').value;
    if (v) {
        localStorage.setItem('fmd_tile_url', v);
        MAP.remove();
        document.getElementById('map').innerHTML = '';
        initMap();
    }
    document.getElementById('settings-modal').style.display = 'none';
}
function closeSettings() {
    document.getElementById('settings-modal').style.display = 'none';
}

// ---- 状态指示 ----
function setOnline(online) {
    var el = document.getElementById('connection-status');
    el.textContent = online ? '在线 ✓' : '离线 ✗';
    el.className = online ? 'online' : 'offline';
}

// ---- 加载设备列表 ----
function loadDevices() {
    fetch('/api/devices')
        .then(function(r) { return r.json(); })
        .then(function(devices) {
            setOnline(true);
            renderDeviceList(devices);
            devices.forEach(function(d) {
                if (d.lastLat && d.lastLng) {
                    updateMarker({
                        token: d.token, lat: d.lastLat, lng: d.lastLng,
                        accuracy: d.lastAccuracy, battery: d.lastBattery,
                        model: d.model, online: d.online
                    });
                }
            });
            if (!SELECTED_TOKEN && devices.length > 0) {
                selectDevice(devices[0].token);
            }
        })
        .catch(function() {
            setOnline(false);
        });
}

function renderDeviceList(devices) {
    var container = document.getElementById('device-list');
    if (!devices || devices.length === 0) {
        container.innerHTML = '<p class="hint">暂无设备，请确保手机模块已配置并上报数据</p>';
        return;
    }
    container.innerHTML = devices.map(function(d) {
        var online = d.online && isRecent(d.lastSeen);
        var ago = timeAgo(d.lastSeen);
        var sel = d.token === SELECTED_TOKEN ? 'selected' : '';
        return '<div class="device-card ' + sel + '" onclick="selectDevice(\'' + d.token + '\')">'
            + '<div class="device-name"><span class="' + (online ? 'online' : 'offline') + '">●</span> '
            + (d.model || d.token.substring(0, 16)) + '</div>'
            + '<div class="device-meta">' + (online ? '在线' : '离线') + ' · ' + ago
            + (d.lastBattery > 0 ? ' · 电量 ' + d.lastBattery + '%' : '')
            + (d.locationCount > 0 ? ' · ' + d.locationCount + ' 个位置点' : '') + '</div></div>';
    }).join('');
}

function selectDevice(token) {
    SELECTED_TOKEN = token;
    loadDevices(); // 刷新选中状态
    document.getElementById('device-detail').style.display = 'block';
    loadDeviceDetail(token);
    var m = DEVICE_MARKERS[token];
    if (m) {
        MAP.setView(m.getLatLng(), 15);
        m.openPopup();
    }
    loadDeviceHistory(token);
}

function loadDeviceDetail(token) {
    fetch('/api/device/' + encodeURIComponent(token))
        .then(function(r) { return r.json(); })
        .then(function(info) {
            document.getElementById('detail-token').textContent = '📱 ' + (info.model || '未知设备');
            var online = info.online && isRecent(info.lastSeen);
            document.getElementById('detail-info').innerHTML =
                '<div>状态: ' + (online ? '<span style="color:#4CAF50">在线</span>' : '<span style="color:#f44336">离线</span>') + '</div>'
                + '<div>设备标识: <code style="font-size:11px;color:#2196F3">' + token + '</code></div>'
                + '<div>型号: ' + (info.model || '未知') + '</div>'
                + '<div>最后位置: ' + (info.lastLat ? info.lastLat.toFixed(6) + ', ' + info.lastLng.toFixed(6) : '未知') + '</div>'
                + '<div>精度: ' + (info.lastAccuracy ? info.lastAccuracy + '米' : '未知') + '</div>'
                + '<div>定位源: ' + (info.lastProvider || '未知') + '</div>'
                + '<div>电量: ' + (info.lastBattery > 0 ? info.lastBattery + '%' : '未知') + '</div>'
                + '<div>位置点数: ' + ((info.locations || []).length) + '</div>'
                + '<div>首次上报: ' + fmtTime(info.firstSeen) + '</div>'
                + '<div>最后活跃: ' + fmtTime(info.lastSeen) + '</div>';
            updateInfoBar({
                token: token, lat: info.lastLat, lng: info.lastLng,
                accuracy: info.lastAccuracy, battery: info.lastBattery,
                model: info.model, time: info.lastSeen, online: online
            });
        })
        .catch(function() {});
}

function updateInfoBar(data) {
    document.getElementById('bar-device').textContent = '📱 ' + (data.model || (data.token || '').substring(0, 10));
    document.getElementById('bar-battery').textContent = data.battery > 0 ? '🔋 ' + data.battery + '%' : '🔋 --';
    document.getElementById('bar-location').textContent = data.lat ? '📍 ' + data.lat.toFixed(6) + ', ' + data.lng.toFixed(6) : '📍 未知';
    document.getElementById('bar-time').textContent = data.time ? '🕐 ' + fmtTime(data.time) : '🕐 --';
}

// ---- 地图标记 ----
function updateMarker(data) {
    if (!data.lat || !data.lng) return;
    var latlng = [data.lat, data.lng];
    if (DEVICE_MARKERS[data.token]) {
        DEVICE_MARKERS[data.token].setLatLng(latlng);
    } else {
        var icon = L.divIcon({
            html: '<div style="background:#e94560;color:#fff;border-radius:50%;width:32px;height:32px;display:flex;align-items:center;justify-content:center;font-size:16px;border:3px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,.5)">📱</div>',
            className: '', iconSize: [32, 32], iconAnchor: [16, 16]
        });
        var m = L.marker(latlng, { icon: icon }).addTo(MAP);
        m.bindPopup('<b>' + (data.model || data.token.substring(0, 10)) + '</b><br>精度: ' + (data.accuracy ? data.accuracy + '米' : '未知') + '<br>电量: ' + (data.battery > 0 ? data.battery + '%' : '未知'));
        DEVICE_MARKERS[data.token] = m;
    }
    if (data.token === SELECTED_TOKEN && MAP.getZoom() < 14) {
        MAP.setView(latlng, 15);
    }
}

// ---- 轨迹 ----
function loadDeviceHistory(token) {
    fetch('/api/device/' + encodeURIComponent(token))
        .then(function(r) { return r.json(); })
        .then(function(info) {
            var locs = info.locations || [];
            if (locs.length < 2) return;
            if (window._trailLine) MAP.removeLayer(window._trailLine);
            if (window._trailStart) MAP.removeLayer(window._trailStart);
            if (window._trailEnd) MAP.removeLayer(window._trailEnd);
            var pts = locs.map(function(l) { return [l.lat, l.lng]; });
            window._trailLine = L.polyline(pts, { color: '#e94560', weight: 3, opacity: 0.6 }).addTo(MAP);
            window._trailStart = L.circleMarker(pts[0], { radius: 5, color: '#4CAF50', fillColor: '#4CAF50', fillOpacity: 1 }).addTo(MAP).bindPopup('最早位置');
            window._trailEnd = L.circleMarker(pts[pts.length - 1], { radius: 5, color: '#f44336', fillColor: '#f44336', fillOpacity: 1 }).addTo(MAP).bindPopup('最新位置');
            MAP.fitBounds(pts);
        })
        .catch(function() {});
}

// ---- 指令发送 ----
document.addEventListener('click', function(e) {
    var btn = e.target.closest('.cmd-btn');
    if (!btn) return;
    if (!SELECTED_TOKEN) { document.getElementById('cmd-result').textContent = '请先选择设备'; return; }
    sendCmd(btn.dataset.action, '');
});

document.getElementById('custom-cmd-btn').addEventListener('click', function() {
    var input = document.getElementById('custom-cmd-input');
    var text = input.value.trim();
    if (!text || !SELECTED_TOKEN) return;
    var action = text, param = '';
    var idx = text.indexOf('#');
    if (idx > 0) { action = text.substring(0, idx).trim(); param = text.substring(idx + 1).trim(); }
    sendCmd(action, param);
    input.value = '';
});

function sendCmd(action, parameter) {
    var el = document.getElementById('cmd-result');
    el.textContent = '发送中...';
    fetch('/api/commands', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: SELECTED_TOKEN, action: action, parameter: parameter })
    })
    .then(function(r) { return r.json(); })
    .then(function(data) {
        if (data.ok) {
            el.textContent = '✓ 指令已发送';
            // 刷新历史
            loadHistory();
        } else {
            el.textContent = '✗ 发送失败: ' + (data.error || '未知');
        }
    })
    .catch(function() { el.textContent = '✗ 网络错误'; });
}

// ---- 加载指令历史 ----
function loadHistory() {
    fetch('/api/commands/history')
        .then(function(r) { return r.json(); })
        .then(function(items) {
            var container = document.getElementById('history-list');
            var hint = container.querySelector('.hint');
            if (hint) hint.remove();
            if (!items || items.length === 0) {
                if (!container.querySelector('.hint')) {
                    container.innerHTML = '<p class="hint">暂无记录</p>';
                }
                return;
            }
            container.innerHTML = items.slice(-30).reverse().map(function(h) {
                return '<div class="history-item">'
                    + '<span class="h-time">' + fmtTime(h.time) + '</span>'
                    + '<span class="h-device">' + ((h.token || '').substring(0, 10)) + '</span>'
                    + '<span class="h-action">' + (h.action || '--') + '</span>'
                    + '<span class="h-result' + (h.result && h.result.startsWith('error') ? ' error' : '') + '">' + (h.result || '--') + '</span></div>';
            }).join('');
        })
        .catch(function() {});
}

// ---- 工具函数 ----
function isRecent(ts) { return ts && (Date.now() - ts < 5 * 60 * 1000); }

function fmtTime(ts) {
    if (!ts) return '--';
    var d = new Date(ts);
    return ('0' + (d.getMonth() + 1)).slice(-2) + '/' + ('0' + d.getDate()).slice(-2) + ' '
        + ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2) + ':' + ('0' + d.getSeconds()).slice(-2);
}

function timeAgo(ts) {
    if (!ts) return '--';
    var diff = Date.now() - ts;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前';
    return Math.floor(diff / 86400000) + '天前';
}

// ---- 轮询 ----
function poll() {
    loadDevices();
    loadHistory();
}

// ---- 初始化 ----
initMap();
poll();
setInterval(poll, POLL_INTERVAL);