# Agent 修改指南

## 项目概览

Android Xposed 模块 + Cloudflare Workers Web 看板。手机被偷后用另一台手机发短信或打开网页就能定位/响警报/锁屏。

## 关键设计决策

### 省电模式（已实现）

- ❌ 不持续开 GPS
- ✅ 只有收到 LOCATE 指令才单次定位，定位完就关
- ✅ 手机每 5 秒 HTTP 轮询服务器（不是长连接）
- ✅ 轮询间隔在 `LocationService.java` 第 60 行 `POLL_INTERVAL_MS`

### DE 存储（已实现）

- `ConfigManager.getPreferences()` 使用 `createDeviceProtectedStorageContext()` 获取 DE 存储
- **解锁前后均可读写** SharedPreferences
- 新增 `migrateFromCe()` 方法在解锁后将 CE 旧数据迁移到 DE
- 迁移在 `MainActivity.onResume()` 和 `LocationService.pollServer()` 中自动触发

### KSU 保活模块（可选但推荐）

- `ksu_module/service.sh` 开机启动服务
- 强制前台服务（Android 14+ 要求），通知低优先级静音
- OOM adj 锁定为 -800，防止被 LMKD 杀死
- 30 秒看门狗自动重启
- Shell 后备轮询（Java 服务未就绪时也能响应 LOCATE）

### 指令去重

- `LocationService.fetchCommands()` 中 `HashMap<String, JSONObject> deduped`
- 同类型指令只保留最后一条，防止解锁后轰炸

### 坐标纠偏

- 前端自动检测瓦片 URL：`autonavi/webrd/wprd` → 国内地图
- `wgs84ToGcj02()` 函数转换坐标
- `fixCoords()` 在 `updateMarker()` 中调用

### maxZoom

- OSM（默认）: maxZoom=19
- 高德/国内: maxZoom=18
- `initMap()` 和 `getTileOpts()` 中动态设置

## 文件对照表

| 文件 | 功能 | 关键点 |
|------|------|--------|
| `app/.../FindMyDeviceEntry.java` | Xposed 入口 | Zygote 注入 + 钩 SystemServer |
| `app/.../BootReceiver.java` | 开机启动 | 只启动轮询，不启动 GPS |
| `app/.../SmsReceiver.java` | SMS 拦截 | 匹配前缀 + 验证授权号码 |
| `app/.../CommandProcessor.java` | 指令执行 | 12+ 种指令，全局单例音频管理 |
| `app/.../LocationService.java` | 核心服务 | 轮询 + 单次定位 + 指令去重 |
| `app/.../ConfigManager.java` | 配置管理 | DE 存储 + CE→DE 迁移 |
| `app/.../MainActivity.java` | 配置界面 | 权限申请 + 触发迁移 |
| `server/worker.js` | Web 看板（CF Worker） | 所有逻辑在单文件 |
| `server/server.js` | Web 看板（Node.js） | 内存存储，仅测试用 |
| `ksu_module/service.sh` | KSU 保活 | 开机启动 + OOM 锁定 + 看门狗 |

## 常见修改点

### 改轮询间隔

```java
// LocationService.java 第 60 行
private static final long POLL_INTERVAL_MS = 5 * 1000; // 5秒
```

### 添加新指令

1. `server/worker.js`（服务端）：在 `handleCreateCommand` 不做限制（任何 action 都可存）
2. `app/.../LocationService.java`：`executeServerCommand()` 加新 case
3. `app/.../CommandProcessor.java`：`executeCommand()` 加新 case + 实现方法
4. 前端（worker.js 嵌入式 JS + `public/app.js`）：加按钮或自定义命令

### 改通知行为

```java
// LocationService.java buildNotification()
.setPriority(Notification.PRIORITY_LOW)  // 低优先级
.setOngoing(true)                         // 常驻
```

Android 14+ 强制前台服务必须调 `startForeground()`，通知无法完全隐藏。

### 改前端坐标纠偏

```javascript
// worker.js 嵌入式 JS / public/app.js
_usingChinaTiles = u && (u.indexOf('autonavi') >= 0 || u.indexOf('webrd') >= 0 || u.indexOf('wprd') >= 0);
```

`fixCoords(lat, lng)` 在 `updateMarker()` 中调用。

### 编译

Windows:
```powershell
.\build.ps1              # Debug + KSU ZIP
.\build.ps1 -Release     # Release + KSU ZIP
```

Linux/macOS:
```bash
./gradlew assembleRelease
```

## 推送到 GitHub

```bash
git add -A
git commit -m "说明改了什么"
git push origin main
```

Cloudflare 自动部署监听 `main` 分支，`server/` 目录变更会自动重新部署。