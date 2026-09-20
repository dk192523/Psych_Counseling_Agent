# 远程上线清单（REMOTE GO-LIVE）

> 2026-09-20 整理。本地已闭环的能力不再列 here——本文只覆盖**必须借助生产服务器/域名/云服务**的事项。
> 配套升级文档 §12（部署约束）与 §13（备份恢复 SOP）。

## 1. 正式 TLS（上线的第一优先事项）

当前 HTTP 部署下 Cookie 带 `Secure` 属性依赖浏览器的 localhost 豁免；正式域名上必须 HTTPS：

```bash
# 服务器上（域名已解析到服务器 IP）
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com        # 自动改 nginx 配置并配 80→443 跳转
# 或使用包内 nginx-site.conf.example 手工配 443 后再签
```

验收：`curl -sI https://your-domain.com` 返回 200 且响应头含 `Strict-Transport-Security`（应用层
已输出 HSTS，TLS 下自动生效）；登录后浏览器地址栏无"不安全"标记。

## 2. 生产部署验收单（每次换版过一遍）

- [ ] `sha256sum -c *.zip.sha256` 校验包完整
- [ ] 复制旧 `.env`（**勿覆盖**，见升级文档 §12 第 1 条：CSRF 要求前后端同版本部署）
- [ ] `./manage.sh backup`（age 加密，成功才算继续）
- [ ] `./manage.sh deploy`（整包换版 + clean 构建）
- [ ] `.env` 确认：`ADMIN_INITIAL_PASSWORD` 已设（新库首次启动强制要求）、
      `SESSION_COOKIE_SECURE=true`（TLS 后）、`AI_WORKER_SHARED_SECRET` 非空
- [ ] 冒烟：登录 → 发一条消息（流式正常）→ 发"我决定了，今晚就自杀"（危机模板秒回含 12356）
      → 历史会话完整 → `./manage.sh verify-backup` 最新备份可验
- [ ] 回滚预案：停新目录 → 旧目录 `./manage.sh start`（`psych_chat_turn` 为增量表，回滚无损）

## 3. 数据加密与密钥管理（生产决策项）

| 决策点 | 选项 | 建议 |
|---|---|---|
| 数据卷加密 | 云盘加密（腾讯云 CBS 加密）/ LUKS | 云盘加密最省事，快照自动继承 |
| 列级加密 | pgcrypto | **暂不建议**：ILIKE 召回与 pgvector 检索都会失效，代价大于收益（单用户自用场景） |
| 备份密钥 | age 私钥保存位置 | 私钥**不放在服务器上**（加密备份的意义），存本地密码管理器/离线介质 |
| 密钥轮换 | 定期换 `BACKUP_AGE_RECIPIENT` | 换公钥后新旧备份并存，旧备份到期自动轮换清理 |
| KMS | 腾讯云 KMS 托管 | 规模到了再说；当前 age + 云盘加密已覆盖威胁模型 |

## 4. 多副本架构决策框架（何时才需要）

当前 `DeploymentGuard` 强制单副本（`APP_DEPLOYMENT_MODE=single`）。出现以下信号才考虑：

- 单实例 CPU 在高峰持续 >80% 或 SSE 并发接近 Hikari/tomcat 上限；
- 需要零停机发布（当前换版有 ~1 分钟窗口）。

届时需按 `docs/IMPROVEMENT_BACKLOG.md` §6 检查单逐项改造（共享 Session/全局限流/生成租约
fencing/Redis），**不要直接改 `APP_REPLICA_COUNT`**——门禁会拒绝启动，这是故意的。

## 5. 真实环境监控（上线后补）

- 腾讯云云监控告警：服务器 CPU/内存/磁盘 + 容器 `dabing-psych-agent-backend-1` 存活；
- 日志：`docker logs` 或接 CLS；关键字 `tier=IMMINENT`（危机审计 WARN）建议配告警；
- 备份定时：cron `0 4 * * *` 跑 `./manage.sh backup` + 每周一次 `verify-backup` 演练；
- LLM 成本：DeepSeek 控制台看用量；本地无 usage 统计（backlog 项）。
