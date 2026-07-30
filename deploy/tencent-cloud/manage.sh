#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$ROOT_DIR/dk-ai-agent"
COMPOSE_FILE="$COMPOSE_DIR/docker-compose.yml"
ENV_FILE="$COMPOSE_DIR/.env"
RAW_DIR="$ROOT_DIR/counseling-kb/raw"

FRONTEND_PORT=""
FRONTEND_BIND_ADDRESS=""
STARTUP_TIMEOUT_SECONDS=""

log() {
  printf '[deploy] %s\n' "$*"
}

warn() {
  printf '[deploy][warning] %s\n' "$*" >&2
}

die() {
  printf '[deploy][error] %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: ./manage.sh <command> [service]

Commands:
  check             Validate Docker, .env, corpus, disk and port 3004
  deploy            Build images, start all services and wait for health
  start             Start existing images without rebuilding
  restart           Restart running services and wait for health
  stop              Stop containers; named database/model volumes are retained
  status            Show container and HTTP health status
  logs [service]    Follow the last 200 log lines (backend/frontend/ai-worker/postgres)
  backup            Create a PostgreSQL custom-format dump under ./backups

This script never deletes Docker volumes and never kills a process occupying a port.
EOF
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"
}

require_env_file() {
  [[ -f "$ENV_FILE" ]] || die "缺少 $ENV_FILE；先执行：cp dk-ai-agent/.env.example dk-ai-agent/.env"
}

env_value() {
  local key="$1"
  sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r'
}

load_runtime_values() {
  require_env_file
  FRONTEND_PORT="$(env_value FRONTEND_PORT)"
  FRONTEND_BIND_ADDRESS="$(env_value FRONTEND_BIND_ADDRESS)"
  STARTUP_TIMEOUT_SECONDS="$(env_value STARTUP_TIMEOUT_SECONDS)"
  FRONTEND_PORT="${FRONTEND_PORT:-3004}"
  FRONTEND_BIND_ADDRESS="${FRONTEND_BIND_ADDRESS:-0.0.0.0}"
  STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-1200}"

  [[ "$FRONTEND_PORT" =~ ^[0-9]+$ ]] || die "FRONTEND_PORT 必须是整数"
  (( 10#$FRONTEND_PORT >= 1 && 10#$FRONTEND_PORT <= 65535 )) || die "FRONTEND_PORT 超出 1-65535"
  [[ "$FRONTEND_BIND_ADDRESS" == "0.0.0.0" || "$FRONTEND_BIND_ADDRESS" == "127.0.0.1" ]] \
    || die "FRONTEND_BIND_ADDRESS 只允许 0.0.0.0 或 127.0.0.1"
  [[ "$STARTUP_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || die "STARTUP_TIMEOUT_SECONDS 必须是整数"
}

compose() {
  # Docker Compose lets exported shell variables override --env-file. Remove every
  # variable referenced by this Compose file so the audited .env stays authoritative.
  local key
  local -a sanitized_environment=(env)
  while IFS= read -r key; do
    [[ -n "$key" ]] && sanitized_environment+=(-u "$key")
  done < <(grep -oE '\$\{[A-Za-z_][A-Za-z0-9_]*' "$COMPOSE_FILE" | sed 's/^${//' | sort -u)

  "${sanitized_environment[@]}" docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

require_secret() {
  local key="$1"
  local min_length="$2"
  local value
  value="$(env_value "$key")"
  case "$value" in
    ""|*CHANGE_ME*|*replace-with*)
      die "$key 仍为空或保留了示例占位符"
      ;;
  esac
  (( ${#value} >= min_length )) || die "$key 长度至少需要 $min_length 个字符"
}

validate_env() {
  load_runtime_values
  require_secret DEEPSEEK_API_KEY 16
  require_secret POSTGRES_PASSWORD 16
  require_secret AI_WORKER_SHARED_SECRET 24
  require_secret ADMIN_INITIAL_PASSWORD 12

  local secure_cookie
  secure_cookie="$(env_value SESSION_COOKIE_SECURE)"
  [[ "$secure_cookie" == "true" || "$secure_cookie" == "false" ]] \
    || die "SESSION_COOKIE_SECURE 必须为 true 或 false"

  if [[ "$FRONTEND_BIND_ADDRESS" == "0.0.0.0" && "$secure_cookie" == "true" ]]; then
    warn "当前直接监听所有网卡且启用了 Secure Cookie；只能通过 HTTPS 访问，否则登录 Cookie 不会回传。"
  fi
  if [[ "$FRONTEND_BIND_ADDRESS" == "0.0.0.0" && "$secure_cookie" == "false" ]]; then
    warn "当前为直接 HTTP 模式。只应临时使用，并在腾讯云安全组中把 3004 限制为你的来源 IP。"
  fi

  chmod 600 "$ENV_FILE"
}

check_corpus() {
  [[ -d "$RAW_DIR" ]] || die "逐字稿目录不存在：$RAW_DIR"
  local count
  count="$(find "$RAW_DIR" -type f | wc -l | tr -d ' ')"
  (( count > 0 )) || die "逐字稿目录为空：$RAW_DIR"
  log "逐字稿文件：$count"
}

own_frontend_uses_port() {
  local container_id published
  container_id="$(compose ps -q frontend 2>/dev/null || true)"
  [[ -n "$container_id" ]] || return 1
  published="$(docker port "$container_id" 80/tcp 2>/dev/null || true)"
  [[ "$published" =~ :${FRONTEND_PORT}$ ]]
}

port_listener_details() {
  if command -v ss >/dev/null 2>&1; then
    ss -H -ltnp "sport = :$FRONTEND_PORT" 2>/dev/null || true
  elif command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$FRONTEND_PORT" -sTCP:LISTEN 2>/dev/null || true
  elif command -v netstat >/dev/null 2>&1; then
    netstat -lntp 2>/dev/null | awk -v port=":$FRONTEND_PORT" '$4 ~ port "$"'
  fi
}

check_port() {
  local listeners
  listeners="$(port_listener_details)"
  if [[ -n "$listeners" ]]; then
    if own_frontend_uses_port; then
      log "端口 $FRONTEND_PORT 已由本项目 frontend 容器监听，可原地更新。"
      return
    fi
    printf '%s\n' "$listeners" >&2
    die "端口 $FRONTEND_PORT 已被其他进程占用。脚本不会自动杀进程，请先确认并处理。"
  fi
  log "端口 $FRONTEND_PORT 可用。"
}

check_disk() {
  local available_kb
  available_kb="$(df -Pk "$ROOT_DIR" | awk 'NR == 2 {print $4}')"
  if [[ "$available_kb" =~ ^[0-9]+$ ]] && (( available_kb < 6291456 )); then
    warn "可用磁盘少于 6 GiB，首次 Docker 构建和模型缓存可能失败。"
  fi
}

check_docker() {
  require_command docker
  docker info >/dev/null 2>&1 \
    || die "Docker daemon 不可用。执行 sudo systemctl start docker；若权限不足，重新登录 docker 用户组。"
  docker compose version >/dev/null 2>&1 \
    || die "缺少 Docker Compose v2 插件（需要 docker compose，而不是旧版 docker-compose）。"
}

cmd_check() {
  check_docker
  require_command curl
  validate_env
  check_corpus
  check_disk
  compose config --quiet
  check_port
  log "部署检查通过：${FRONTEND_BIND_ADDRESS}:${FRONTEND_PORT}"
}

wait_until_ready() {
  local url deadline attempt
  url="http://127.0.0.1:${FRONTEND_PORT}/api/health"
  deadline=$(( SECONDS + STARTUP_TIMEOUT_SECONDS ))
  attempt=0

  log "等待服务就绪：$url（最多 ${STARTUP_TIMEOUT_SECONDS}s）"
  while (( SECONDS < deadline )); do
    if curl --fail --silent --show-error --max-time 5 "$url" >/dev/null 2>&1; then
      log "服务已就绪：$url"
      return 0
    fi
    attempt=$(( attempt + 1 ))
    if (( attempt % 6 == 0 )); then
      compose ps || true
    fi
    sleep 5
  done

  compose ps || true
  compose logs --tail=120 backend frontend ai-worker || true
  die "服务在超时时间内未就绪，请查看上方容器状态和日志。"
}

cmd_deploy() {
  cmd_check
  log "开始构建并启动容器。首次会下载基础镜像、依赖和 ONNX 模型。"
  if ! compose up -d --build --remove-orphans; then
    compose ps || true
    compose logs --tail=120 backend frontend ai-worker postgres || true
    die "Docker Compose 启动失败。"
  fi
  wait_until_ready
  compose ps
}

cmd_start() {
  cmd_check
  if ! compose up -d --no-build --remove-orphans; then
    compose ps || true
    die "启动失败；若镜像尚未构建，请改用 ./manage.sh deploy。"
  fi
  wait_until_ready
  compose ps
}

cmd_restart() {
  check_docker
  validate_env
  compose restart
  wait_until_ready
  compose ps
}

cmd_stop() {
  check_docker
  require_env_file
  compose down --remove-orphans
  log "容器已停止；PostgreSQL 与模型缓存卷仍保留。"
}

cmd_status() {
  check_docker
  load_runtime_values
  compose ps
  if curl --fail --silent --max-time 5 "http://127.0.0.1:${FRONTEND_PORT}/api/health" >/dev/null 2>&1; then
    log "HTTP 健康检查：正常"
  else
    warn "HTTP 健康检查：不可用"
  fi
}

cmd_logs() {
  check_docker
  require_env_file
  shift || true
  if (( $# > 0 )); then
    compose logs --tail=200 -f "$1"
  else
    compose logs --tail=200 -f
  fi
}

cmd_backup() {
  check_docker
  require_env_file
  local backup_dir backup_file
  backup_dir="$ROOT_DIR/backups"
  mkdir -p "$backup_dir"
  backup_file="$backup_dir/psych-$(date '+%Y%m%d-%H%M%S').dump"

  if ! compose exec -T postgres sh -c \
    'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' >"$backup_file"; then
    rm -f "$backup_file"
    die "数据库备份失败；确认 postgres 容器处于运行状态。"
  fi
  [[ -s "$backup_file" ]] || die "数据库备份文件为空：$backup_file"
  log "数据库备份完成：$backup_file"
}

command_name="${1:-}"
case "$command_name" in
  check) cmd_check ;;
  deploy) cmd_deploy ;;
  start) cmd_start ;;
  restart) cmd_restart ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  logs) cmd_logs "$@" ;;
  backup) cmd_backup ;;
  -h|--help|help|"") usage ;;
  *) usage; die "未知命令：$command_name" ;;
esac
