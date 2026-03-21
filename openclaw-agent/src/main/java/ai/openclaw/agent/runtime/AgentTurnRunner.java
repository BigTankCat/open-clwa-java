package ai.openclaw.agent.runtime;

import ai.openclaw.agent.tools.OpenClawToolRegistry;
import ai.openclaw.llm.OpenAiCompatibleChatClient;
import ai.openclaw.llm.OpenAiCompatibleChatClient.ChatMessage;
import ai.openclaw.llm.OpenAiCompatibleChatClient.ChatResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs OpenAI-style tool calling loops: LLM → tool_calls → tool outputs → LLM … until text reply
 * or {@code maxToolRounds}.
 */
public final class AgentTurnRunner {

  private static final int DEFAULT_MAX_TOOL_ROUNDS = 8;
  private static final int ABS_MAX_TOOL_ROUNDS = 32;
  private static final int TOOL_RESULT_TRACE_MAX_CHARS = 4000;

  private final OpenAiCompatibleChatClient client;
  private final OpenClawToolRegistry registry;
  private final int maxToolRounds;

  public AgentTurnRunner(
      OpenAiCompatibleChatClient client,
      OpenClawToolRegistry registry,
      int maxToolRounds) {
    this.client = client;
    this.registry = registry;
    int n = maxToolRounds > 0 ? maxToolRounds : DEFAULT_MAX_TOOL_ROUNDS;
    this.maxToolRounds = Math.min(n, ABS_MAX_TOOL_ROUNDS);
  }

  public AgentTurnRunner(OpenAiCompatibleChatClient client, OpenClawToolRegistry registry) {
    this(client, registry, DEFAULT_MAX_TOOL_ROUNDS);
  }

  /**
   * Mutates {@code conversation} in place (appends assistant + tool messages for each tool round).
   *
   * @param tools merged OpenAI tools array, or null to omit
   * @param toolChoice model tool_choice, or null when {@code tools} is null
   */
  public String run(
      LlmInvocationParams params,
      List<ChatMessage> conversation,
      Object tools,
      Object toolChoice,
      AgentTraceSink sink)
      throws Exception {
    for (int round = 0; round < maxToolRounds; round++) {
      traceLlmRequest(sink, params, conversation, tools, toolChoice);
      ChatResult result =
          client.chatCompletions(
              params.chatCompletionsUrl(),
              params.apiKey(),
              params.model(),
              conversation,
              params.temperature(),
              params.maxTokens(),
              tools,
              toolChoice);
      traceLlmResponse(sink, result.raw());
      if (result.usage() != null && !result.usage().isNull()) {
        Map<String, Object> usagePayload = new LinkedHashMap<>();
        usagePayload.put("ts", System.currentTimeMillis());
        usagePayload.put("usage", result.usage());
        sink.trace("llm.usage", usagePayload);
      }

      JsonNode toolCalls = result.toolCalls();
      boolean hasTools =
          toolCalls != null && toolCalls.isArray() && toolCalls.size() > 0;
      if (!hasTools) {
        String text = result.content() != null ? result.content() : "";
        return text;
      }

      Map<String, Object> callsPayload = new LinkedHashMap<>();
      callsPayload.put("ts", System.currentTimeMillis());
      callsPayload.put("round", round);
      callsPayload.put("tool_calls", toolCalls);
      sink.trace("agent.tool_calls", callsPayload);

      String assistantContent = result.content();
      if (assistantContent != null && assistantContent.isBlank()) {
        assistantContent = null;
      }
      conversation.add(ChatMessage.assistantWithToolCalls(assistantContent, toolCalls));

      for (JsonNode tc : toolCalls) {
        String id = tc.path("id").asText("");
        JsonNode fn = tc.path("function");
        String fnName = fn.path("name").asText("");
        String args = fn.path("arguments").asText("");
        if (args == null || args.isBlank()) {
          args = "{}";
        }
        String output = registry.execute(fnName, args);
        Map<String, Object> resPayload = new LinkedHashMap<>();
        resPayload.put("ts", System.currentTimeMillis());
        resPayload.put("round", round);
        resPayload.put("toolCallId", id);
        resPayload.put("name", fnName);
        resPayload.put(
            "content",
            output.length() > TOOL_RESULT_TRACE_MAX_CHARS
                ? output.substring(0, TOOL_RESULT_TRACE_MAX_CHARS) + "…"
                : output);
        sink.trace("agent.tool_result", resPayload);
        conversation.add(ChatMessage.tool(id, fnName, output));
      }
    }
    return "{\"error\":\"max_tool_rounds_exceeded\",\"maxRounds\":" + maxToolRounds + "}";
  }

  private static void traceLlmRequest(
      AgentTraceSink sink,
      LlmInvocationParams params,
      List<ChatMessage> conversation,
      Object tools,
      Object toolChoice) {
    List<Map<String, Object>> maps = new ArrayList<>();
    for (ChatMessage cm : conversation) {
      maps.add(OpenAiCompatibleChatClient.messageToRequestMap(cm));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ts", System.currentTimeMillis());
    payload.put("model", params.model());
    payload.put("chatCompletionsUrl", params.chatCompletionsUrl());
    payload.put("temperature", params.temperature());
    payload.put("maxTokens", params.maxTokens());
    payload.put("messages", maps);
    payload.put("tools", tools);
    payload.put("toolChoice", toolChoice);
    sink.trace("llm.request", payload);
  }

  private static void traceLlmResponse(AgentTraceSink sink, JsonNode raw) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ts", System.currentTimeMillis());
    payload.put("raw", raw);
    sink.trace("llm.response", payload);
  }
}
