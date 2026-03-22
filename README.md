# OpenClaw Java (Spring) 实现

本目录为 OpenClaw Node 工程核心逻辑的 **Java Spring** 移植，与主仓库 [openclaw/openclaw](https://github.com/openclaw/openclaw) 的 Gateway/配置等行为对齐，便于在 JVM 环境下部署与维护。

**单独维护 Java 仓库时**：与 Node 运行时相关的网关侧 **TypeScript 参考源码** 已放在 **`vendor/openclaw-node-ref/`**（不参与 Maven 构建）；可用 **`scripts/sync-openclaw-node-ref.sh`** 从本机主仓库重新同步，避免依赖 monorepo 里「旁边的」`openclaw` 目录才能查契约。

## 模块结构

| 模块 | 说明 |
|------|------|
| `openclaw-protocol` | 协议 DTO：WebSocket 帧（req/res/event）、错误码与 ErrorShape |
| `openclaw-config` | 配置路径（state dir、config 文件）、JSON5 加载、`$include`、`${ENV}` 等 |
| `openclaw-llm` | OpenAI 兼容 `chat/completions` HTTP 客户端（供网关与后续 Agent 复用） |
| `openclaw-memory` | 本地 SQLite 记忆库（首版：按 agentId 分库、`memory.put` / `memory.search`、LIKE 检索） |
| `openclaw-plugin-api` | 插件 SPI：`OpenClawPlugin` + `ServiceLoader` 发现 |
| `openclaw-agent` | Agent 工具链：`OpenClawToolRegistry`、`AgentTurnRunner`（OpenAI `tool_calls` 多轮闭环）、内置 `echo` 工具 |
| `openclaw-gateway` | Spring Boot Gateway：HTTP 健康检查、WebSocket JSON-RPC、鉴权与方法 scope |

## 与 Node 实现的对应关系

- **配置与路径**：`openclaw-config` 对应 Node 的 `src/config/paths.ts`、配置加载与合并（JSON5、`$include`、`${ENV}` 等）。
- **Memory（SQLite）**：`openclaw-memory` 为文本块 + **LIKE 召回**；若配置 **`OPENCLAW_EMBEDDINGS_URL`**（OpenAI 兼容 `POST` embeddings）及 **`OPENCLAW_EMBEDDINGS_API_KEY`** / **`OPENCLAW_EMBEDDINGS_MODEL`**，则在 `memory_put` 后为块写入 **float 向量**（BLOB），`memory_search` 在 LIKE 候选集上做 **余弦重排**（无需本机 `sqlite-vec` 扩展）。未配置嵌入 URL 时行为与旧版一致。
- **插件**：`openclaw-plugin-api` 提供 SPI；网关在启动时 `ServiceLoader` 加载实现类（需在 JAR 的 `META-INF/services/ai.openclaw.plugin.api.OpenClawPlugin` 中登记）。
- **Agent / 工具链**：`chat.send` 触发的 LLM 路径使用 `AgentTurnRunner`：模型返回 `tool_calls` 时按名称调用 `OpenClawToolRegistry.execute`，将 `role=tool` 结果写回对话并再次请求模型，默认最多 **8** 轮（见 `AgentTurnRunner` 构造参数）。**会话 transcript** 仍只追加最终 assistant 文本；中间轮次与工具 I/O 在 trace 中查看。
- **Tools 合并**：内置 registry（如 `echo`）与 `llm.config.set` 的 `tools` 合并为一份请求列表；**同名 function 以用户配置覆盖**，并写入 trace 事件 `agent.tools.merge`（`overriddenToolNames`）。若无任何 tool，则不传 `tools`/`tool_choice`。
- **桌面 / 移动端**：与 Node 主仓库一致，**不在** `openclaw-java` 内实现；macOS/iOS/Android 客户端仍在主工程 `apps/*`（Swift/Kotlin 等）。Java 网关通过 WebSocket/HTTP 对接这些客户端即可。
- **协议**：`openclaw-protocol` 对应 `src/gateway/protocol/`（ErrorCodes、ErrorShape、Request/Response/Event 帧）。
- **鉴权与 scope**：`MethodScopes` 对应 `src/gateway/method-scopes.ts`（operator.read/write/admin 等）。
- **HTTP**：`/health`、`/healthz`（存活）、`/ready`、`/readyz`（就绪）对应 Node `server-http.ts`。会话类 API：`GET/POST /sessions/{key}/...`。**`POST /sessions/{key}/kill`** 对齐 Node `session-kill-http.ts`：需 **本机直连 admin**（回环 + Host 规则，受 `gateway.trustedProxies` / `gateway.allowRealIpFallback` 影响）、**有效 Bearer**（与 `OPENCLAW_GATEWAY_TOKEN` 一致时视为 operator），或 **`x-openclaw-requester-session-key`** + 在 `SubagentRunRegistry` 中登记的子会话归属；否则 403。历史/trace 等接口仍可用「未配置 token 时放行」的开发行为。**HTTP 入站渠道**：`POST /api/channel/http/message`，JSON 体 `{"sessionKey":"...","message":"..."}`，鉴权与 token 规则同其他 HTTP API；行为与 WebSocket `chat.send` 一致。
- **WebSocket**：`/ws` 上 JSON-RPC。**本地网页（Control UI）对齐**：`chat.send`（含 `idempotencyKey`/`runId`）、`chat.history`、`chat.abort`；`sessions.patch` / `sessions.reset` / `sessions.compact`（compact 当前为占位 no-op）；运行结束或中止时向已连接客户端广播 **`chat`** 事件（`final` / `aborted`），与 Web 端流式 UI 兼容。另实现 **`config.set`**（可选 `baseHash` 与磁盘文件 SHA-256 校验）、**`logs.tail`**（`OPENCLAW_LOG_FILE` 或 `gateway-java.log`）、`last-heartbeat`、`agent.identity.get`、`system-presence`、`usage.cost` / `sessions.usage*`（占位空数据）、`exec.approval.resolve`（no-op）、`config.openFile`（返回不可用）、**`cron.*`**（进程内调度 + `~/.openclaw/java-gateway/cron-store.json` 持久化）、`tts.status`/`tts.providers`（占位）、**`browser.request`**（见下）、`device.pair.*` / `device.token.revoke`（空列表 / no-op）。**未实现外部消息渠道**（Telegram 等）：`channels.status` 仍主要描述 HTTP 入站，便于仅本地 Web + HTTP 接入。
- **依赖 Node 的能力（Java 工程内集中实现）**：`openclaw-gateway/.../nodebridge/` 包负责把需 Node 运行时的能力以 **`node.invoke`** 形式委托给已连接 Node。当前 **`browser.request`** 在设置 **`OPENCLAW_BRIDGE_BROWSER_NODE_ID`**（或 `openclaw.bridge.browser-node-id`）时，向该节点发送 **`browser.proxy`**（与主仓库 `src/gateway/server-methods/browser.ts` 对齐）。说明与限制见 **`docs/node-capabilities.md`**、**`reference/node-capabilities/README.md`**。
- **Skills**：合并顺序与 Node 一致：`extraDirs` → **bundled**（`OPENCLAW_BUNDLED_SKILLS_DIR`，否则自 `user.dir` 向上查找 `skills/`）→ `~/.openclaw/skills` → `~/.agents/skills` → `<workspace>/.agents/skills` → `<workspace>/skills`。`skills.status` 仅列出通过 **`shouldIncludeSkill` 等价过滤** 的条目（`enabled`、`skills.allowBundled`、OS、`requires` 的 bin/env/config、默认 `browser.*` 配置路径等），并填充 **`eligible` / `requirements` / `missing` / `configChecks` / `blockedByAllowlist` / `bundled` / `emoji` / `homepage`**（对齐 `skills-status.ts` + `requirements.ts` 的语义）。**`skills.install`** 前对技能目录做与 Node 同源的 **轻量安全扫描**（`skill-scanner` 规则子集），结果在 **`warnings`** 中返回；安装仍会继续。**尚未对齐**：配对节点上的远程 bin/OS（`getRemoteSkillEligibility`）、`@mariozechner/pi-coding-agent` 的加载细节，以及 `skills.status` 里 **首选单条 install 选项**（Node 在非全 `download` 时常只展示一条偏好安装项）。**`skills.update`** 写入 `openclaw.json` 的 `skills.entries`（JSON5 写回）。
- **Agent 可调工具（LLM function calling）**：内置 `echo`；若启用 SQLite memory，还有 `memory_put` / `memory_search`（`agentId` 取自当前会话，勿在参数里伪造）。大文本 `memory_put` 在库内按固定块长分片存储；`memory.search` 仍为 LIKE。**`claude_task`**（默认注册，可用 **`OPENCLAW_CLAUDE_TASK_TOOL_ENABLED=false`** 关闭）：在本机执行 **Claude Code CLI**（`claude --permission-mode bypassPermissions --print`），参数 **`prompt`**、**`workdir`**、可选 **`timeoutSec`**（默认 600s，上限 7200s）。须设置 **`OPENCLAW_WORKSPACE_ROOT`**（绝对路径），且 **`workdir`** 解析后必须落在该根目录下。可设置 **`OPENCLAW_CLAUDE_CLI`** 指定 `claude` 可执行文件路径。每次运行写入 **`${OPENCLAW_STATE_DIR}/java-gateway/claude-runs/<runId>.log`**，并在同目录 **`claude-runs-index.jsonl`** 追加一行 JSON（`runId`、`exitCode`、`logPath`、`timedOut` 等）供任务调度做多轮迭代。主仓库技能 **`claude-code-task`**（`skills/claude-code-task/SKILL.md`）说明前置条件与用法；**`coding-agent`** 技能中亦指向 Java 网关优先使用 `claude_task`。可选 **`node_invoke`**：设置 **`OPENCLAW_NODE_INVOKE_TOOL_ENABLED=true`** 后注册，通过 Node 队列把命令交给已连接 OpenClaw Node；参数含 `nodeId`、`command`、可选 `params`、`idempotencyKey`、`timeoutMs`（默认 120s，上限 600s）。
- **`chat.send` 反思轮次**：请求参数可选 **`reflectionRounds`**（0–3，默认 0）与 **`reflectionPrompt`**（自定义英文/中文提示；省略则用内置英文审稿提示）。首轮仍走完整 tools；后续轮次在**同一条 LLM 对话链**上追加 user 审稿消息并 **关闭 tools**，最后只向会话 transcript **追加一条**最终 assistant 文本。Trace：`agent.reflection.start` / `agent.reflection.end`。

## Trace 事件（与会话 `*.jsonl`）

与一次 `chat.send` → LLM 相关的典型事件包括：

- `agent.turn.start`：本轮开始摘要（如 `memoryHitCount`、`mergedToolCount`）。
- `llm.request` / `llm.response` / `llm.usage`：每一轮对厂商的请求与响应（多轮 tool 时会各出现多次）。
- `agent.tool_calls`：模型返回的 `tool_calls`（含 `round`）。
- `agent.tool_result`：每个工具执行结果摘要（`content` 在 trace 中截断）。
- `agent.tools.merge`：用户 `tools` 覆盖了哪些内置工具名。
- `agent.reflection.start` / `agent.reflection.end`：当 `chat.send` 带 `reflectionRounds`>0 时的自检轮次（见上文）。

## 构建与运行

- **要求**：JDK 21+、Maven 3.9+
- **构建**：在 `openclaw-java` 目录下执行  
  `mvn clean install`
- **单测（推荐）**：`mvn test -pl openclaw-llm,openclaw-agent -am`
- **运行 Gateway**：  
  `cd openclaw-gateway && mvn spring-boot:run`  
  或指定端口：  
  `OPENCLAW_GATEWAY_PORT=18789 mvn spring-boot:run`
- **可选鉴权**：设置环境变量 `OPENCLAW_GATEWAY_TOKEN`，WebSocket 连接时在 `connect` 的 `auth.token` 中携带该 token。

## 配置与状态目录

- 默认状态目录：`~/.openclaw`（可通过 `OPENCLAW_STATE_DIR` 覆盖）。
- 默认配置文件：`${OPENCLAW_STATE_DIR}/openclaw.json`（可通过 `OPENCLAW_CONFIG_PATH` 覆盖）。

与 Node 端保持一致，便于同一台机器上混用或迁移。

## 扩展与维护建议

- **新增 WebSocket 方法**：在 `GatewayWebSocketHandler` 的 `handleTextMessage` 中增加 `method` 分支，并确保在 `MethodScopes` 中登记所需 scope。
- **自主目标（多轮编排）**：`autonomous.goals.*` + 事件 `autonomous.goal`；`chat.send` 可带 `autonomousGoalId`（或 `autonomousTaskId`）把每轮 `runId` 关联到 `${OPENCLAW_STATE_DIR}/java-gateway/autonomous-goals/` 下的 JSON + JSONL。详见 `docs/engineering-java-openclaw-handroll.zh.md` §3.5。
- **新增 HTTP 端点**：在 `openclaw-gateway` 中新增 `@RestController` 或挂到现有 Controller。
- **渠道 / 插件**：可新增子模块（如 `openclaw-channels-api`、`openclaw-plugins-api`），定义 SPI 与 Node 的 channel/plugin 契约对齐，再在 gateway 中注册路由或 WS 方法。

## 文档

- Node 能力桥接（Java）：`docs/node-capabilities.md`，对照索引：`reference/node-capabilities/README.md`
- 主项目文档：<https://docs.openclaw.ai>
- 仓库：<https://github.com/openclaw/openclaw>
