# 服务器全量部署手册：zip 老版本 → git 部署（保留用户数据）

> 2026-11-03。适用场景：服务器已有 zip 包安装的老版本（`/opt/psych-counseling-agent`）
> 与真实用户数据（命名卷 `psych-counseling-agent_postgres_data`），要切换到 git clone
> 部署形态并升级到最新代码。此后日常更新只需 `./deploy/tencent-cloud/manage.sh update`。
>
> **数据保留的三根支柱**：①`COMPOSE_PROJECT_NAME` 不变（复用同一命名卷）；
> ②`.env` 原样复制（gitignore 内，git 永不触碰）；③绝不执行 `docker compose down -v`。

## 0. 前置检查（5 分钟）

```bash
# 服务器装 git（已装则跳过）
sudo apt update && sudo apt install -y git
docker compose version    # 需 v2 插件
# 记录升级前数据基线（升级后核对）
cd /opt/psych-counseling-agent && ./manage.sh status
docker exec $(docker ps -qf name=postgres) psql -U postgres -d dk_ai_db -t \
  -c "SELECT 'users='||COUNT(*) FROM psych_user" \
  -c "SELECT 'conversations='||COUNT(*) FROM psych_conversation" \
  -c "SELECT 'messages='||COUNT(*) FROM psych_chat_message"
```

仓库为公开仓库，`git clone https://...` 无需凭据。

## 1. 数据保护三步（必做，不动老服务）

```bash
sudo git clone https://github.com/dk192523/Psych_Counseling_Agent.git /opt/psych-src

# 复制老 .env —— 唯一的配置迁移动作
sudo cp /opt/psych-counseling-agent/dk-ai-agent/.env /opt/psych-src/dk-ai-agent/.env

# 【关键】核对三件事：
sudo grep COMPOSE_PROJECT_NAME /opt/psych-src/dk-ai-agent/.env
#   必须仍是 psych-counseling-agent（改了=丢数据，改回！）
sudo grep AI_WORKER_SHARED_SECRET /opt/psych-src/dk-ai-agent/.env   # 非空
#   新备份机制强制要求 age 公钥（老 .env 可能没有）——生成并追加：
grep -q BACKUP_AGE_RECIPIENT /opt/psych-src/dk-ai-agent/.env || {
  age-keygen -o /root/backup-key.txt   # 私钥留在服务器外的地方！见第 5 节
  echo "BACKUP_AGE_RECIPIENT=$(grep -o 'age1[a-z0-9]*' /root/backup-key.txt)" \
    | sudo tee -a /opt/psych-src/dk-ai-agent/.env
}

# 只读预检（对老库；要求老 postgres 在线）
cd /opt/psych-src && ./deploy/tencent-cloud/manage.sh migration-preflight

# age 加密备份 + 校验
sudo apt install -y age   # 若未装
./deploy/tencent-cloud/manage.sh backup
./deploy/tencent-cloud/manage.sh verify-backup backups/<刚生成的>.dump.age
```

预检 BLOCKED（退出码 2）说明 schema 与新代码预期不符——**停止，勿继续**，
按 MIGRATION_SAME_DATABASE.md 人工核对后再来。

## 1b. 模型引导（一次性，必做）

新版本 backend 从本地文件加载 embedding 模型（`onnx_model` 卷内
`multilingual-e5-small/model.onnx`，470MB）——首次部署前放入一次：

```bash
# 服务器上下载（hf-mirror 对国内更快；能直连 HF 就用官方域名）
wget -O /tmp/model.onnx   https://hf-mirror.com/intfloat/multilingual-e5-small/resolve/main/onnx/model.onnx

# 先创建卷（由 compose 定义），再放入文件
cd /opt/psych-src/dk-ai-agent
docker compose --env-file .env up -d postgres        # 让 compose 项目建立（仅 postgres）
docker run --rm -v psych-counseling-agent_onnx_model:/target   -v /tmp:/src:ro alpine sh -c   "mkdir -p /target/multilingual-e5-small && cp /src/model.onnx /target/multilingual-e5-small/"
rm /tmp/model.onnx
```

> 跳过此步 backend 会因找不到模型文件而启动失败（报 file:/opt/models/... 不存在）。
> 放好之后的所有重启/更新都不再需要任何外网。

## 2. 换版（维护窗口，约 10-15 分钟）

```bash
# 停老栈（业务+postgres 全停，数据卷保留）
cd /opt/psych-counseling-agent && sudo ./manage.sh stop

# 新栈首次部署（会构建三镜像；模型已在 1b 步放入卷内）
cd /opt/psych-src && sudo ./deploy/tencent-cloud/manage.sh deploy
```

**预期长等待**（非故障）：向量库全量重灌（851 文档）约 5-10 分钟，
期间 readiness 拒绝流量，`manage.sh` 会自动等到健康。

## 3. 升级验收

```bash
./deploy/tencent-cloud/manage.sh status      # 四容器 healthy
# 数据核对：三行数字必须与第 0 节基线一致
docker exec $(docker ps -qf name=postgres) psql -U postgres -d dk_ai_db -t \
  -c "SELECT 'users='||COUNT(*) FROM psych_user" \
  -c "SELECT 'conversations='||COUNT(*) FROM psych_conversation" \
  -c "SELECT 'messages='||COUNT(*) FROM psych_chat_message"
```

浏览器验收：登录（新 CSRF 链路，老登录页强刷一次 Ctrl+F5）→ 历史会话完整 →
发一条消息（流式正常）→ 危机冒烟：发"我决定了，今晚就自杀"应**秒回**含
12356/120 资源的专用模板（不经过 LLM）。

## 4. 日常更新（从此一条命令）

```bash
cd /opt/psych-src && sudo ./deploy/tencent-cloud/manage.sh update
# 可选参数：--ref <tag|commit> 指定版本；--skip-backup 跳过备份（不建议）
# 回滚上一次更新：sudo ./deploy/tencent-cloud/manage.sh rollback
```

update 语义：fetch 比对 → 无更新直接退出 → age 备份 → 只读预检（任一失败都
**不动服务**）→ 停业务容器（postgres 与数据卷在线不动）→ 切版本 → 重建 →
健康等待；部署失败自动回退到更新前提交。

## 5. 回滚与善后

- **换版失败回滚**：`cd /opt/psych-src && sudo ./deploy/tencent-cloud/manage.sh rollback`；
  或回到老栈：`cd /opt/psych-counseling-agent && sudo ./manage.sh start`（卷未动，无损）。
- **age 私钥保管**：`/root/backup-key.txt` 中的私钥是备份唯一的解密钥匙——复制到
  服务器之外的密码管理器/离线介质后从服务器删除公钥对文件保留即可（公钥在 .env 无害）。
- 老目录 `/opt/psych-counseling-agent` **保留 2-4 周**（终极回滚保险），确认稳定后归档删除。
- 定时备份：`sudo crontab -e` 加 `0 4 * * * cd /opt/psych-src && ./deploy/tencent-cloud/manage.sh backup`。

## 常见问题

- **`update` 提示"未发现 .git"**：当前还在 zip 目录跑——到 `/opt/psych-src` 下执行。
- **登录 403 CSRF_INVALID**：浏览器缓存了旧前端，Ctrl+F5 强刷；仍失败说明前后端
  版本不一致（老栈没停干净），`docker ps` 检查是否有两套容器并存。
- **update 中途断网/中断**：`.last-update` 只在成功部署后用于 rollback；中断后重跑
  `update` 即可（幂等：fetch 比对从头再来）。
