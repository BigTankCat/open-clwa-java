# Node 能力桥接（Java 网关）

Java 网关本身不内嵌 Chromium、TTS 提供商或渠道运行时。与主仓库 Node 网关一致，这些能力由 **已连接的 OpenClaw Node**（通过 `node.pending.pull` / `node.invoke.result`）执行。

本目录 **`openclaw-java`** 下的实现位置：

| 能力 | Java 包 / 类 | Node 命令 / 说明 |
|------|----------------|------------------|
| 浏览器控制面 HTTP 代理 | `openclaw-gateway/.../nodebridge/BrowserProxyNodeBridge.java` | `browser.proxy`（见主仓库 `src/gateway/server-methods/browser.ts`） |
| 通用 Node 调用 | `NodeInvokeService`、`node.invoke` WS 方法、`NodeInvokeAgentTool` | 任意 Node 声明的 `command` |
| TTS | 仍为占位 | 主仓库在进程内调用 `src/tts/tts.ts`，**不**通过 `node.invoke`；Java 侧若要完整 TTS 需另接服务或长期由 Node 网关处理 |

## 环境变量（Java）

- **`OPENCLAW_BRIDGE_BROWSER_NODE_ID`**（或 Spring 配置 **`openclaw.bridge.browser-node-id`**）：启用 `browser.request` → 向该 id 的已连接 Node 发送 `browser.proxy`。
- **`openclaw.bridge.browser-invoke-timeout-ms`**：未在请求里传 `timeoutMs` 时的默认等待时间（毫秒，默认 120000）。

## 限制

- Java 桥接 **不会** 复现 Node 侧 `persistProxyFiles` / 路径重写；代理返回中的内联文件路径仍以 Node 返回为准。
- Node 侧的命令白名单、capabilities 校验仍在 **Node 运行时** 生效；Java 仅负责排队与等待结果。

## 主仓库参考路径

- `src/gateway/server-methods/browser.ts` — `browser.request` / `browser.proxy`
- `src/gateway/server-methods/tts.ts` — TTS WebSocket 方法
- `src/gateway/node-registry.ts` — Node 会话与 `invoke`

## Java 仓库内已复制的上游快照（便于单独拆仓）

目录：**`vendor/openclaw-node-ref/`**（MIT，与主仓库一致）。从本机主仓库刷新：

```bash
./scripts/sync-openclaw-node-ref.sh /path/to/openclaw
```

详见 `vendor/openclaw-node-ref/README.md`。

更多索引见 `reference/node-capabilities/README.md`。
