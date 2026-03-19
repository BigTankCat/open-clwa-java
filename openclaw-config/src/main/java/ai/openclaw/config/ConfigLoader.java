package ai.openclaw.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Loads OpenClaw config from JSON file; aligned with Node config load (simplified:
 * no JSON5, no includes, no env substitution in this first slice).
 */
public final class ConfigLoader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ConfigPaths paths;

  public ConfigLoader(ConfigPaths paths) {
    this.paths = paths;
  }

  /**
   * Load config from the resolved config file. Returns empty object if file does not exist.
   */
  public ConfigSnapshot load() {
    Path file = paths.getConfigFilePath();
    if (!Files.isRegularFile(file)) {
      return new ConfigSnapshot(null, Collections.emptyMap(), false);
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      JsonNode root = MAPPER.readTree(bytes);
      Map<String, Object> map = root == null || !root.isObject()
          ? Collections.emptyMap()
          : MAPPER.convertValue(root, ConfigSnapshot.MAP_TYPE);
      return new ConfigSnapshot(file.toString(), map, true);
    } catch (Exception e) {
      throw new ConfigLoadException("Failed to load config from " + file, e);
    }
  }

  public ConfigPaths getPaths() {
    return paths;
  }

  public static final class ConfigLoadException extends RuntimeException {
    public ConfigLoadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
