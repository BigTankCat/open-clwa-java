package ai.openclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal OpenAI-compatible Chat Completions client.
 *
 * <p>Expected response shape:
 *
 * <ul>
 *   <li>choices[0].message.content
 *   <li>choices[0].message.tool_calls (optional)
 *   <li>usage (optional)
 * </ul>
 */
public final class OpenAiCompatibleChatClient {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Chat message for request bodies. Use {@link #user(String)}, {@link #assistantText(String)},
   * {@link #assistantWithToolCalls(String, JsonNode)}, {@link #tool(String, String, String)}.
   */
  public record ChatMessage(
      String role,
      String content,
      JsonNode toolCalls,
      String toolCallId,
      String name) {

    public static ChatMessage system(String content) {
      return new ChatMessage("system", content, null, null, null);
    }

    public static ChatMessage user(String content) {
      return new ChatMessage("user", content, null, null, null);
    }

    public static ChatMessage assistantText(String content) {
      return new ChatMessage("assistant", content, null, null, null);
    }

    /** Assistant message that only carries {@code tool_calls} (content may be null). */
    public static ChatMessage assistantWithToolCalls(String content, JsonNode toolCalls) {
      return new ChatMessage("assistant", content, toolCalls, null, null);
    }

    /** Tool result message (OpenAI chat completions). */
    public static ChatMessage tool(String toolCallId, String name, String content) {
      return new ChatMessage("tool", content, null, toolCallId, name);
    }
  }

  public record ChatResult(String content, JsonNode toolCalls, JsonNode usage, JsonNode raw) {}

  private final HttpClient httpClient;

  public OpenAiCompatibleChatClient() {
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  static Map<String, Object> messageToRequestMap(ChatMessage m) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("role", m.role());
    boolean hasToolCalls =
        m.toolCalls() != null
            && !m.toolCalls().isNull()
            && m.toolCalls().isArray()
            && m.toolCalls().size() > 0;
    if (m.content() != null && !m.content().isBlank()) {
      map.put("content", m.content());
    } else if (!"assistant".equals(m.role()) || !hasToolCalls) {
      // Non-assistant or assistant without tool_calls: send empty string for compatibility.
      map.put("content", m.content() != null ? m.content() : "");
    }
    if (hasToolCalls) {
      map.put("tool_calls", m.toolCalls());
    }
    if (m.toolCallId() != null && !m.toolCallId().isBlank()) {
      map.put("tool_call_id", m.toolCallId());
    }
    if (m.name() != null && !m.name().isBlank()) {
      map.put("name", m.name());
    }
    return map;
  }

  public ChatResult chatCompletions(
      String chatCompletionsUrl,
      String apiKey,
      String model,
      List<ChatMessage> messages,
      Double temperature,
      Integer maxTokens,
      Object tools,
      Object toolChoice)
      throws Exception {
    if (chatCompletionsUrl == null || chatCompletionsUrl.isBlank()) {
      throw new IllegalArgumentException("chatCompletionsUrl required");
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("apiKey required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model required");
    }

    List<Map<String, Object>> messageMaps =
        messages.stream().map(OpenAiCompatibleChatClient::messageToRequestMap).toList();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", messageMaps);
    body.put("temperature", temperature != null ? temperature : 0.2);
    body.put("stream", false);
    if (maxTokens != null) {
      body.put("max_tokens", maxTokens);
    }
    if (tools != null) {
      body.put("tools", tools);
    }
    if (toolChoice != null) {
      body.put("tool_choice", toolChoice);
    }

    String json = MAPPER.writeValueAsString(body);

    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(chatCompletionsUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

    HttpResponse<String> resp =
        httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() / 100 != 2) {
      throw new RuntimeException(
          "LLM request failed: status=" + resp.statusCode() + " body=" + resp.body());
    }

    JsonNode raw = MAPPER.readTree(resp.body());
    JsonNode firstChoice = raw.path("choices");
    JsonNode message =
        firstChoice.isArray() && firstChoice.size() > 0
            ? firstChoice.get(0).path("message")
            : null;
    String content = message != null ? textOrEmpty(message.path("content")) : "";
    JsonNode toolCalls = message != null ? message.get("tool_calls") : null;
    JsonNode usage = raw.get("usage");

    return new ChatResult(content, toolCalls, usage, raw);
  }

  private static String textOrEmpty(JsonNode n) {
    if (n == null || n.isNull()) {
      return "";
    }
    if (n.isTextual()) {
      return n.asText("");
    }
    return n.toString();
  }
}
