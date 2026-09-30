# 知识来源治理

`counseling-kb/manifests/sources.jsonl` 每行登记一个来源，字段契约见同目录 `manifest.schema.json`。manifest 提供来源登记与离线校验，目前未接入运行时准入门禁；Java/Python 已另行实现知识域路由、来源元数据传递与检索边界等行为。manifest 校验通过不表示线上已按登记状态执行准入控制。

## 字段

| 字段 | 含义 |
| --- | --- |
| `id`, `title` | 稳定且唯一的来源标识、名称 |
| `domain` | `knowledge`、`cases/summaries`、`cases/transcripts`、`policy`、`prompt-references`、`exam/practice` 六选一 |
| `use` | `production` 生产、`evaluation` 评测、`reference` 参考、`quarantine` 待核验隔离 |
| `path`, `runtime_path`, `source_url` | 至少一个；`path` 相对 `counseling-kb`；`runtime_path` 相对仓库根目录且仅允许 `dk-ai-agent/src/main/resources/` 内具体文件；路径使用 `/`；外部 URL 仅登记，不自动抓取 |
| `license`, `license_status` | 授权名称或说明，以及 `unknown/approved/restricted/rejected` 状态 |
| `license_evidence` | 授权核验凭据位置或可追溯说明；生产必填 |
| `pii_status` | `unknown/contains_pii/redacted/no_pii`；登记状态不替代内容审查 |
| `review_status` | `pending/approved/rejected` |
| `reviewed_by`, `reviewed_at` | 审核人及 `YYYY-MM-DD` 审核日期；生产必填 |
| `notes` | 来源背景、已知限制与待处理事项，不填写真实个人敏感信息 |

## 准入和隔离

生产仅允许知识、脱敏案例摘要和审核后的政策材料。必须授权明确且 `license_status=approved`、`review_status=approved`，PII 状态为 `redacted` 或 `no_pii`，并留下授权凭据、审核人和日期。公开可访问不等于有权复制、训练或用于生产；授权审核需要覆盖实际用途。案例还需人工检查再识别风险，不能只删姓名。

生产本地条目必须指向对应域内的具体文件；目录条目只允许作为 `quarantine` 盘点，不能一次批准整个可变目录。转写、提示词参考、考试练习不得通过此清单进入生产检索。评测材料单独登记、单独使用，同一路径或同一 URL 不得同时登记为生产和评测；文件复制和改写产生的隐性泄漏仍需人工检查。`evaluation/reference` 也不是授权或隐私豁免，需要按实际使用范围完成核验。

新增六个域目录只提供组织结构。当前 `raw/`、`cases.json`、`document/` 均保留原位；清单将已有原始材料、聚合案例和各文档登记为 `quarantine`，授权未知、PII 未核验、审核待完成。框架文档按文件名初步归入知识域，其余案例文档归入摘要域；这不是质量背书。没有已批准的生产来源。后续逐项补齐来源与授权、脱敏并审核后，再另行调整导入流程；现有运行时不会因这份清单自动停止读取旧语料。

## 使用

在仓库根目录执行（Python 标准库，无网络）：

```powershell
python counseling-kb/validate_sources.py
python counseling-kb/validate_sources.py counseling-kb/manifests/sources.jsonl
python -m pytest eval/test_validate_sources.py -q
```

校验拒绝缺字段、重复 ID、错误枚举、越界/不存在路径、错误 URL、无效日期、生产准入不满足及显式生产/评测交叉登记；返回码 `0` 为通过，`1` 为失败。JSON Schema 描述单条结构及生产字段约束，脚本补充路径、重复项等跨记录检查，未实现通用 JSON Schema 引擎。后续导入器应只消费通过校验且用途匹配的条目，并记录具体内容版本。

## Java classpath 种子材料

`dk-ai-agent/src/main/resources/document/professional/` 共 6 篇专业摘要，包括原有 CCI、WHO mhGAP 材料及新增 CCI CBT、NIMH、WHO PFA、NICE NG222 材料；`document/policy/` 包含国家卫生健康委、NICE 两篇政策摘要。这 8 篇均为项目编写的原创整理文本，审核状态均为 `pending`。外部 `Source-URL` 是参考文献链接，不是被复制的全文内容，也不代表外部机构授权或背书。登记采用 `use=reference`、审核待完成；本地 `Status: active` 是运行时资源标记，不等于治理审批通过。

这些文件位于 `counseling-kb` 根目录之外，因此清单使用 `runtime_path` 登记相对仓库根目录的实际资源位置，同时用 `source_url` 登记参考文献，不使用越界的 `../` 路径。`runtime_path` 仅用于参考、评测或隔离盘点，不能作为生产准入路径；校验拒绝资源目录外、越界、不存在或非文件的路径。当前 WHO 摘要引用的是 mhGAP Intervention Guide 2.0（2016），并非 2023 年 mhGAP guideline；更换版本时应同时更新摘要、链接与登记。

`dk-ai-agent/src/main/resources/exam/practice/seed.jsonl` 是项目原创的虚构模拟练习（synthetic/local），不是考试真题、真实咨询案例或专业认证材料。该资源仅供练习 API 获取题目、判分和返回解析，不进入向量库、咨询检索或个人咨询记忆。清单以 `runtime_path` 登记该唯一正文，`use=reference`。其治理用途仅为参考/评测，不得通过改标签变为生产咨询知识。题目中的 `classpath:` 来源标识表示本地资源，不是外部引用网站。
