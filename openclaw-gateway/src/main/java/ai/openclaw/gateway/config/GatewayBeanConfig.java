package ai.openclaw.gateway.config;

import ai.openclaw.agent.tools.EchoTool;
import ai.openclaw.agent.tools.OpenClawToolRegistry;
import ai.openclaw.config.ConfigLoader;
import ai.openclaw.config.ConfigPaths;
import ai.openclaw.config.ConfigWriter;
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
    return new SqliteMemoryStore(paths.getStateDirPath());
  }

  @Bean
  public OpenClawToolRegistry openClawToolRegistry() {
    OpenClawToolRegistry registry = new OpenClawToolRegistry();
    registry.register(new EchoTool());
    return registry;
  }
}
