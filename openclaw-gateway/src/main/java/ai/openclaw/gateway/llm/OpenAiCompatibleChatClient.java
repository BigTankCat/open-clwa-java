package ai.openclaw.gateway.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal OpenAI-compatible Chat Completions client.
 *
 * Expected response shape:
 * - choices[0].message.content
 * - choices[0].message.tool_calls (optional)
 * - usage (optional)
 */
public final class OpenAiCompatibleChatClient {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public record ChatMessage(String role, String content) {}

  public record ChatResult(String content, JsonNode toolCalls, JsonNode usage, JsonNode raw) {}

  private final HttpClient httpClient;

  public OpenAiCompatibleChatClient() {
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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

    Map<String, Object> body =
        new java.util.LinkedHashMap<>(
            Map.of(
                "model",
                model,
                "messages",
                messages.stream()
                    .map((m) -> Map.of("role", m.role, "content", m.content))
                    .toList(),
                "temperature",
                temperature != null ? temperature : 0.2,
                "stream",
                false));
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
    JsonNode message = firstChoice.isArray() && firstChoice.size() > 0 ? firstChoice.get(0).path("message") : null;
    String content = message != null ? message.path("content").asText("") : "";
    JsonNode toolCalls = message != null ? message.get("tool_calls") : null;
    JsonNode usage = raw.get("usage");

    return new ChatResult(content, toolCalls, usage, raw);
  }
}

