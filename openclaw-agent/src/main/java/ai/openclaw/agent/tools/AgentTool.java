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
}
