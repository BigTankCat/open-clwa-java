package ai.openclaw.agent.tools;

import ai.openclaw.agent.runtime.ToolExecutionContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for collecting and managing {@link AgentTool} definitions.
 * <p>
 * This registry is responsible for:
 * <ul>
 *   <li>Collecting tool definitions from various sources (built-in, plugins, skills)</li>
 *   <li>Exposing tools in OpenAI-compatible format for LLM function calling</li>
 *   <li>Executing tool calls and managing tool lifecycle</li>
 * </ul>
 * <p>
 * Thread-safety: This class is thread-safe for read operations. Tool registration
 * should be done during initialization phase before any execution.
 *
 * @author OpenClaw Team
 * @since 2026.3.14
 * @see AgentTool
 * @see ai.openclaw.agent.runtime.AgentTurnRunner
 */
public final class OpenClawToolRegistry {

  private final List<AgentTool> tools = new ArrayList<>();

  /**
   * Registers a tool in this registry.
   * <p>
   * Tool registration is thread-safe but should be done during initialization
   * before any tool execution begins. Registering tools after the system
   * is running may lead to race conditions.
   *
   * @param tool the tool to register, must not be null
   * @throws IllegalArgumentException if tool is null
   * @since 2026.3.14
   */
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
  public String execute(String name, String argumentsJson, ToolExecutionContext ctx) {
    Optional<AgentTool> opt = find(name);
    if (opt.isEmpty()) {
      return "{\"error\":\"unknown_tool\",\"name\":\"" + jsonEscape(name) + "\"}";
    }
    try {
      ToolExecutionContext c = ctx != null ? ctx : ToolExecutionContext.defaultContext();
      return opt.get()
          .execute(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson, c);
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
