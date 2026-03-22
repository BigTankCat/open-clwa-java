package ai.openclaw.gateway.agent.tools;

import ai.openclaw.agent.runtime.ToolExecutionContext;
import ai.openclaw.agent.tools.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.openclaw.memory.SqliteMemoryStore;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes to {@link SqliteMemoryStore} for the current session agent. */
public final class MemoryPutAgentTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final SqliteMemoryStore store;

  public MemoryPutAgentTool(SqliteMemoryStore store) {
    this.store = store;
  }

  @Override
  public String name() {
    return "memory_put";
  }

  @Override
  public String description() {
    return "Store text in the agent-scoped memory index (SQLite). Replaces previous chunks for the same path.";
  }

  @Override
  public Map<String, Object> parametersSchema() {
    Map<String, Object> path =
        Map.of("type", "string", "description", "Logical path or label for this memory (e.g. notes/topic.md)");
    Map<String, Object> content =
        Map.of("type", "string", "description", "Full text to store (will be chunked automatically)");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("path", path);
    props.put("content", content);
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("type", "object");
    root.put("properties", props);
    root.put("required", java.util.List.of("path", "content"));
    return root;
  }

  @Override
  public String execute(String argumentsJson, ToolExecutionContext ctx) throws Exception {
    JsonNode n = MAPPER.readTree(argumentsJson);
    String path = n.path("path").asText("").trim();
    String content = n.path("content").asText("");
    if (path.isEmpty()) {
      return MAPPER.writeValueAsString(Map.of("error", "path_required"));
    }
    String agentId = ctx != null ? ctx.agentId() : "default";
    store.put(agentId, path, content);
    return MAPPER.writeValueAsString(Map.of("ok", true, "path", path, "agentId", agentId));
  }
}
