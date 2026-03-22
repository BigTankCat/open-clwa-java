package ai.openclaw.gateway.agent.tools;

import ai.openclaw.agent.runtime.ToolExecutionContext;
import ai.openclaw.agent.tools.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.openclaw.memory.MemoryHit;
import ai.openclaw.memory.SqliteMemoryStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** LIKE search over {@link SqliteMemoryStore} for the current session agent. */
public final class MemorySearchAgentTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final SqliteMemoryStore store;

  public MemorySearchAgentTool(SqliteMemoryStore store) {
    this.store = store;
  }

  @Override
  public String name() {
    return "memory_search";
  }

  @Override
  public String description() {
    return "Search agent memory for passages matching a query (substring / SQL LIKE semantics).";
  }

  @Override
  public Map<String, Object> parametersSchema() {
    Map<String, Object> query =
        Map.of("type", "string", "description", "Search string");
    Map<String, Object> limit =
        Map.of("type", "integer", "description", "Max rows (default 10, max 50)");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("query", query);
    props.put("limit", limit);
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("type", "object");
    root.put("properties", props);
    root.put("required", java.util.List.of("query"));
    return root;
  }

  @Override
  public String execute(String argumentsJson, ToolExecutionContext ctx) throws Exception {
    JsonNode n = MAPPER.readTree(argumentsJson);
    String query = n.path("query").asText("").trim();
    if (query.isEmpty()) {
      return MAPPER.writeValueAsString(Map.of("error", "query_required"));
    }
    int limit = 10;
    if (n.has("limit") && n.get("limit").canConvertToInt()) {
      limit = Math.min(50, Math.max(1, n.get("limit").asInt()));
    }
    String agentId = ctx != null ? ctx.agentId() : "default";
    List<MemoryHit> hits = store.search(agentId, query, limit);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (MemoryHit h : hits) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("path", h.path());
      row.put("chunkIndex", h.chunkIndex());
      row.put("content", h.content());
      row.put("createdAtMs", h.createdAtMs());
      rows.add(row);
    }
    return MAPPER.writeValueAsString(Map.of("hits", rows, "count", rows.size()));
  }
}
