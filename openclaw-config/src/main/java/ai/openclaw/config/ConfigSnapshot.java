package ai.openclaw.config;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;

/** Immutable snapshot of config (path + raw map). */
public final class ConfigSnapshot {

  static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final String configPath;
  private final Map<String, Object> config;
  private final boolean exists;

  public ConfigSnapshot(String configPath, Map<String, Object> config, boolean exists) {
    this.configPath = configPath;
    this.config = config != null ? Map.copyOf(config) : Map.of();
    this.exists = exists;
  }

  public String getConfigPath() {
    return configPath;
  }

  public Map<String, Object> getConfig() {
    return config;
  }

  public boolean isExists() {
    return exists;
  }
}
