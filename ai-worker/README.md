# DK AI Worker

这是可选的 Python intelligence plane。Java 25 主控仍负责 HTTP/SSE、会话历史、数据库、预算、超时、并发闸门和最终回退；Worker 只负责深度模式中的结构化规划、中文混合重排和逐字稿片段候选。

默认没有 `DEEPSEEK_API_KEY` 也能启动。此时 Worker 会返回带 `degraded=true` 的启发式结果；Java 默认不把它当成深度成功，而是改用进程内 Spring AI Agent，再失败才回到现有 Java RAG Advisor。

## 本地运行

```powershell
cd ai-worker
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
$env:PYTHONUTF8="1"
$env:PYTHONIOENCODING="utf-8"
.\.venv\Scripts\python.exe -m uvicorn dk_ai_worker.main:app --host 127.0.0.1 --port 8001
```

健康检查：`http://127.0.0.1:8001/internal/v1/health/live`。

## 合约

- `POST /internal/v1/plan`
- `POST /internal/v1/evidence/refine`
- `GET /internal/v1/health/live`
- `GET /internal/v1/health/ready`

请求和响应使用 UTF-8 JSON、camelCase 字段、`contractVersion: "1"`。设置 `AI_WORKER_SHARED_SECRET` 后，Java 通过 `X-AI-Worker-Token` 认证；Worker 不记录用户原文、候选全文或模型回复。

## 测试

```powershell
.\.venv\Scripts\python.exe -m pytest
```

逐字稿目录只读挂载为 `COUNSELING_TRANSCRIPT_DIRECTORY`，slug 必须符合 `YYYY-MM-DD-call-NN`，并且路径归一化后仍在该目录内。
