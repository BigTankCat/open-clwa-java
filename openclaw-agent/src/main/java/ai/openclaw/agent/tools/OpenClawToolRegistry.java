package ai.openclaw.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
