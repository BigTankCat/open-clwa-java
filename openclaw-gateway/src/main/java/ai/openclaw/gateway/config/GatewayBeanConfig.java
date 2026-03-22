package ai.openclaw.gateway.config;

import ai.openclaw.agent.tools.EchoTool;
import ai.openclaw.agent.tools.OpenClawToolRegistry;
import ai.openclaw.gateway.agent.tools.ClaudeTaskAgentTool;
import ai.openclaw.gateway.agent.tools.MemoryPutAgentTool;
import ai.openclaw.gateway.agent.tools.MemorySearchAgentTool;
import ai.openclaw.gateway.agent.tools.NodeInvokeAgentTool;
import ai.openclaw.gateway.node.NodeInvokeService;
import ai.openclaw.config.ConfigLoader;
import ai.openclaw.config.ConfigPaths;
import ai.openclaw.config.ConfigWriter;
import ai.openclaw.memory.HttpEmbeddingClient;
import ai.openclaw.memory.SqliteMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayBeanConfig {

  @Bean
  public ConfigPaths configPaths() {
    return ConfigPaths.fromEnvironment();
  }

  @Bean
  public ConfigLoader configLoader(ConfigPaths paths) {
    return new ConfigLoader(paths);
  }

  @Bean
  public ConfigWriter configWriter(ConfigPaths paths) {
    return new ConfigWriter(paths);
  }

  @Bean
  public SqliteMemoryStore sqliteMemoryStore(ConfigPaths paths) {
    HttpEmbeddingClient emb = new HttpEmbeddingClient();
    return new SqliteMemoryStore(paths.getStateDirPath(), emb.enabled() ? emb : null);
  }

  @Bean
  public OpenClawToolRegistry openClawToolRegistry(
      ConfigPaths configPaths, SqliteMemoryStore memory, NodeInvokeService nodeInvoke) {
    OpenClawToolRegistry registry = new OpenClawToolRegistry();
    registry.register(new EchoTool());
    registry.register(new MemoryPutAgentTool(memory));
    registry.register(new MemorySearchAgentTool(memory));
    if (Boolean.parseBoolean(
        System.getenv().getOrDefault("OPENCLAW_CLAUDE_TASK_TOOL_ENABLED", "true"))) {
      registry.register(new ClaudeTaskAgentTool(configPaths));
    }
    if (Boolean.parseBoolean(
        System.getenv().getOrDefault("OPENCLAW_NODE_INVOKE_TOOL_ENABLED", "false"))) {
      registry.register(new NodeInvokeAgentTool(nodeInvoke));
    }
    return registry;
  }
}
