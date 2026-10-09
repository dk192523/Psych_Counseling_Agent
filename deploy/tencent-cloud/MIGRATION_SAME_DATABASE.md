# 云端 PostgreSQL 同库原地升级

适用范围：腾讯云已有多用户 PostgreSQL，在**同一数据库、同一 Compose 项目、同一 `postgres_data` named volume** 上切换应用版本。不是 PostgreSQL → 桌面 H2，不合并多个数据库，不支持两个后端 JVM 同时运行。PostgreSQL 跨大版本升级另做专门方案；当前镜像要求 PostgreSQL 16。

预检会盘点当前 Compose 的全部声明服务、挂载和顶层命名卷，并逐一检查各声明服务的运行容器实际挂载。当前明确分类为：`postgres_data` 挂载至 postgres 的 `/var/lib/postgresql/data`，是必须保全的数据库；`onnx_cache`、`djl_cache` 分别挂载至 backend 的 `/tmp/spring-ai-onnx-generative`、`/home/app/.djl.ai`，是可重新下载生成的模型缓存。分类要求服务、逻辑卷和容器目标三者匹配；仅凭卷名相同不能通过。未知命名卷（包括未引用的顶层卷）、匿名持久化卷、bind mount（含只读挂载）、额外挂载、存储驱动参数或尚不支持的 configs/secrets/volumes_from 均阻止通过，须先人工盘点，不能直接忽略。

预检另外使用 Docker 项目标签过滤，只查询已验证的当前 Compose 项目中运行容器的服务标签，以发现当前配置删除或改名后仍运行的孤儿容器。任何未声明或缺失服务标签的运行容器都会在 SQL 前阻断，包括另一个旧 backend；不会输出其容器 ID、镜像、服务标签或路径，也不会自动停止它。项目运行清单最多接受 256 条、65536 个字符，超限或元数据异常均失败关闭；已声明服务继续接受逐容器挂载核对。旧实例需由管理员确认并停止后重新预检。

这一清单不读取挂载内的文件，不检查未运行容器的实际挂载，也不能发现其他 Compose 项目、缺失当前项目标签的容器、容器可写层文件、对象存储/COS、远程数据库或代码通过网络访问的其他存储。未运行服务只覆盖 Compose 声明，其数量会单独报告；允许停止 backend/Worker/frontend 后再次预检，postgres 仍必须唯一且正在运行。升级前须另行盘点范围外存储的位置、所属用户、访问权限和应用引用关系，并完成经过校验的复制或备份恢复；预检通过不能证明这些数据已保全。不得自动把私人聊天记录或用户文件转换为共享知识库内容。

## 1. 先识别原项目和原卷

保留旧部署目录、原 `dk-ai-agent/.env`、`COMPOSE_PROJECT_NAME`、数据库名/账号配置和命名卷。不要用新包的 `.env.example` 覆盖旧 `.env`，不要把目录更名导致的默认项目名变化当成正常升级，也不要改 `.env` 数据库密码期待已有卷自动同步。绝不执行 `docker compose down -v`、`docker volume rm` 或卷清理。

将新包解压至旁边目录并验证 ZIP 的 `.sha256`，只复制旧 `.env` 到新目录，权限设为 `600`。用新目录的预检程序检查仍在运行的旧 postgres：

```bash
cd /opt/psych-release-YYYYMMDD-HHMMSS/Psych_Counseling_Agent_Server
chmod +x manage.sh
./manage.sh migration-preflight
```

预检依赖 Docker Compose v2 与 Python 3 标准库，不要求启动新容器。Compose 在内存解析现有配置；程序不直接读取 `.env` 内容、不输出配置/用户名/密码/正文。它只执行 Compose `config`、`ps`、按项目过滤的 Docker `ps`、Docker 容器/卷元数据检查，以及当前 postgres 内的 `psql` 只读查询；没有 `up`、`start`、`restart`、`down`、建表、修复、恢复或归属写入。

输出包含项目名/数据库实际卷名、固定顺序的 `mount_inventory.*` 聚合计数（声明挂载、顶层命名卷、观察到的运行容器挂载，以及服务/容器覆盖数量）、PostgreSQL/pgvector 版本、schema 指纹、关键表/列完整性、用户/会话/消息/记忆/轮次/墓碑/知识向量计数、无主/失配及孤儿数量。挂载清单只输出固定分类和数量，不输出源路径、未知卷名、目标路径、容器 ID 或解析后的环境变量值。运行容器的已知卷还需匹配 Compose 项目及逻辑卷标签；缺挂载、额外挂载或卷名变化均阻塞，失败不会进入 SQL 查询。当前应用由启动初始化器管理 schema，没有迁移版本登记表，因此 `schema_version=unversioned-initializers` 不是推测出的版本号；结构指纹只用于比较，不代替 schema 兼容性验证。数据库报告是同一 `REPEATABLE READ READ ONLY` 事务内的快照；挂载元数据按顺序观察，不能保证观察期间部署未发生变化，应在维护窗口保持编排稳定。使用只读会话默认值、查询/锁超时，最终 `ROLLBACK`。它不输出行 ID 或具体归属清单。

结构检查按当前源码 PostgreSQL DDL 核对六张业务表的完整字段集合、类型、长度、可空性和默认值（包括 BIGSERIAL 的自有序列默认值）；字段集合必须与 `COLUMN_CONTRACT` 完全一致，多余字段通过 `blocker.unexpected_columns` 聚合计数阻断。`vector_store` 的字段契约由依赖管理，不适用业务表的多余字段检查。精确核对主键列及顺序、已验证外键的源/目标列和删除行为；墓碑不允许外键。用户名唯一索引、消息幂等唯一索引和 `status='RUNNING'` 的单会话部分唯一索引必须具备正确的列、谓词及有效状态，不能靠索引名或当前数据无重复来代替。新增检查只输出固定键的聚合差异数量，不输出实际字段定义、序列名、约束名或索引名。预检还检测 public 下已存在的 `psych_schema_history`、`flyway_schema_history`，仅输出 `blocker.preexisting_migration_history_tables` 数量；即使历史表为空也阻断，必须先人工理解其迁移状态。发现历史登记对象时输出 `schema_version=unknown-preexisting-history`，不会读取或输出其中的记录。只有全部检查通过才输出 `schema_status=baseline_candidate`；它仅表示未发现上述已知历史登记对象且结构符合当前契约，**不是执行 baseline、登记迁移版本或启动应用的许可**。未发现上述对象时 `schema_version` 为 `unversioned-initializers`，这不证明不存在其他工具的历史表；预检不会创建或写入版本表。

退出码 `0` 表示本次只读快照通过；`2` 表示 `BLOCKED`。找不到唯一运行容器、标签与项目名不符、实际挂载不是预期 named volume、卷目录未初始化、用户表为空、关键表/列缺失或结构契约漂移、缺 pgvector、PostgreSQL 大版本不同、无主会话或数据完整性异常都会阻止通过。SQL/工具失败、报告缺项或意外输出同样失败关闭；原始错误被隐藏以防敏感值进入运维日志。

旧 schema 缺列/缺表或 `owner_id IS NULL`/不存在的用户引用时，先停在这里，由管理员在受控环境依据可验证的账号证据制定人工映射和单独审核的 schema 升级方案。不得把无主会话交给第一个用户、管理员或新创建账号，也不得把空表/空卷当成原库。墓碑可以引用已删除用户，因此 `info.tombstone_owner_absent` 只是统计，不要求把墓碑改归其他用户；墓碑与仍存在会话重名则阻塞。

## 2. age 加密备份与隔离恢复演练

先按 `README_DEPLOY.md` 的备份章节在独立可信恢复机生成 age 私钥，生产环境只配置公钥。旧版备份脚本如果仍产生明文 dump，应先将新包的 `manage.sh` 同步到原部署目录并留存旧脚本；不要覆盖 `.env`。在原部署目录运行：

```bash
./manage.sh backup
./manage.sh verify-backup backups/psych-YYYYMMDD-HHMMSS.dump.age
```

将 `.dump.age`、`.sha256`、`.manifest` 三件套移至受控异地存储。密文 SHA-256/age 文件头验证，甚至 `pg_restore --list`，都不能证明可以完整恢复。

在独立可信恢复机上，以新建且无业务数据的 PostgreSQL 16 + pgvector 环境做恢复演练。使用独立目录、独立项目名、独立卷和仅环回网络；不要让恢复环境连接生产 Worker、模型服务、生产域名或外部通知。以下只是对已准备好的**隔离空库**的导入示例：提前在恢复机上配置名为 `psych_restore_isolated` 的 PostgreSQL service，服务配置指定隔离目标，密码由受保护的 pgpass 文件提供；service 配置和 pgpass 文件均只允许恢复账号读取（例如权限 `600`），禁止指向生产库或非空目标。命令仅引用不含凭据的 service 名，连接密码、含凭据的 DSN 等不得写入命令参数或终端历史。

```bash
set -Eeuo pipefail
set +x
umask 077
sha256sum -c psych-YYYYMMDD-HHMMSS.dump.age.sha256
# 用受控连接方式先核实目标服务器、数据库和 public 中无任何用户对象。
# 确认空库后才执行；全流解密并通过单事务恢复，禁止 --clean。
age --decrypt -i /secure/psych-backup-identity.txt psych-YYYYMMDD-HHMMSS.dump.age \
  | pg_restore --exit-on-error --single-transaction --no-owner --no-privileges \
      --dbname='service=psych_restore_isolated'
```

确认恢复命令与完整 age 解密均成功，检查 schema、扩展、约束、聚合行数、账号与会话归属、消息顺序、记忆水位、轮次幂等键、墓碑和向量维度；在隔离环境运行新应用验证已有账号登录和跨用户隔离。保留恢复演练结果，再安排维护窗口。例子没有提供自动恢复生产库入口，也不会自动替你确认空库。

## 3. 维护窗口：停旧后启新

1. 限制外部写流量并等待在途聊天完成；停止旧 backend/Worker/frontend，保留原 postgres 在线。使用旧目录和原 `.env`，例如：

   ```bash
   cd /opt/psych-counseling-agent
   docker compose --env-file dk-ai-agent/.env -f dk-ai-agent/docker-compose.yml stop frontend backend ai-worker
   ```

2. 在已停止业务写入的条件下，再次生成最终 age 加密备份，保留三件套，并再次执行新包的 `migration-preflight`。先前演练应覆盖相同 schema/工具；最终备份也应完成隔离恢复确认，失败就保持暂停或恢复旧服务，不继续发布。`info.running_turns` 在停旧前可能非零；需确认它们确已终止，应用启动时的轮次恢复属于应用行为，预检不修改状态。
3. 核对 `.env` 保持原配置、预检报告的项目名和卷名与升级前相同。停掉其余旧容器后，再切换部署目录并从新目录启动；`./manage.sh stop` 使用不带 `-v` 的 `down`，保留命名卷。禁止新旧后端重叠运行。
4. 从新目录运行 `./manage.sh check`、`./manage.sh deploy`。确认仍使用原 named volume，再检查 readiness、已有账号登录、原会话/消息/记忆、幂等重试、删除行为和跨账号隔离。对比升级前后聚合计数时须考虑已声明的保留期任务，不把预期清理误判为迁移遗漏。
5. 保留旧程序目录、最终备份、恢复演练记录和原 `.env` 的受控副本。向量索引/模型版本变更可能触发重新初始化，先确认新版本的来源准入策略；知识向量数变化不能解释用户会话数据丢失。

预检 `READY` 不代表新应用已通过生产验收，也不覆盖备份之后的写入。当前 schema 初始化可包含写入和轮次恢复，所以必须在停旧后才启新。

## 4. 回滚和其他目标

同库升级后的 schema/数据已可能被新应用修改。不能假设把旧程序目录换回去就兼容；只有事先验证旧应用能读取升级后 schema 才能直接切回。否则保持服务停止，先保全升级后的加密备份，再按已验证方案将升级前备份恢复到独立**空库/空卷**、核对归属和数据并切换。不要对现有业务库运行 `pg_restore --clean`，不要删原卷来“重试”。

确需搬到不同服务器/数据库时，只允许整库恢复到已核实的空库，保留完整用户及其外键关系，不能把同库升级脚本当成搬库器。非空目标库合并需要另外立项：设计 ID/owner_id 映射、账号冲突、幂等键冲突、墓碑冲突、事务回滚和审计；当前预检和发布流程不承担合并。桌面 H2 是单机产品的独立存储方案，不适用于多用户云端迁移。
