package ai.openclaw.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Collects {@link AgentTool} definitions and exposes them as OpenAI {@code tools[]} entries. */
public final class OpenClawToolRegistry {

  private final List<AgentTool> tools = new ArrayList<>();

  public void register(AgentTool tool) {
    if (tool == null) {
      throw new IllegalArgumentException("tool required");
    }
    tools.add(tool);
  }

  public List<AgentTool> list() {
    return List.copyOf(tools);
  }

  public Optional<AgentTool> find(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    for (AgentTool t : tools) {
      if (name.equals(t.name())) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /**
   * Runs a tool by function name. Unknown tools return a small JSON error payload (HTTP 200 from
   * model perspective); the runner still sends it as tool output.
   */
  public String execute(String name, String argumentsJson) {
    Optional<AgentTool> opt = find(name);
    if (opt.isEmpty()) {
      return "{\"error\":\"unknown_tool\",\"name\":\"" + jsonEscape(name) + "\"}";
    }
    try {
      return opt.get().execute(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
    } catch (Exception e) {
      return "{\"error\":\"tool_execution\",\"message\":\"" + jsonEscape(e.getMessage()) + "\"}";
    }
  }

  private static String jsonEscape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  public List<Map<String, Object>> openAiTools() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (AgentTool t : tools) {
      Map<String, Object> fn = new LinkedHashMap<>();
      fn.put("name", t.name());
      fn.put("description", t.description());
      fn.put("parameters", t.parametersSchema());
      out.add(Map.of("type", "function", "function", fn));
    }
    return List.copyOf(out);
  }
}
