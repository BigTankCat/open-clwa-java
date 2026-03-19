package ai.openclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes OpenClaw config back to {@code openclaw.json}.
 *
 * <p>This first slice writes plain JSON (no JSON5/include/env substitution).
 */
public final class ConfigWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ConfigPaths paths;

  public ConfigWriter(ConfigPaths paths) {
    this.paths = paths;
  }

  public String getConfigPath() {
    return paths.getConfigPath();
  }

  public void write(Map<String, Object> config) {
    Path file = paths.getConfigFilePath();
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config);
      Files.writeString(file, json, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new ConfigWriteException("Failed to write config to " + file, e);
    }
  }

  public static final class ConfigWriteException extends RuntimeException {
    public ConfigWriteException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

