# 腾讯云 OrcaTerm 部署手册（端口 3004）

> 2026-09-17：服务器示例现在默认环回绑定、Secure Cookie=true、关闭注册。下文方式 A 仅作为显式修改后的临时排障方式，正式使用按方式 B 配置 HTTPS。前后端须一起升级以支持 CSRF，详情见 ../../docs/REPAIR_REPORT.md。

这是一套源码构建型 Docker Compose 部署。宿主机只发布前端入口 `3004`；PostgreSQL、Java 后端 `8123`、Python AI Worker `8000` 都只在 Docker 内网通信。

> OrcaTerm（遨驰终端）是腾讯云网页终端和文件管理工具，不是应用商店式部署面板。下面所有命令都在 OrcaTerm 的终端中执行，压缩包从它的文件管理区上传。
> 官方参考：[OrcaTerm 文件管理](https://cloud.tencent.com/document/product/1665/84580)、[腾讯云搭建 Docker](https://cloud.tencent.com/document/product/213/46000)、[添加安全组规则](https://cloud.tencent.com/document/product/213/112614)。

## 1. 上线前必须决定访问方式

### 方式 A：直接 IP + 3004（只建议临时验收）

- `.env`：`FRONTEND_BIND_ADDRESS=0.0.0.0`
- `.env`：`FRONTEND_PORT=3004`
- `.env`：`SESSION_COOKIE_SECURE=false`
- 腾讯云安全组：TCP `3004` 只允许你当前公网 IP `/32`，不要直接放行 `0.0.0.0/0`
- 访问：`http://服务器公网IP:3004`

HTTP 不加密登录 Cookie 和咨询内容，不适合给真实用户长期使用。

### 方式 B：域名 + HTTPS（正式部署推荐）

- `.env`：`FRONTEND_BIND_ADDRESS=127.0.0.1`
- `.env`：`FRONTEND_PORT=3004`
- `.env`：`SESSION_COOKIE_SECURE=true`
- 腾讯云安全组：只公开 TCP `80/443`，不公开 `3004`
- 宿主机 Nginx 反代 `127.0.0.1:3004`
- 访问：`https://你的域名`

如果 `SESSION_COOKIE_SECURE=true` 却仍用 HTTP 访问，浏览器不会回传 Cookie，表现为登录后立即掉线。

## 2. 服务器要求

建议配置：

- Linux x86_64
- 4 核 CPU、8 GiB 内存
- 至少 20 GiB 可用磁盘
- 出站 HTTPS 可访问 Docker Hub、Maven Central、npm、PyPI、GitHub 模型文件和 DeepSeek API
- Docker Engine 与 Docker Compose v2
- `age`（加密备份必需）、Bash 与 GNU coreutils（提供 `realpath`、`stat`、`sha256sum`、`mktemp`、`mv -nT`）

2 核 4 GiB 可能运行，但在首次同时构建 Java、Node、Python 镜像时更容易内存不足。服务器构建失败时优先升级内存或增加 swap，而不是反复重试。

腾讯云轻量应用服务器可直接选择 Docker CE 应用模板；已有 Ubuntu 服务器也可自行安装 Docker。服务器不需要 Docker Desktop。

## 3. 安全组规则

不要开放 `5432`、`8000`、`8123`，Compose 没有把它们发布到宿主机。

直接 IP 验收：

| 协议 | 端口 | 来源 | 用途 |
| --- | ---: | --- | --- |
| TCP | 22 | 你的公网 IP/32 | SSH / OrcaTerm |
| TCP | 3004 | 你的公网 IP/32 | 临时网页访问 |

HTTPS 正式部署：

| 协议 | 端口 | 来源 | 用途 |
| --- | ---: | --- | --- |
| TCP | 22 | 你的公网 IP/32 | SSH / OrcaTerm |
| TCP | 80 | 0.0.0.0/0 | 证书签发与 HTTP 跳转 |
| TCP | 443 | 0.0.0.0/0 | HTTPS 网页访问 |

## 4. 用 OrcaTerm 上传并校验压缩包

在腾讯云控制台打开目标 CVM/轻量服务器的 OrcaTerm，进入文件管理，上传这两个文件到同一目录：

```text
Psych_Counseling_Agent_TencentCloud_3004_*.zip
Psych_Counseling_Agent_TencentCloud_3004_*.zip.sha256
```

在终端进入上传目录并校验：

```bash
cd ~
PACKAGE='Psych_Counseling_Agent_TencentCloud_3004_YYYYMMDD-HHMMSS.zip'
test -f "$PACKAGE" && test -f "$PACKAGE.sha256" || {
  echo "找不到部署包或校验文件，请检查 PACKAGE 的完整文件名"
  exit 1
}
sha256sum -c "$PACKAGE.sha256"
```

把 `PACKAGE` 的值替换为本次上传 ZIP 的完整文件名，不要使用 `*.zip` 通配符。服务器保留多个版本时，通配符可能选中旧包。

结果必须显示 `OK`。校验失败就重新上传，不要部署损坏的包。

首次安装：

```bash
(
set -Eeuo pipefail
if sudo test -e /opt/psych-counseling-agent; then
  echo "/opt/psych-counseling-agent 已存在；请使用第 10 节的安全升级流程"
  exit 1
fi
INSTALL_TMP="$(mktemp -d)"
unzip -q "$PACKAGE" -d "$INSTALL_TMP"
sudo mv "$INSTALL_TMP/Psych_Counseling_Agent_Server" /opt/psych-counseling-agent
rmdir "$INSTALL_TMP"
sudo chown -R "$USER":"$USER" /opt/psych-counseling-agent
cd /opt/psych-counseling-agent
chmod +x manage.sh
)
```

如果 `unzip` 不存在，Ubuntu 执行 `sudo apt-get install -y unzip`。

## 5. 安装或检查 Docker

先检查：

```bash
docker version
docker compose version
```

如果使用腾讯云 Docker CE 应用模板，这两项通常已经存在。已有 Ubuntu 服务器建议按上面的腾讯云官方 Docker 文档添加 Docker CE 软件源，然后安装：

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl unzip openssl age
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://mirrors.cloud.tencent.com/docker-ce/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://mirrors.cloud.tencent.com/docker-ce/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
```

执行 `usermod` 后退出并重新连接 OrcaTerm，使用户组生效。TencentOS、Debian 等系统的软件源命令不同，直接使用腾讯云官方文档对应章节；不要退回旧的 Python 版 `docker-compose`。

Docker daemon 报错时：

```bash
sudo systemctl status docker --no-pager
sudo systemctl start docker
docker info
```

已有 Docker 的 Ubuntu 服务器也需要单独安装 `age`：`sudo apt-get update && sudo apt-get install -y age`。其他发行版使用其软件源中的 `age` 包，或按 [age 官方安装说明](https://github.com/FiloSottile/age#installation)安装；不要使用来源不明的二进制。`age --version` 应正常输出版本。可以在 `.env` 中设置 `AGE_BIN=/绝对路径/age`，或用同名环境变量覆盖；该值只能是一个可执行文件名或路径，不能附带参数。

## 6. 配置生产环境变量

复制模板：

```bash
cd /opt/psych-counseling-agent
cp dk-ai-agent/.env.example dk-ai-agent/.env
chmod 600 dk-ai-agent/.env
```

生成三个不同的随机值，分别用于数据库、Java/Python 内部鉴权、管理员初始密码：

```bash
openssl rand -hex 24
openssl rand -hex 32
openssl rand -hex 24
```

编辑配置：

```bash
nano dk-ai-agent/.env
```

至少替换：

```dotenv
POSTGRES_PASSWORD=第一段随机值
AI_WORKER_SHARED_SECRET=第二段随机值
ADMIN_INITIAL_PASSWORD=第三段随机值
DEEPSEEK_API_KEY=你的真实DeepSeekKey
```

部署拓扑必须保持单副本：

```dotenv
APP_DEPLOYMENT_MODE=single
APP_REPLICA_COUNT=1
APP_INSTANCE_ID=
```

`DeploymentGuard` 在 Spring Bean 初始化前检查部署声明，仅接受 `single` 和 `1`；非法值或显式空值会拒绝启动。Compose 如需给未设置的变量提供默认值，应使用 `${APP_DEPLOYMENT_MODE-single}` 和 `${APP_REPLICA_COUNT-1}`，不要使用 `:-`，否则显式空值会被静默替换。`APP_INSTANCE_ID` 可留空，仅用于观测，不是分布式锁，也不能证明只有一个实例在线。

禁止同时运行两个后端 JVM，包括新旧版本重叠的滚动发布。升级必须先停止旧实例，确认其已退出后再启动新实例，并接受切换期间的短暂停机；按第 10 节流程执行。

直接 IP 模式保留：

```dotenv
FRONTEND_PORT=3004
FRONTEND_BIND_ADDRESS=0.0.0.0
SESSION_COOKIE_SECURE=false
```

HTTPS 模式改为：

```dotenv
FRONTEND_PORT=3004
FRONTEND_BIND_ADDRESS=127.0.0.1
SESSION_COOKIE_SECURE=true
```

注意：

- 不要把 `.env` 发给别人，也不要提交进 Git。
- 以前在聊天、截图或日志中暴露过的 API Key 应先在服务商后台轮换，再部署新 Key。
- `ADMIN_INITIAL_PASSWORD` 在数据库尚无管理员时必须配置，生产和开发首次启动均适用；请替换模板占位值，使用至少 8 位且 UTF-8 不超过 72 字节的强口令。未设置、为空、仅含空白或不合规时，后端拒绝启动，不创建账号。
- 日志仅记录管理员创建成功及口令来源，不输出初始口令或哈希。已有管理员时无需配置该变量，修改该变量也不会重置已有密码。
- `.env` 的 `COMPOSE_PROJECT_NAME=psych-counseling-agent` 不要在升级时修改，否则 Compose 会创建另一组空数据卷。

## 7. 检查并启动

```bash
cd /opt/psych-counseling-agent
./manage.sh check
./manage.sh deploy
```

`check` 会检查：

- Docker daemon 与 Compose v2
- 必填密钥是否仍是占位符
- 逐字稿目录是否存在
- 磁盘空间
- `3004` 是否被其他进程占用
- Compose 配置能否解析

它不会自动杀死占用端口的进程。首次 `deploy` 会下载基础镜像、Maven/npm/Python 依赖和 ONNX 模型，并初始化向量数据，耗时取决于服务器网络。Java 容器的 readiness 会等当前语料版本完成向量灌注后才放行前端，因此脚本显示“服务已就绪”时知识库已经可用。

查看状态和日志：

```bash
./manage.sh status
./manage.sh logs backend
./manage.sh logs ai-worker
```

本机健康检查：

```bash
curl -i http://127.0.0.1:3004/api/health
```

直接 IP 模式访问：

```text
http://服务器公网IP:3004
```

首次创建的管理员用户名是 `admin`，密码是 `.env` 中设置的 `ADMIN_INITIAL_PASSWORD`。请妥善保管并在首次登录后修改；已有管理员应使用其当前密码，启动日志不会提供密码。

## 8. 配置域名与 HTTPS

先把域名 A 记录指向服务器公网 IP，并确认安全组已开放 `80/443`。Ubuntu 安装 Nginx 和 Certbot：

```bash
sudo apt-get update
sudo apt-get install -y nginx certbot python3-certbot-nginx
```

复制站点模板并替换域名：

```bash
sudo cp /opt/psych-counseling-agent/nginx-site.conf.example /etc/nginx/sites-available/psych-counseling-agent
sudo nano /etc/nginx/sites-available/psych-counseling-agent
sudo ln -s /etc/nginx/sites-available/psych-counseling-agent /etc/nginx/sites-enabled/psych-counseling-agent
sudo nginx -t
sudo systemctl reload nginx
```

签发并自动启用 HTTPS 跳转：

```bash
sudo certbot --nginx --redirect -d 你的域名
sudo nginx -t
sudo systemctl reload nginx
```

确认 `.env` 已使用 `127.0.0.1` 和 `SESSION_COOKIE_SECURE=true`，然后让配置生效：

```bash
cd /opt/psych-counseling-agent
./manage.sh deploy
curl -I https://你的域名
```

TencentOS/RHEL 系发行版若没有 `/etc/nginx/sites-available`，把模板放到 `/etc/nginx/conf.d/psych-counseling-agent.conf`，其余 Nginx 配置不变。

## 9. 日常运维

```bash
# 状态
./manage.sh status

# 跟随全部日志
./manage.sh logs

# 只看 Java 后端
./manage.sh logs backend

# 重启，不重建镜像
./manage.sh restart

# 停止容器，保留数据库和模型缓存卷
./manage.sh stop

# 启动已构建镜像
./manage.sh start

# 重新构建并发布当前源码
./manage.sh deploy

# 数据库备份（先按下文配置 age 公钥）
./manage.sh backup
```

绝对不要执行：

```bash
docker compose down -v
```

`-v` 会删除 PostgreSQL 命名卷，历史账号、会话和向量数据都会丢失。

### 9.1 配置公钥加密备份

在**独立、可信的恢复机器**安装 `age`，生成备份身份。以下命令只在该机器执行，不能在生产服务器生成或存放私钥：

```bash
umask 077
age-keygen -o psych-backup-identity.txt
chmod 600 psych-backup-identity.txt
age-keygen -y psych-backup-identity.txt
```

最后一行输出 `age1...` 形式的**公钥 recipient**，只把该公钥复制到生产服务器 `dk-ai-agent/.env`：

```dotenv
BACKUP_AGE_RECIPIENT=这里替换为完整的age1公钥
BACKUP_RETENTION_DAYS=30
AGE_BIN=age
BACKUP_AGE_IDENTITY_FILE=
```

`BACKUP_RETENTION_DAYS` 默认 30，允许 1..3650。公钥必须显式配置为 `age-keygen` 生成的 X25519 recipient，不支持口令加密、SSH recipient 或多个 recipient。空值、示例占位符、公钥格式或校验和错误、缺少 `age` 都会让备份失败，绝不退回明文。配置值不加引号、不附加行尾注释。

`psych-backup-identity.txt` 含解密私钥，应保存在独立的加密介质或受控托管系统，不能上传生产服务器、提交 Git、放入发布包或贴到聊天和日志里。公钥用于加密，可以留在服务器；私钥用于恢复，丢失后无法解密已有备份。实际托管位置、授权人和应急取回流程仍需运维人员落实。

### 9.2 备份产物、保留期和轮换

`./manage.sh backup` 把容器内 `pg_dump -Fc` 的输出直接通过管道交给 `age`。明文不会写入宿主机文件；密文临时文件位于同一备份目录，成功后原子改名。失败或收到可捕获的退出信号时清理临时文件；同秒已有同名文件时直接失败，不覆盖旧备份。`SIGKILL`、掉电等无法捕获的中断可能留下 `.psych-backup.*` 密文临时文件或不完整产物，应先检查有无备份任务运行，再人工处置。

每次完成后会产生三个文件：

```text
/opt/psych-counseling-agent/backups/psych-YYYYMMDD-HHMMSS.dump.age
/opt/psych-counseling-agent/backups/psych-YYYYMMDD-HHMMSS.dump.age.sha256
/opt/psych-counseling-agent/backups/psych-YYYYMMDD-HHMMSS.dump.age.manifest
```

目录由脚本以 `700` 创建，三个文件权限均为 `600`，且必须由当前执行用户所有。已有备份目录权限不合规会拒绝执行；不要把 `backups` 改成符号链接。部署根目录必须由当前用户或 root 所有，且不能允许组或其他用户写入。`.sha256` 只含密文 SHA-256 和文件名；`.manifest` 只含时间、格式、公钥 recipient、密文大小、密文哈希和保留期等元数据，不含咨询正文、数据库口令或私钥。

只有新产物全部生成并通过路径、权限、大小、密文哈希和 age 文件头检查后，脚本才清理同目录内超过保留天数的旧备份（按密文文件修改时间计算）。清理仅匹配 `psych-YYYYMMDD-HHMMSS.dump.age`，同时删除对应 sidecar，不跟随符号链接。不完整或未通过校验的旧产物会保留并提示人工检查；失败的备份不会触发清理。此前产生的明文 `.dump`、其他前缀文件及其他发布目录内的备份不会自动删除，需要单独清点、迁移和安全处置。

轮换时，在恢复机器生成新的私钥及公钥，替换服务器的 `BACKUP_AGE_RECIPIENT` 后运行备份并验证新产物。旧备份仍需要旧私钥；按清单中的 `recipient` 确认对应关系，直到所有依赖旧私钥的本地、异地及归档备份都完成迁移或到期，才能退役旧私钥。不要直接覆盖唯一的旧私钥文件。

### 9.3 校验和恢复演练

服务器上不放私钥，日常执行密文检查：

```bash
cd /opt/psych-counseling-agent
./manage.sh verify-backup backups/psych-YYYYMMDD-HHMMSS.dump.age
```

将示例文件名替换为真实名称。命令只接受**当前 `manage.sh` 所在目录的 `backups` 子目录**中的受控文件，检查文件权限、非空及合理大小、SHA-256 sidecar 和 age v1 文件头；不要求 Docker 运行，也不连接数据库。SHA-256 只检测损坏，不能证明来源可信，也不能替代解密检查或恢复演练。

需要进一步检查时，把 `manage.sh` 和同一份备份的三个文件复制到隔离的恢复机器，保持 `manage.sh` 与 `backups/` 相邻，目录权限为 `700`、产物为 `600`、所有者为执行用户。安装 `age` 和支持该归档格式的 PostgreSQL 客户端工具 `pg_restore`，在该机器执行：

```bash
BACKUP_AGE_IDENTITY_FILE=/安全介质/psych-backup-identity.txt \
  ./manage.sh verify-backup backups/psych-YYYYMMDD-HHMMSS.dump.age
```

私钥文件必须是当前用户拥有的普通文件，权限为 `600` 或 `400`。此时脚本将解密流送给 `pg_restore --list`，并读完解密流以完成 age 完整性校验；不落地明文、不连接正式数据库、不恢复任何数据。指定私钥后，缺少 `age`、`pg_restore` 或私钥不合法会报错，不会静默降级为只检查密文。未指定私钥时只验证密文。归档目录可读取仍不等于数据和应用已经可恢复：必须另行在独立测试数据库完成恢复演练、核对数据与应用行为，避免把生产数据库当演练目标。

备份完成后还应将三个文件一起复制到腾讯云 COS 或另一台机器，并在目的地重新校验。**真实私钥托管、异地备份自动化、云盘/快照加密及独立恢复演练仍是待办**，本脚本只实现本地备份加密、校验和保留期。只留在同一块云硬盘上不能防磁盘故障。

## 10. 安全升级流程

以下流程保留旧目录和固定 Compose 数据卷，不覆盖当前 `.env`。执行前先完成第 9.1 节的 `age` 安装与公钥配置；从旧版升级时须将新备份脚本同步到当前部署目录，避免旧版 `backup` 仍生成明文文件。旧脚本可以另行留档，完成切换后运行下面流程：

```bash
(
set -Eeuo pipefail
cd ~
PACKAGE='Psych_Counseling_Agent_TencentCloud_3004_YYYYMMDD-HHMMSS.zip'
test -f "$PACKAGE" && test -f "$PACKAGE.sha256" || {
  echo "找不到部署包或校验文件，请检查 PACKAGE 的完整文件名"
  exit 1
}
sha256sum -c "$PACKAGE.sha256"

RELEASE_DIR="/opt/psych-release-$(date +%Y%m%d-%H%M%S)"
sudo mkdir -p "$RELEASE_DIR"
sudo unzip -q "$PACKAGE" -d "$RELEASE_DIR"
sudo chown -R "$USER":"$USER" "$RELEASE_DIR"

NEW_DIR="$RELEASE_DIR/Psych_Counseling_Agent_Server"
test -f "$NEW_DIR/manage.sh"
test -f "$NEW_DIR/dk-ai-agent/docker-compose.yml"
test -d "$NEW_DIR/counseling-kb/raw"
cp /opt/psych-counseling-agent/dk-ai-agent/.env "$NEW_DIR/dk-ai-agent/.env"
chmod 600 "$NEW_DIR/dk-ai-agent/.env"
chmod +x "$NEW_DIR/manage.sh"
bash -n "$NEW_DIR/manage.sh"

# 先用新版本脚本完成配置、语料、磁盘和端口检查；此时旧服务仍在线。
cd "$NEW_DIR"
./manage.sh check

cd /opt/psych-counseling-agent
./manage.sh backup
./manage.sh stop

PREVIOUS_DIR="/opt/psych-counseling-agent.previous-$(date +%Y%m%d-%H%M%S)"
echo "旧版本目录将保留为：$PREVIOUS_DIR"
sudo mv /opt/psych-counseling-agent "$PREVIOUS_DIR"
sudo mv "$NEW_DIR" /opt/psych-counseling-agent
sudo chown -R "$USER":"$USER" /opt/psych-counseling-agent

cd /opt/psych-counseling-agent
./manage.sh check
./manage.sh deploy
)
```

子 Shell 开启了失败即停：校验、解压、预检查或备份失败时，不会继续停旧服务。若目录切换后的 `deploy` 失败，按命令输出复制旧版本的完整路径并回滚：

```bash
(
set -Eeuo pipefail
PREVIOUS_DIR='/opt/psych-counseling-agent.previous-YYYYMMDD-HHMMSS'
FAILED_DIR="/opt/psych-counseling-agent.failed-$(date +%Y%m%d-%H%M%S)"
test -d "$PREVIOUS_DIR"
(cd /opt/psych-counseling-agent && ./manage.sh stop) || true
sudo mv /opt/psych-counseling-agent "$FAILED_DIR"
sudo mv "$PREVIOUS_DIR" /opt/psych-counseling-agent
sudo chown -R "$USER":"$USER" /opt/psych-counseling-agent
cd /opt/psych-counseling-agent
./manage.sh deploy
)
```

新版本确认稳定后再人工清理旧目录。备份随旧目录一并保留，当前目录的保留期清理不会跨到旧目录；清理旧目录前先把所需备份的三个文件迁移到受控异地存储并完成校验。不要清理名为 `psych-counseling-agent_*` 的 Docker volumes。

## 11. 常见故障

### `3004` 被占用

```bash
sudo ss -ltnp 'sport = :3004'
docker ps --format 'table {{.ID}}\t{{.Names}}\t{{.Ports}}'
```

确认进程归属后再决定停哪个服务。不要直接复制一条 `kill -9` 命令盲杀。

### 后端一直 unhealthy

```bash
./manage.sh status
./manage.sh logs backend
./manage.sh logs postgres
docker system df
free -h
df -h
```

常见原因是数据库密码与旧卷不一致、磁盘不足、内存不足、ONNX 模型下载失败或 DeepSeek Key 无效。PostgreSQL 命名卷初始化后，单独修改 `.env` 中的数据库密码不会自动修改卷内密码；这种情况应恢复原密码或在数据库内正规改密，不能用删卷解决。

### OrcaTerm 上传失败

先确认磁盘空间和当前目录权限。源码包较小，可重新上传；始终用随包 `.sha256` 文件校验后再解压。

### HTTPS 后登录循环

检查：

```dotenv
FRONTEND_BIND_ADDRESS=127.0.0.1
SESSION_COOKIE_SECURE=true
```

然后确认访问地址确实是 `https://`，并执行 `./manage.sh deploy` 让容器读取新环境变量。

## 12. 当前上线边界

- 当前仅支持一个后端 JVM、单副本部署，禁止扩容及新旧实例重叠的滚动发布。`DeploymentGuard` 只校验本进程的部署声明，两个都声明 `single`、副本数 `1` 的 JVM 无法互相发现；运维仍须确保旧实例退出后才启动新实例。
- 真正支持多副本仍需共享 Session 与吊销状态、全局限流、turn lease/fencing，以及记忆和向量更新协调；`APP_INSTANCE_ID` 只是观测标识，不能替代这些机制。目前未支持多副本。
- 主页面聊天已用 `POST + SSE`，咨询正文不会进入 URL；旧 GET 入口仅为兼容保留，内层 Nginx 已关闭该路径访问日志。
- 用户注册仍对外开放，虽有后端按 IP 限流，公开推广前仍应关注模型额度和异常账号。
- CSRF token 已完成全链路接线：前端先通过 `GET /api/auth/csrf` 获取 token，后续状态变更请求携带 `X-XSRF-TOKEN`；同源反代和显式 Origin 白名单仍是部署约束，不要把 API 单独开放为跨域公共服务。这不等于已完成完整公网安全验收。
- 直接 HTTP 只适合受限 IP 验收；真实咨询数据必须走 HTTPS。
- 这个包是源码构建包，不是完全离线镜像包。首次部署必须具备外网依赖下载能力。
- 包内不含 `.env`、API Key、本地日志、IDE 配置、`target`、`node_modules`、Python 虚拟环境或测试缓存。
