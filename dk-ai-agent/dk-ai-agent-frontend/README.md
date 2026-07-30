# AI 心理咨询师前端

Vue 3 + Vite 单入口前端，页面地址为 `/psych-master`，根路径 `/` 会直接跳转到该页面。

## 本地开发

需要 Node.js 20 或兼容版本，并先启动后端：

```cmd
npm.cmd ci
npm.cmd run dev
```

默认访问 `http://localhost:3001`。开发环境请求 `http://localhost:8123/api`。

## 构建

```cmd
npm.cmd run build
```

产物输出到 `dist/`。Docker 镜像使用 Node 20 构建，再由 Nginx 提供静态文件及 `/api/` 反向代理。

## 主要接口

| 接口 | 用途 |
|---|---|
| `/api/ai/counseling/chat/sse` | 快速回复和深度思考的 SSE 对话 |
| `/api/ai/conversations` | 创建及读取历史会话 |
| `/api/health` | 后端健康检查 |

流式响应会解析 `status`、`fallback`、`delta` 和 `done` 四类事件；只有 `delta` 会进入 Markdown 消息正文。历史会话保存在 PostgreSQL，前端支持新建、切换和删除。

## 当前边界

- `EventSource` 通过 GET query string 发送消息，敏感内容可能进入代理访问日志；对外部署前应改成 POST 流式协议。
- 当前没有登录与用户隔离，不适合直接暴露到公网。
- 页面不替代医疗诊断或紧急援助。

完整说明见 [功能说明](../../docs/FUNCTIONAL_SPEC.md) 和 [技术设计](../../docs/TECHNICAL_DESIGN.md)。
