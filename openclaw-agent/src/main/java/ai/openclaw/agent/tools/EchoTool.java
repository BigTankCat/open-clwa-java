package ai.openclaw.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/** Demo tool: documents the OpenAI tools[] shape for clients. */
public final class EchoTool implements AgentTool {

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
}
