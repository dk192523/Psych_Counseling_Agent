#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
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
Usage: ./manage.sh <command> [service|backup-path]

Commands:
  check             Validate Docker, .env, corpus, disk and port 3004
  deploy            Build images, start all services and wait for health
  start             Start existing images without rebuilding
  restart           Restart running services and wait for health
  stop              Stop containers; named database/model volumes are retained
  status            Show container and HTTP health status
  logs [service]    Follow the last 200 log lines (backend/frontend/ai-worker/postgres)
  backup            Stream a PostgreSQL dump into an age-encrypted backup
  verify-backup <path> Check an encrypted backup without restoring a database

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

backup_setting() {
  if [[ -f "$ENV_FILE" ]]; then
    env_value "$1"
  fi
}

check_backup_directory() {
  local create="${1:-false}" backup_dir="$ROOT_DIR/backups" root_mode root_owner
  require_command realpath
  require_command stat
  root_mode="$(stat -c '%a' -- "$ROOT_DIR")"
  root_owner="$(stat -c '%u' -- "$ROOT_DIR")"
  [[ "$root_owner" == "$EUID" || "$root_owner" == "0" ]] \
    || die "部署目录必须由当前用户或 root 所有"
  (( (8#$root_mode & 0022) == 0 )) || die "部署目录不能允许组或其他用户写入"

  [[ ! -L "$backup_dir" ]] || die "备份目录不能是符号链接"
  if [[ ! -e "$backup_dir" && "$create" == "true" ]]; then
    mkdir -m 700 -- "$backup_dir"
  fi
  [[ -d "$backup_dir" && ! -L "$backup_dir" ]] || die "备份目录不存在或不是实际目录"
  [[ "$(realpath -e -- "$backup_dir")" == "$backup_dir" ]] || die "备份目录越界"
  [[ "$(stat -c '%u' -- "$backup_dir")" == "$EUID" ]] || die "备份目录必须由当前用户所有"
  [[ "$(stat -c '%a' -- "$backup_dir")" == "700" ]] || die "备份目录权限必须为 700"
}

check_backup_file() {
  local file="$1"
  [[ ! -L "$file" && -f "$file" && -s "$file" ]] || die "备份产物缺失、为空或不是普通文件：$file"
  [[ "$(stat -c '%u' -- "$file")" == "$EUID" ]] || die "备份产物必须由当前用户所有：$file"
  [[ "$(stat -c '%a' -- "$file")" == "600" ]] || die "备份产物权限必须为 600：$file"
  [[ "$(stat -c '%h' -- "$file")" == "1" ]] || die "备份产物不能有硬链接：$file"
}

verify_cipher_backup() {
  local supplied_file="$1" backup_file backup_name checksum expected_line age_header
  check_backup_directory
  require_command sha256sum
  backup_file="$(realpath -ms -- "$supplied_file")"
  backup_name="${backup_file##*/}"
  [[ "${backup_file%/*}" == "$ROOT_DIR/backups" ]] || die "只能校验本部署 backups 目录内的文件"
  [[ "$backup_name" =~ ^psych-[0-9]{8}-[0-9]{6}\.dump\.age$ ]] || die "备份文件名不符合脚本格式"
  check_backup_file "$backup_file"
  [[ "$(realpath -e -- "$backup_file")" == "$backup_file" ]] || die "备份路径包含符号链接或越界"
  check_backup_file "$backup_file.sha256"
  check_backup_file "$backup_file.manifest"
  (( $(stat -c '%s' -- "$backup_file") >= 128 )) || die "密文过小，可能不完整"
  (( $(stat -c '%s' -- "$backup_file.sha256") <= 256 )) || die "SHA-256 sidecar 大小异常"
  (( $(stat -c '%s' -- "$backup_file.manifest") <= 4096 )) || die "备份清单大小异常"
  age_header="$(LC_ALL=C head -c 22 -- "$backup_file")"
  [[ "$age_header" == 'age-encryption.org/v1' ]] || die "文件没有预期的 age v1 密文头"
  checksum="$(sha256sum -- "$backup_file")"
  checksum="${checksum%% *}"
  expected_line="$checksum  $backup_name"
  [[ "$(cat -- "$backup_file.sha256")" == "$expected_line" ]] \
    || die "密文 SHA-256 或 sidecar 文件名校验失败"
  (( $(stat -c '%s' -- "$backup_file.sha256") == ${#expected_line} + 1 )) \
    || die "SHA-256 sidecar 必须只包含一行密文哈希和文件名"
}

prune_backups() {
  local retention_days="$1" current_file="$2" backup_file backup_name cutoff modified_at
  check_backup_directory
  cutoff=$(( $(date '+%s') - retention_days * 86400 ))
  for backup_file in "$ROOT_DIR/backups"/psych-*.dump.age; do
    [[ ! -L "$backup_file" && -f "$backup_file" && "$backup_file" != "$current_file" ]] || continue
    backup_name="${backup_file##*/}"
    [[ "$backup_name" =~ ^psych-[0-9]{8}-[0-9]{6}\.dump\.age$ ]] || continue
    modified_at="$(stat -c '%Y' -- "$backup_file")"
    (( modified_at < cutoff )) || continue
    # A missing, linked or invalid sidecar means this is not a completed backup.
    if ! (verify_cipher_backup "$backup_file"); then
      warn "保留未通过校验的旧产物，需人工检查：$backup_file"
      continue
    fi
    rm -f -- "$backup_file.manifest" "$backup_file.sha256" "$backup_file"
    log "已清理超过 ${retention_days} 天的备份：$backup_name"
  done
}

cmd_backup() (
  # Traps are confined to this subshell; plaintext only ever travels through pipes.
  local backup_dir="$ROOT_DIR/backups" backup_file backup_name recipient retention_days age_bin
  local ciphertext_tmp checksum_tmp manifest_tmp checksum ciphertext_bytes backup_complete=false file
  local -a temporary_files=() published_files=()
  cleanup_backup() {
    local status="$1" path
    trap - EXIT HUP INT TERM
    for path in "${temporary_files[@]}"; do
      rm -f -- "$path" || true
    done
    if [[ "$backup_complete" != "true" ]]; then
      for path in "${published_files[@]}"; do
        rm -f -- "$path" || true
      done
    fi
    exit "$status"
  }
  trap 'cleanup_backup "$?"' EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  require_env_file
  recipient="$(env_value BACKUP_AGE_RECIPIENT)"
  retention_days="$(env_value BACKUP_RETENTION_DAYS)"
  retention_days="${retention_days:-30}"
  age_bin="${AGE_BIN:-$(backup_setting AGE_BIN)}"
  age_bin="${age_bin:-age}"
  [[ "$recipient" =~ ^age1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{58}$ ]] \
    || die "BACKUP_AGE_RECIPIENT 必须显式配置为有效的 age X25519 公钥，不能使用占位符"
  [[ "$retention_days" =~ ^[0-9]{1,4}$ ]] || die "BACKUP_RETENTION_DAYS 必须为 1..3650 的整数"
  (( 10#$retention_days >= 1 && 10#$retention_days <= 3650 )) \
    || die "BACKUP_RETENTION_DAYS 必须为 1..3650 的整数"
  retention_days=$(( 10#$retention_days ))
  require_command "$age_bin"
  require_command sha256sum
  require_command mktemp
  # Let age validate the recipient's Bech32 checksum before touching PostgreSQL.
  "$age_bin" -r "$recipient" </dev/null >/dev/null || die "age 无法使用配置的公钥；备份已中止"
  check_backup_directory true
  check_docker
  backup_name="psych-$(date '+%Y%m%d-%H%M%S').dump.age"
  backup_file="$backup_dir/$backup_name"
  for file in "$backup_file" "$backup_file.sha256" "$backup_file.manifest"; do
    [[ ! -e "$file" && ! -L "$file" ]] || die "同秒备份文件已存在，不会覆盖：$file"
  done

  ciphertext_tmp="$(mktemp "$backup_dir/.psych-backup.XXXXXXXXXX")"
  temporary_files+=("$ciphertext_tmp")
  checksum_tmp="$(mktemp "$backup_dir/.psych-backup.XXXXXXXXXX")"
  temporary_files+=("$checksum_tmp")
  manifest_tmp="$(mktemp "$backup_dir/.psych-backup.XXXXXXXXXX")"
  temporary_files+=("$manifest_tmp")
  chmod 600 -- "$ciphertext_tmp" "$checksum_tmp" "$manifest_tmp"

  if ! compose exec -T postgres sh -c \
    'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
    | "$age_bin" -r "$recipient" -o "$ciphertext_tmp"; then
    die "数据库导出或 age 加密失败；临时产物会清理，不会退回明文备份"
  fi
  [[ -s "$ciphertext_tmp" ]] || die "加密备份文件为空"
  checksum="$(sha256sum -- "$ciphertext_tmp")"
  checksum="${checksum%% *}"
  ciphertext_bytes="$(stat -c '%s' -- "$ciphertext_tmp")"
  printf '%s  %s\n' "$checksum" "$backup_name" >"$checksum_tmp"
  {
    printf 'format=psych-postgresql-age-v1\n'
    printf 'created_at_utc=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf 'backup_file=%s\narchive_format=postgresql-custom\nencryption=age-x25519\n' "$backup_name"
    printf 'recipient=%s\nciphertext_bytes=%s\nciphertext_sha256=%s\n' "$recipient" "$ciphertext_bytes" "$checksum"
    printf 'retention_days=%s\n' "$retention_days"
  } >"$manifest_tmp"
  chmod 600 -- "$ciphertext_tmp" "$checksum_tmp" "$manifest_tmp"
  check_backup_directory

  # GNU mv -nT never replaces an existing path, including a concurrent same-second backup.
  mv -nT -- "$ciphertext_tmp" "$backup_file"
  [[ ! -e "$ciphertext_tmp" ]] || die "同秒备份文件已存在，不会覆盖"
  published_files+=("$backup_file")
  mv -nT -- "$checksum_tmp" "$backup_file.sha256"
  [[ ! -e "$checksum_tmp" ]] || die "SHA-256 sidecar 已存在，不会覆盖"
  published_files+=("$backup_file.sha256")
  mv -nT -- "$manifest_tmp" "$backup_file.manifest"
  [[ ! -e "$manifest_tmp" ]] || die "备份清单已存在，不会覆盖"
  published_files+=("$backup_file.manifest")
  verify_cipher_backup "$backup_file"
  backup_complete=true
  log "加密备份已生成并通过密文校验：$backup_file"
  prune_backups "$retention_days" "$backup_file"
)

cmd_verify_backup() {
  (( $# == 2 )) || die "用法：./manage.sh verify-backup <path>"
  local backup_file identity_file age_bin
  verify_cipher_backup "$2"
  backup_file="$(realpath -e -- "$2")"
  identity_file="${BACKUP_AGE_IDENTITY_FILE:-$(backup_setting BACKUP_AGE_IDENTITY_FILE)}"
  age_bin="${AGE_BIN:-$(backup_setting AGE_BIN)}"
  age_bin="${age_bin:-age}"
  if [[ -z "$identity_file" ]]; then
    log "密文路径、权限、大小、SHA-256 和 age 文件头校验通过；未配置私钥，未验证解密和恢复"
    return
  fi
  require_command "$age_bin"
  require_command pg_restore
  [[ ! -L "$identity_file" && -f "$identity_file" && -s "$identity_file" && -r "$identity_file" ]] \
    || die "BACKUP_AGE_IDENTITY_FILE 必须指向可读、非空、非符号链接的私钥文件"
  [[ "$(stat -c '%u' -- "$identity_file")" == "$EUID" ]] || die "私钥文件必须由当前用户所有"
  [[ "$(stat -c '%a' -- "$identity_file")" == "600" || "$(stat -c '%a' -- "$identity_file")" == "400" ]] \
    || die "私钥文件权限必须为 600 或 400"
  [[ "$(stat -c '%h' -- "$identity_file")" == "1" ]] || die "私钥文件不能有硬链接"
  if ! "$age_bin" --decrypt -i "$identity_file" "$backup_file" | {
    # Drain the decrypted stream even if pg_restore stops after reading its TOC.
    # This lets age authenticate the complete payload without writing plaintext.
    local list_status=0
    pg_restore --list >/dev/null || list_status=$?
    cat >/dev/null || exit 1
    exit "$list_status"
  }; then
    die "解密或 PostgreSQL 归档目录校验失败；未连接或恢复任何数据库"
  fi
  log "密文、完整解密流和 pg_restore --list 校验通过；未连接数据库，仍需独立恢复演练"
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
  verify-backup) cmd_verify_backup "$@" ;;
  -h|--help|help|"") usage ;;
  *) usage; die "未知命令：$command_name" ;;
esac
