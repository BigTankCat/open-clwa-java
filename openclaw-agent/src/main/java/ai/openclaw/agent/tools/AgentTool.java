package ai.openclaw.agent.tools;

import java.util.Map;

/**
 * Declarative tool for future agent loops (function calling). Execution wiring lives in the gateway
 * for now.
 */
public interface AgentTool {

  String name();

  String description();

  /** OpenAI-style JSON Schema object under {@code function.parameters}. */
  Map<String, Object> parametersSchema();

  /**
   * Execute with the JSON arguments object the model produced for {@code function.arguments}.
   *
   * @return Result string stored as the tool message {@code content} (often JSON or plain text).
   */
  String execute(String argumentsJson) throws Exception;
}
