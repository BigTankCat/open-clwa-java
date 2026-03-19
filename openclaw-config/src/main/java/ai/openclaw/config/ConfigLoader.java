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
  /**
   * Load config and resolve:
   * - $include (recursive)
   * - ${ENV} substitutions (with config.env overrides applied to env map)
   */
  public ConfigSnapshot load() {
    Path file = paths.getConfigFilePath();
    if (!Files.isRegularFile(file)) {
      return new ConfigSnapshot(null, Collections.emptyMap(), false);
    }
    try {
      ConfigSnapshot raw = loadRaw();
      @SuppressWarnings("unchecked")
      Map<String, Object> resolved = raw.getConfig();

      Map<String, String> env = buildEnvMap(resolved);
      Object substituted =
          ConfigEnvSubstitutor.substituteConfigEnvVars(resolved, env, (msg) -> System.err.println(msg));
      @SuppressWarnings("unchecked")
      Map<String, Object> substitutedMap = (Map<String, Object>) substituted;

      return new ConfigSnapshot(file.toString(), substitutedMap, true);
    } catch (Exception e) {
      throw new ConfigLoadException("Failed to load config from " + file, e);
    }
  }

  /**
   * Load config after resolving $include, but without ${ENV} substitution.
   * This snapshot is used to restore env var references during write-back.
   */
  public ConfigSnapshot loadRaw() {
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

      ConfigIncludes includes = new ConfigIncludes(MAPPER);
      Object withIncludes = includes.resolveIncludes(map, file);

      @SuppressWarnings("unchecked")
      Map<String, Object> rawMap = (Map<String, Object>) withIncludes;
      return new ConfigSnapshot(file.toString(), rawMap, true);
    } catch (Exception e) {
      throw new ConfigLoadException("Failed to load raw config from " + file, e);
    }
  }

  public static Map<String, String> buildEnvMap(Map<String, Object> config) {
    Map<String, String> env = new HashMap<>(System.getenv());
    Object envObj = config != null ? config.get("env") : null;
    if (envObj instanceof Map<?, ?> envMap) {
      for (Map.Entry<?, ?> e : envMap.entrySet()) {
        if (!(e.getKey() instanceof String k)) continue;
        Object v = e.getValue();
        if (v == null) continue;
        env.put(k, String.valueOf(v));
      }
    }
    return env;
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
