# OpenClaw Node / 网关参考快照（vendor）

本目录为从主仓库 **OpenClaw** 复制的 **只读参考源码**，供 **Java 仓库单独维护** 时对照 `browser.proxy`、`tts.*`、`node.invoke` 错误处理与命令策略，**不参与 Maven 构建**。

## 许可证

与上游一致：**MIT**（见仓库根目录 `LICENSE`）。复制时保留各文件原有版权与许可注释（若有）。

## 上游与更新

- 权威仓库：<https://github.com/openclaw/openclaw>
- 建议在每次要对齐行为时，从本机主仓库同步，例如：

```bash
# 在 openclaw-java 根目录
./scripts/sync-openclaw-node-ref.sh /path/to/openclaw
```

或设置环境变量 **`OPENCLAW_UPSTREAM_ROOT`** 后执行同一脚本（见脚本内说明）。

## 目录布局（对应主仓库路径）

| 本目录文件 | 主仓库路径 |
|------------|------------|
| `src/gateway/server-methods/browser.ts` | `src/gateway/server-methods/browser.ts` |
| `src/gateway/server-methods/tts.ts` | `src/gateway/server-methods/tts.ts` |
| `src/gateway/server-methods/nodes.helpers.ts` | `src/gateway/server-methods/nodes.helpers.ts` |
| `src/infra/node-commands.ts` | `src/infra/node-commands.ts` |
| `src/gateway/node-command-policy.ts` | `src/gateway/node-command-policy.ts` |
| `src/gateway/device-metadata-normalization.ts` | `src/gateway/device-metadata-normalization.ts` |

**说明：** `node-command-policy.ts` 等文件仍包含指向上游其他模块的 `import`（如 `../config/config.js`）。在 vendor 树内单独打开时，TypeScript 可能报未解析模块，属预期；完整依赖图请在上游仓库中查看。

## 与 Java 实现的对应关系

Java 侧委托实现见 `openclaw-gateway/src/main/java/ai/openclaw/gateway/nodebridge/`，文档见 `docs/node-capabilities.md`。
