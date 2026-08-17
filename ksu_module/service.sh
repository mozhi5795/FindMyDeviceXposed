#!/system/bin/sh
# ============================================================
# FindMyDevice KSU 保活守护模块
# 开机自动拉起定位轮询服务，无需等待用户解锁
# 功能：
# 1. 开机后立即启动轮询服务（不依赖 Xposed 钩子时序）
# 2. 用 root 设置 OOM 优先级，防止被 Android 后台杀死
# 3. 保活看门狗，服务异常退出后自动重启
# 4. Shell 级 HTTP 轮询后备（在 Java 服务就绪前也能工作）
# ============================================================

MODDIR=${0%/*}
APP_PACKAGE="com.fyne.findmydevice"
SERVICE_CLASS="com.fyne.findmydevice.LocationService"
SERVICE_ACTION="com.fyne.findmydevice.START_POLLING"
POLL_INTERVAL=15
CONFIG_DIR="/data/data/${APP_PACKAGE}/shared_prefs"
CONFIG_FILE="${CONFIG_DIR}/findmydevice_config.xml"

# ---- 日志 ----
log() {
  echo "[FMD-KSU] $(date '+%Y-%m-%d %H:%M:%S') $*"
}

# ---- 等待系统启动完成 ----
wait_for_boot() {
  while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
  done
  # 等待服务管理器就绪
  sleep 5
}

# ---- 读取配置（从 SharedPreferences XML） ----
# 首次解锁前 FBE 加密，此函数会失败，届时使用默认值
read_config() {
  SERVER_URL=""
  DEVICE_TOKEN=""
  
  if [ -f "$CONFIG_FILE" ]; then
    # 尝试读取服务器地址
    local url_line=$(grep -oP 'string name="server_url">\K[^<]+' "$CONFIG_FILE" 2>/dev/null)
    [ -n "$url_line" ] && SERVER_URL="$url_line"
    
    # 尝试读取设备令牌
    local token_line=$(grep -oP 'string name="device_token">\K[^<]+' "$CONFIG_FILE" 2>/dev/null)
    [ -n "$token_line" ] && DEVICE_TOKEN="$token_line"
  fi
}

# ---- 启动 Java 轮询服务（无通知模式） ----
# KSU 模块用 root 权限直接启动后台服务并锁定 OOM 优先级，
# 不显示前台通知，由本模块的 root 权限保活。
start_fmd_service() {
  log "启动 FindMyDevice 轮询服务（KSU 无通知模式）..."
  
  # 直接启动后台服务，传 from_ksu=true 让 Java 代码跳过 startForeground
  am startservice \
    -n "${APP_PACKAGE}/${SERVICE_CLASS}" \
    -a "$SERVICE_ACTION" \
    --ez from_ksu true 2>/dev/null
  
  local pid=$(pgrep -f "${APP_PACKAGE}" | head -1)
  if [ -n "$pid" ]; then
    # 调低 OOM 优先级，防止被 LMKD 杀死
    # -800 = persistent service 级别（系统不会杀）
    echo -800 > /proc/$pid/oom_score_adj 2>/dev/null
    renice -n -10 -p $pid 2>/dev/null
    log "服务已启动，PID=$pid，OOM adj 锁定为 -800"
  else
    log "服务启动失败，将重试"
  fi
}

# ---- Shell 级 HTTP 轮询（后备方案） ----
# 当 Java 服务不可用时（如首次解锁前 FBE 加密），直接通过 curl 轮询
shell_poll_loop() {
  read_config
  
  # 没有配置信息时无法轮询，等 Java 服务
  if [ -z "$SERVER_URL" ] || [ -z "$DEVICE_TOKEN" ]; then
    return
  fi
  
  log "启动 Shell 级 HTTP 轮询（后备）"
  
  while true; do
    # 如果 Java 服务已在运行，停止 shell 轮询
    local java_pid=$(pgrep -f "${APP_PACKAGE}" | head -1)
    if [ -n "$java_pid" ]; then
      sleep 30
      continue
    fi
    
    # 读取最新配置
    read_config
    if [ -z "$SERVER_URL" ] || [ -z "$DEVICE_TOKEN" ]; then
      sleep 30
      continue
    fi
    
    # 拉取待执行指令
    local cmd_json=$(curl -s --connect-timeout 5 --max-time 10 \
      "${SERVER_URL}/api/commands?token=${DEVICE_TOKEN}" 2>/dev/null)
    
    if [ -n "$cmd_json" ] && [ "$cmd_json" != "[]" ] && [ "$cmd_json" != "null" ]; then
      log "收到远程指令: $cmd_json"
      # 提取指令 ID 和 action（简易 JSON 解析）
      echo "$cmd_json" | while read -r line; do
        local cmd_id=$(echo "$line" | grep -oP '"id":"\K[^"]+')
        local action=$(echo "$line" | grep -oP '"action":"\K[^"]+')
        [ -z "$action" ] && continue
        
        case "$action" in
          LOCATE|LOCATION)
            # 通过 dumpsys 获取最后已知位置
            local loc=$(dumpsys location 2>/dev/null | grep -oP 'Last Known Location: Location\[[^\]]+\]' | head -1)
            local lat=$(echo "$loc" | grep -oP 'lat=[^,]+' | cut -d= -f2)
            local lng=$(echo "$loc" | grep -oP 'lng=[^,]+' | cut -d= -f2)
            
            if [ -n "$lat" ] && [ -n "$lng" ]; then
              # 上报位置到服务器
              curl -s --connect-timeout 5 --max-time 10 \
                -X POST "${SERVER_URL}/api/report" \
                -H "Content-Type: application/json" \
                -d "{\"token\":\"${DEVICE_TOKEN}\",\"lat\":${lat},\"lng\":${lng},\"provider\":\"gps\",\"time\":$(date +%s)000}" \
                2>/dev/null
              
              # 上报指令执行结果
              curl -s --connect-timeout 5 --max-time 10 \
                -X POST "${SERVER_URL}/api/commands/result" \
                -H "Content-Type: application/json" \
                -d "{\"token\":\"${DEVICE_TOKEN}\",\"commandId\":\"${cmd_id}\",\"result\":\"ok\",\"time\":$(date +%s)000}" \
                2>/dev/null
              
              log "已上报位置: $lat, $lng"
            else
              log "无法获取 GPS 位置"
            fi
            ;;
        esac
      done
    fi
    
    sleep $POLL_INTERVAL
  done
}

# ---- 保活看门狗 ----
watchdog() {
  while true; do
    local pid=$(pgrep -f "${APP_PACKAGE}" | head -1)
    if [ -z "$pid" ]; then
      log "服务进程不存在，重新启动..."
      start_fmd_service
    else
      # 确保 OOM 优先级持续有效（可能被系统重置）
      local cur_adj=$(cat /proc/$pid/oom_score_adj 2>/dev/null)
      if [ "$cur_adj" != "-800" ]; then
        echo -800 > /proc/$pid/oom_score_adj 2>/dev/null
      fi
    fi
    sleep 30
  done
}

# ==================== 主流程 ====================

log "=== FindMyDevice KSU 保活守护启动 ==="
wait_for_boot

# 启动 Java 服务
start_fmd_service

# 启动 Shell 后备轮询（后台线程）
shell_poll_loop &

# 启动保活看门狗（前台线程）
watchdog