package ai.openclaw.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.json5.Json5Factory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;

/**
 * Shared parsers for OpenClaw config payloads.
 *
 * <p>This port currently supports JSON5 input, matching the Node gateway expectations for
 * config.apply/config.patch raw payloads.
 */
public final class ConfigParsers {

  private static final ObjectMapper JSON5_MAPPER = new ObjectMapper(new Json5Factory());

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private ConfigParsers() {}

  /**
   * Parse JSON5 string and return an object map.
   *
   * @throws IllegalArgumentException when raw is not a JSON5 object.
   */
  public static Map<String, Object> parseJson5Object(String raw) throws Exception {
    if (raw == null) return Collections.emptyMap();
    JsonNode node = JSON5_MAPPER.readTree(raw);
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("raw must be a JSON5 object");
    }
    return JSON5_MAPPER.convertValue(node, MAP_TYPE);
  }
}

