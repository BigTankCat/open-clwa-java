package ai.openclaw.agent.runtime;

/**
 * Per-turn context for tools that need gateway/session state (e.g. memory scoped by agent).
 */
public record ToolExecutionContext(String agentId, String sessionKey) {

  public static ToolExecutionContext defaultContext() {
    return new ToolExecutionContext("default", "");
  }
}
