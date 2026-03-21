# OpenClaw Java (Spring) 实现

本目录为 OpenClaw Node 工程核心逻辑的 **Java Spring** 移植，与主仓库 [openclaw/openclaw](https://github.com/openclaw/openclaw) 的 Gateway/配置等行为对齐，便于在 JVM 环境下部署与维护。

## 模块结构

| 模块 | 说明 |
|------|------|
| `openclaw-protocol` | 协议 DTO：WebSocket 帧（req/res/event）、错误码与 ErrorShape |
| `openclaw-config` | 配置路径（state dir、config 文件）、JSON5 加载、`$include`、`${ENV}` 等 |
| `openclaw-llm` | OpenAI 兼容 `chat/completions` HTTP 客户端（供网关与后续 Agent 复用） |
| `openclaw-memory` | 本地 SQLite 记忆库（首版：按 agentId 分库、`memory.put` / `memory.search`、LIKE 检索） |
| `openclaw-plugin-api` | 插件 SPI：`OpenClawPlugin` + `ServiceLoader` 发现 |
| `openclaw-agent` | Agent 工具链骨架：`OpenClawToolRegistry`、内置 `echo` 工具定义（OpenAI `tools[]` 形状） |
| `openclaw-gateway` | Spring Boot Gateway：HTTP 健康检查、WebSocket JSON-RPC、鉴权与方法 scope |

## 与 Node 实现的对应关系

- **配置与路径**：`openclaw-config` 对应 Node 的 `src/config/paths.ts`、配置加载与合并（JSON5、`$include`、`${ENV}` 等）。
- **Memory（SQLite）**：`openclaw-memory` 对应 Node `src/memory` 的**本地索引**思路；当前 Java 首版为文本块 + LIKE 搜索，**尚无** `sqlite-vec` 级向量检索与完整 QMD 同步。
- **插件**：`openclaw-plugin-api` 提供 SPI；网关在启动时 `ServiceLoader` 加载实现类（需在 JAR 的 `META-INF/services/ai.openclaw.plugin.api.OpenClawPlugin` 中登记）。
- **Agent / 工具链**：`openclaw-agent` 提供工具注册表与 OpenAI 形态的工具描述；**完整多轮 tool-call 执行循环**仍在网关侧迭代中。
- **桌面 / 移动端**：与 Node 主仓库一致，**不在** `openclaw-java` 内实现；macOS/iOS/Android 客户端仍在主工程 `apps/*`（Swift/Kotlin 等）。Java 网关通过 WebSocket/HTTP 对接这些客户端即可。
- **协议**：`openclaw-protocol` 对应 `src/gateway/protocol/`（ErrorCodes、ErrorShape、Request/Response/Event 帧）。
- **鉴权与 scope**：`MethodScopes` 对应 `src/gateway/method-scopes.ts`（operator.read/write/admin 等）。
- **HTTP**：`/health`、`/healthz`（存活）、`/ready`、`/readyz`（就绪）对应 Node `server-http.ts`。
- **WebSocket**：`/ws` 上 JSON-RPC，先发 `connect`（可选 token/scopes），再支持 `health`、`config.get` 等方法。

## 构建与运行

- **要求**：JDK 21+、Maven 3.9+
- **构建**：在 `openclaw-java` 目录下执行  
  `mvn clean install`
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
- **新增 HTTP 端点**：在 `openclaw-gateway` 中新增 `@RestController` 或挂到现有 Controller。
- **渠道 / 插件**：可新增子模块（如 `openclaw-channels-api`、`openclaw-plugins-api`），定义 SPI 与 Node 的 channel/plugin 契约对齐，再在 gateway 中注册路由或 WS 方法。

## 文档

- 主项目文档：<https://docs.openclaw.ai>
- 仓库：<https://github.com/openclaw/openclaw>
