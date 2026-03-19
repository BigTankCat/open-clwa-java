package ai.openclaw.gateway.config;

import ai.openclaw.config.ConfigLoader;
import ai.openclaw.config.ConfigPaths;
import ai.openclaw.config.ConfigWriter;
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
}
