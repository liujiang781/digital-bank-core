#!/usr/bin/env bash
# 实验四 S5 · 本机联调启动脚本
# 从 logs/_*.cmd（git 忽略）读取已有的本机凭据与环境变量，启动五个后端服务和网关。
# 本脚本不保存任何凭据到仓库；日志写入 logs/s5-*.log。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Microsoft/jdk-21.0.7.6-hotspot/bin/java.exe"
LOGDIR="$ROOT/logs"
mkdir -p "$LOGDIR"

# 合并五个 .cmd 中的环境变量（同名取最后一次），去掉 BOM 与 CRLF
declare -A ENV
for f in "$LOGDIR"/_user-service.cmd "$LOGDIR"/_account-service.cmd "$LOGDIR"/_transaction-service.cmd "$LOGDIR"/_risk-service.cmd "$LOGDIR"/_gateway.cmd; do
  [ -f "$f" ] || { echo "缺少启动配置: $f" >&2; exit 1; }
  while IFS= read -r line; do
    line="${line%$'\r'}"
    case "$line" in
      set\ \"*=*)
        kv="${line#set \"}"
        kv="${kv%\"}"
        key="${kv%%=*}"
        val="${kv#*=}"
        key="${key#$'\xef\xbb\xbf'}"
        ENV["$key"]="$val"
        ;;
    esac
  done < "$f"
done

start_service() {
  local module="$1" port="$2"
  local jar="$ROOT/backend/$module/target/$module-0.1.0-SNAPSHOT.jar"
  [ -f "$jar" ] || { echo "缺少 JAR: $jar" >&2; exit 1; }
  # 把合并后的环境变量导出，再按服务覆盖 SERVER_PORT（避免共用一个端口）
  for k in "${!ENV[@]}"; do export "$k=${ENV[$k]}"; done
  # S5 渠道三态演示需要显式开启内部令牌；未设置则内部演示接口保持关闭
  if [ -n "${S5_INTERNAL_TOKEN:-}" ]; then
    export TRANSACTION_INTERNAL_TOKEN="$S5_INTERNAL_TOKEN"
  fi
  export SERVER_PORT="$port"
  echo "[S5] 启动 $module 端口 $port"
  "$JAVA" -jar "$jar" > "$LOGDIR/s5-$module.log" 2>&1 &
  echo $! > "$LOGDIR/s5-$module.pid"
}

start_service user-service 8081
start_service account-service 8082
start_service transaction-service 8083
start_service risk-service 8084
start_service gateway 8090

echo "[S5] 全部进程已拉起；按 Ctrl+C 结束本脚本会同时停止服务。"
trap 'for p in "$LOGDIR"/s5-*.pid; do kill "$(cat "$p")" 2>/dev/null || true; done' EXIT
wait
