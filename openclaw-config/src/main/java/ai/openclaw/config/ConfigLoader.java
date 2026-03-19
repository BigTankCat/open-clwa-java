package ai.openclaw.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.json5.Json5Factory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads OpenClaw config from JSON file; aligned with Node config load (simplified:
 * no includes or env substitution in this first slice).
 */
public final class ConfigLoader {

  private static final ObjectMapper MAPPER = new ObjectMapper(new Json5Factory());

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

      // 1) Resolve $include recursively.
      ConfigIncludes includes = new ConfigIncludes(MAPPER);
      Object withIncludes = includes.resolveIncludes(map, file);

      // 2) Apply ${ENV} substitution.
      @SuppressWarnings("unchecked")
      Map<String, Object> resolved = (Map<String, Object>) withIncludes;

      Map<String, String> env = new HashMap<>(System.getenv());
      // Apply config-defined env vars before substitution.
      Object envObj = resolved.get("env");
      if (envObj instanceof Map<?, ?> envMap) {
        for (Map.Entry<?, ?> e : envMap.entrySet()) {
          if (!(e.getKey() instanceof String k)) continue;
          Object v = e.getValue();
          if (v == null) continue;
          env.put(k, String.valueOf(v));
        }
      }

      Object substituted =
          ConfigEnvSubstitutor.substituteConfigEnvVars(
              resolved, env, (msg) -> System.err.println(msg));
      @SuppressWarnings("unchecked")
      Map<String, Object> substitutedMap = (Map<String, Object>) substituted;

      return new ConfigSnapshot(file.toString(), substitutedMap, true);
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
