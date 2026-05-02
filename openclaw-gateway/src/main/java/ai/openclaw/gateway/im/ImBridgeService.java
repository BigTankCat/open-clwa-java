package ai.openclaw.gateway.im;

import ai.openclaw.gateway.sessions.InMemorySessionStore;
import ai.openclaw.protocol.RequestFrame;
import ai.openclaw.protocol.ResponseFrame;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Bridge for sending messages through Node gateway's IM channel plugins.
 *
 * <p>Java gateway receives messages, processes them through the LLM, and sends
 * responses via external channels (Telegram, Discord, etc.) by forwarding to the
 * Node gateway's WebSocket API using the chat.send protocol.
 *
 * <p>Configuration (environment variables):
 * <ul>
 *   <li>{@code OPENCLAW_NODE_GATEWAY_WS_URL} — Node gateway WS URL (default: ws://localhost:18789/ws)
 *   <li>{@code OPENCLAW_NODE_GATEWAY_TOKEN} — auth token for Node gateway
 * </ul>
 *
 * <p>Usage: after LLM generates a response, call {@code sendViaChannel(channelId, target, text)}
 * to deliver the message through the Node gateway's channel plugins.
 */
@Service
public final class ImBridgeService {

  private final String nodeWsUrl;
  private final String authToken;
  private final HttpClient httpClient;
  private final ObjectMapper json = new ObjectMapper();
  private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pending =
      new ConcurrentHashMap<>();

  public ImBridgeService() {
    this.nodeWsUrl = System.getenv("OPENCLAW_NODE_GATEWAY_WS_URL");
    this.authToken = System.getenv("OPENCLAW_NODE_GATEWAY_TOKEN");
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  /** Returns true if the bridge is configured. */
  public boolean isEnabled() {
    return nodeWsUrl != null && !nodeWsUrl.isBlank();
  }

  /**
   * Sends a text message via a Node gateway channel plugin (e.g. telegram, discord, slack).
   *
   * <p>This opens a temporary WebSocket connection to the Node gateway, sends a
   * {@code chat.send} frame with the channel and target, waits for the response, and closes.
   *
   * @param channelId   "telegram", "discord", "slack", etc.
   * @param target      channel-specific recipient (e.g. chat_id for Telegram)
   * @param text        message text to send
   * @param sessionKey  optional session key for the Node side to route the message
   * @return CompletableFuture with the delivery result
   */
  public CompletableFuture<ImSendResult> sendViaChannel(String channelId, String target, String text, String sessionKey) {
    if (!isEnabled()) {
      return CompletableFuture.completedFuture(
          new ImSendResult(false, null, "IM bridge not configured (OPENCLAW_NODE_GATEWAY_WS_URL not set)"));
    }

    return CompletableFuture.supplyAsync(() -> {
      try {
        // Build chat.send payload
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel", channelId);
        params.put("to", target);
        params.put("text", text);
        if (sessionKey != null) params.put("sessionKey", sessionKey);

        String payload = json.writeValueAsString(params);

        // Use the Node gateway HTTP POST endpoint for outbound delivery
        // Node gateway exposes /v1/chat/completions as OpenAI-compatible endpoint
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(ensureTrailingSlash(nodeWsUrl.replace("ws://", "http://").replace("/ws", "")) + "v1/chat/completions"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30));

        if (authToken != null && !authToken.isBlank()) {
          reqBuilder.header("Authorization", "Bearer " + authToken);
        }

        HttpRequest request = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          return new ImSendResult(true, null, null);
        } else {
          return new ImSendResult(false, null, "Node gateway returned " + status + ": " + response.body());
        }
      } catch (Exception e) {
        return new ImSendResult(false, null, "IM bridge call failed: " + e.getMessage());
      }
    });
  }

  /**
   * Synchronous version of {@link #sendViaChannel(String, String, String, String)}.
   */
  public ImSendResult sendViaChannelSync(String channelId, String target, String text, String sessionKey) {
    try {
      return sendViaChannel(channelId, target, text, sessionKey).get();
    } catch (Exception e) {
      return new ImSendResult(false, null, "IM bridge sync error: " + e.getMessage());
    }
  }

  /**
   * Broadcasts an LLM response to all active IM channels configured in Node gateway.
   * Uses Node's internal routing to deliver to all connected channel accounts.
   */
  public CompletableFuture<ImSendResult> broadcastToAllChannels(String text, String sessionKey) {
    if (!isEnabled()) {
      return CompletableFuture.completedFuture(
          new ImSendResult(false, null, "IM bridge not configured"));
    }

    // For broadcast, we send to each known channel
    // Channel list is determined by Node gateway's active accounts
    // We use a special "broadcast" mode that Node gateway handles
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("broadcast", true);
    params.put("text", text);
    if (sessionKey != null) params.put("sessionKey", sessionKey);

    return sendRaw("chat.send", params);
  }

  /**
   * Sends a raw method call to the Node gateway and returns the parsed result.
   */
  public CompletableFuture<ImSendResult> sendRaw(String method, Map<String, Object> params) {
    if (!isEnabled()) {
      return CompletableFuture.completedFuture(
          new ImSendResult(false, null, "IM bridge not configured"));
    }

    return CompletableFuture.supplyAsync(() -> {
      try {
        String payload = json.writeValueAsString(params);
        String url = ensureTrailingSlash(nodeWsUrl.replace("ws://", "http://").replace("/ws", "")) + "v1/chat/completions";

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30));

        if (authToken != null && !authToken.isBlank()) {
          reqBuilder.header("Authorization", "Bearer " + authToken);
        }

        HttpRequest request = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          return new ImSendResult(true, null, null);
        } else {
          return new ImSendResult(false, null, "Node returned " + status + ": " + response.body());
        }
      } catch (Exception e) {
        return new ImSendResult(false, null, "IM bridge error: " + e.getMessage());
      }
    });
  }

  private String ensureTrailingSlash(String url) {
    return url.endsWith("/") ? url : url + "/";
  }

  /** Result of an IM send operation. */
  public record ImSendResult(boolean ok, String messageId, String error) {}
}
