package ai.openclaw.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Demo tool: documents the OpenAI tools[] shape for clients. */
public final class EchoTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "echo";
  }

  @Override
  public String description() {
    return "Echo back a short message (demo tool for Java agent toolchain).";
  }

  @Override
  public Map<String, Object> parametersSchema() {
    Map<String, Object> message =
        Map.of("type", "string", "description", "Text to echo back");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("message", message);
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("type", "object");
    root.put("properties", props);
    root.put("required", java.util.List.of("message"));
    return root;
  }

  @Override
  public String execute(String argumentsJson) throws Exception {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return "{\"error\":\"missing_arguments\"}";
    }
    JsonNode n = MAPPER.readTree(argumentsJson);
    String msg = n.path("message").asText("");
    return MAPPER.writeValueAsString(Map.of("echo", msg));
  }
}
