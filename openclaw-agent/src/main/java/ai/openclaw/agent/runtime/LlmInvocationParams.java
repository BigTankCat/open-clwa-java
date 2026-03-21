package ai.openclaw.agent.runtime;

/**
 * Parameters for a chat-completions call (mirrors gateway LLM config fields used at runtime).
 */
public record LlmInvocationParams(
    String chatCompletionsUrl,
    String apiKey,
    String model,
    Double temperature,
    Integer maxTokens) {}
