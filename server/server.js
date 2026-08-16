/**
 * FindMyDevice Web 看板服务端 - Node.js 版
 * 不再依赖 Socket.IO，纯 HTTP 轮询，兼容 Cloudflare Workers API
 * 
 * 用法: node server.js
 * 前端: http://localhost:3000
 */

const express = require('express');
const path = require('path');
const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// ==================== 数据存储（内存） ====================
// 重启丢失，仅用于测试
// 生产环境建议用 SQLite / LevelDB

const devices = new Map();
const pendingCommands = new Map();
const commandResults = [];

// ==================== API 路由 ====================

/** POST /api/report - 手机上报位置 */
app.post('/api/report', (req, res) => {
  const { token, lat, lng, accuracy, time, provider, battery, model } = req.body;
  if (!token) return res.status(400).json({ error: '缺少 token' });

  const device = devices.get(token) || { token, firstSeen: Date.now(), locations: [] };
  device.lastSeen = Date.now();
  device.lastLat = lat || 0;
  device.lastLng = lng || 0;
  device.lastAccuracy = accuracy || 0;
  device.lastProvider = provider || 'unknown';
  device.lastBattery = battery || 0;
  device.model = model || device.model || 'unknown';
  device.online = true;

  if (lat && lng) {
    device.locations.push({ lat, lng, accuracy, time: time || Date.now(), provider });
    if (device.locations.length > 200) {
      device.locations = device.locations.slice(-200);
    }
  }

  devices.set(token, device);
  res.json({ ok: true });
});

/** GET /api/commands?token=xxx - 手机拉取待执行指令（拉取后清空） */
app.get('/api/commands', (req, res) => {
  const { token } = req.query;
  if (!token) return res.status(400).json({ error: '缺少 token' });

  const cmds = pendingCommands.get(token) || [];
  pendingCommands.set(token, []);

  res.json(cmds.map(c => ({ id: c.id, action: c.action, parameter: c.parameter || '' })));
});

/** POST /api/commands - 看板创建指令 */
app.post('/api/commands', (req, res) => {
  const { token, action, parameter } = req.body;
  if (!token || !action) return res.status(400).json({ error: '缺少 token 或 action' });

  if (!devices.has(token)) return res.status(404).json({ error: '设备未找到' });

  const cmd = { id: String(Date.now()), action: action.toUpperCase(), parameter: parameter || '', createdAt: Date.now() };
  const cmds = pendingCommands.get(token) || [];
  cmds.push(cmd);
  pendingCommands.set(token, cmds);

  res.json({ ok: true, commandId: cmd.id });
});

/** POST /api/commands/result - 手机上报指令结果 */
app.post('/api/commands/result', (req, res) => {
  const { token, commandId, result, time } = req.body;
  if (!token || !commandId) return res.status(400).json({ error: '缺少参数' });

  commandResults.push({ token, commandId, action: '执行结果', result: result || 'unknown', time: time || Date.now() });
  if (commandResults.length > 500) commandResults.splice(0, commandResults.length - 500);

  res.json({ ok: true });
});

/** GET /api/devices - 获取所有设备列表 */
app.get('/api/devices', (req, res) => {
  const list = [];
  for (const [token, device] of devices) {
    list.push({
      token: device.token,
      model: device.model || 'unknown',
      lastSeen: device.lastSeen,
      lastLat: device.lastLat,
      lastLng: device.lastLng,
      lastAccuracy: device.lastAccuracy,
      lastBattery: device.lastBattery,
      online: device.online,
      locationCount: (device.locations || []).length
    });
  }
  res.json(list);
});

/** GET /api/device/:token - 获取单个设备详情 */
app.get('/api/device/:token', (req, res) => {
  const device = devices.get(req.params.token);
  if (!device) return res.status(404).json({ error: '设备未找到' });
  res.json(device);
});

/** GET /api/commands/history - 指令历史 */
app.get('/api/commands/history', (req, res) => {
  const { token, limit } = req.query;
  let result = commandResults;
  if (token) result = result.filter(r => r.token === token);
  if (limit) result = result.slice(-parseInt(limit));
  res.json(result);
});

// ==================== 启动 ====================

const PORT = process.env.PORT || 3000;
app.listen(PORT, '0.0.0.0', () => {
  console.log('==========================================');
  console.log('  FindMyDevice Web 看板服务端');
  console.log('  ----------------------------------------');
  console.log(`  本地:   http://localhost:${PORT}`);
  console.log(`  局域网: http://<本机IP>:${PORT}`);
  console.log('==========================================');
  console.log('手机模块设置中填入上述地址即可。');
  console.log('部署到 Cloudflare Workers 请使用 worker.js + wrangler.toml');
});