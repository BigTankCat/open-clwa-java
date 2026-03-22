# Node 能力参考（与主仓库对照）

## 已 vend 的 TypeScript 快照（Java 仓库可单独拆仓）

与 Node 桥接相关的主仓库源码已复制到 **`vendor/openclaw-node-ref/`**（只读参考，**不参与 Maven 构建**）。更新方式见该目录 `README.md` 或根目录脚本 `scripts/sync-openclaw-node-ref.sh`。

## 索引（权威路径仍在主仓库）

| 主题 | 主仓库路径 | vendor 内文件 |
|------|------------|----------------|
| `browser.request` → `browser.proxy` | `src/gateway/server-methods/browser.ts` | `vendor/openclaw-node-ref/src/gateway/server-methods/browser.ts` |
| TTS WS 方法 | `src/gateway/server-methods/tts.ts` | `vendor/.../tts.ts` |
| `node.invoke` 错误形态 | `nodes.helpers.ts` 中 `respondUnavailableOnNodeInvokeError` | `vendor/.../nodes.helpers.ts` |
| 命令常量 | `src/infra/node-commands.ts` | `vendor/.../src/infra/node-commands.ts` |
| Node 命令白名单策略 | `src/gateway/node-command-policy.ts` | `vendor/.../node-command-policy.ts` |

更大范围逻辑（完整 `nodes.ts`、`node-registry.ts` 等）请在上游仓库查看；需要时再扩展 sync 脚本拷贝范围。

Java 侧委托实现：`openclaw-gateway/src/main/java/ai/openclaw/gateway/nodebridge/`。

设计文档：`docs/node-capabilities.md`。
