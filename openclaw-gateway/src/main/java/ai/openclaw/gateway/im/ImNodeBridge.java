package ai.openclaw.gateway.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends outbound messages through Node gateway's channel plugins (Telegram, Discord, Slack, etc.)
 * via WebSocket-based JSON-RPC protocol.
 *
 * <p>Java gateway acts as an "operator" client to the Node gateway, sending {@code chat.send}
 * requests that the Node gateway routes to the appropriate channel plugin for delivery.
 *
 * <p>Configuration (env vars):
 * <ul>
 *   <li>{@code OPENCLAW_NODE_GATEWAY_URL} — Node gateway URL (default: ws://127.0.0.1:18789/ws)
 *   <li>{@code OPENCLAW_GATEWAY_TOKEN} — auth token for Node gateway
 * </ul>
 */
@Service
public final class ImNodeBridge {

  private static final Logger log = LoggerFactory.getLogger(ImNodeBridge.class);
  private static volatile ImNodeBridge INSTANCE;

  private final String nodeGatewayUrl;
  private final String authToken;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient httpClient;

  private volatile WebSocket ws = null;
  private volatile boolean wsConnecting = false;
  private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pending =
      new ConcurrentHashMap<>();

  public ImNodeBridge() {
    this.nodeGatewayUrl = System.getenv("OPENCLAW_NODE_GATEWAY_URL");
    this.authToken = System.getenv("OPENCLAW_GATEWAY_TOKEN");
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    INSTANCE = this;
  }

  public static ImNodeBridge get() {
    return INSTANCE;
  }

  public boolean isEnabled() {
    return nodeGatewayUrl != null && !nodeGatewayUrl.isBlank();
  }

  private String getNodeGatewayUrl() {
    return nodeGatewayUrl != null ? nodeGatewayUrl : "ws://127.0.0.1:18789/ws";
  }

  private String getAuthToken() {
    return authToken;
  }

  /**
   * Sends a message through a Node gateway channel plugin.
   *
   * @param channel    channel id (e.g. "telegram", "discord", "slack")
   * @param to         channel-specific recipient
   * @param text       message text
   * @param sessionKey optional session key (can be null)
   */
  public CompletableFuture<ImSendResult> sendMessage(
      String channel, String to, String text, String sessionKey) {
    if (!isEnabled()) {
      return CompletableFuture.completedFuture(
          new ImSendResult(false, null, "IM bridge not configured (OPENCLAW_NODE_GATEWAY_URL not set)"));
    }

    return connectAsync()
        .thenCompose(ws -> sendChatSend(ws, channel, to, text, sessionKey))
        .exceptionally(ex -> new ImSendResult(false, null, "send failed: " + ex.getMessage()));
  }

  private CompletableFuture<WebSocket> connectAsync() {
    WebSocket existing = ws;
    if (existing != null && !existing.isInputClosed()) {
      return CompletableFuture.completedFuture(existing);
    }
    if (wsConnecting) {
      return waitForConnection();
    }
    wsConnecting = true;

    CompletableFuture<WebSocket> result = new CompletableFuture<>();
    httpClient.newWebSocketBuilder()
        .buildAsync(URI.create(getNodeGatewayUrl()), new WebSocket.Listener() {
          private volatile boolean connectFrameSent = false;

          @Override
          public void onOpen(WebSocket webSocket) {
            log.debug("IM bridge WS connected");
            sendConnectFrame(webSocket);
            webSocket.request(1);
          }

          @Override
          public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            handleMessage(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public void onError(WebSocket webSocket, Throwable error) {
            log.warn("IM bridge WS error: {}", error.getMessage());
            ws = null;
            wsConnecting = false;
            result.completeExceptionally(error);
          }

          @Override
          public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.debug("IM bridge WS closed: {} {}", statusCode, reason);
            ws = null;
            wsConnecting = false;
            pending.forEach((id, f) -> f.completeExceptionally(
                new RuntimeException("connection closed: " + statusCode)));
            pending.clear();
            return CompletableFuture.completedFuture(null);
          }

          private void sendConnectFrame(WebSocket ws) {
            if (connectFrameSent) return;
            connectFrameSent = true;
            try {
              Map<String, Object> device = Map.of(
                  "id", "java-im-bridge",
                  "publicKey", "n/a",
                  "signature", "n/a",
                  "signedAt", 0);
              Map<String, Object> client = Map.of(
                  "id", "im-bridge",
                  "version", "1.0.0",
                  "platform", "java",
                  "mode", "bridge");
              Map<String, Object> auth = getAuthToken() != null && !getAuthToken().isBlank()
                  ? Map.of("token", getAuthToken())
                  : Map.of();
              Map<String, Object> params = Map.of(
                  "client", client,
                  "minProtocol", 1,
                  "maxProtocol", 1,
                  "role", "operator",
                  "scopes", new String[] {"operator.admin", "operator.read", "operator.write"},
                  "device", device,
                  "auth", auth);
              Map<String, Object> frame = Map.of(
                  "type", "req",
                  "id", UUID.randomUUID().toString(),
                  "method", "connect",
                  "params", params);
              ws.sendText(json.writeValueAsString(frame), false)
                  .exceptionally(ex -> {
                    log.warn("connect frame failed: {}", ex.getMessage());
                    return null;
                  });
            } catch (Exception e) {
              log.warn("failed to build connect frame: {}", e.getMessage());
            }
          }
        })
        .whenComplete((wsResult, ex) -> {
          if (ex != null) {
            wsConnecting = false;
          } else {
            ws = wsResult;
          }
        });

    return result;
  }

  private CompletableFuture<WebSocket> waitForConnection() {
    long deadline = System.currentTimeMillis() + 10_000;
    return CompletableFuture.supplyAsync(() -> {
      while (System.currentTimeMillis() < deadline) {
        WebSocket current = ws;
        if (current != null && !current.isInputClosed()) {
          return current;
        }
        if (!wsConnecting) {
          try {
            return connectAsync().get();
          } catch (Exception ignored) {}
        }
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
      }
      throw new RuntimeException("connection timeout");
    });
  }

  private void handleMessage(String text) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> msg = json.readValue(text, Map.class);
      String type = String.valueOf(msg.get("type"));
      String id = msg.get("id") != null ? String.valueOf(msg.get("id")) : null;

      if ("res".equals(type) && id != null) {
        CompletableFuture<Map<String, Object>> f = pending.remove(id);
        if (f != null) {
          @SuppressWarnings("unchecked")
          Map<String, Object> payload = (Map<String, Object>) msg.get("payload");
          f.complete(payload != null ? payload : Map.of());
        }
      } else if ("evt".equals(type)) {
        log.debug("IM bridge event: {}", msg.get("event"));
      }
    } catch (Exception e) {
      log.warn("IM bridge parse error: {}", e.getMessage());
    }
  }

  private CompletableFuture<ImSendResult> sendChatSend(
      WebSocket ws, String channel, String to, String text, String sessionKey) {
    String id = UUID.randomUUID().toString();
    CompletableFuture<Map<String, Object>> respFuture = new CompletableFuture<>();
    pending.put(id, respFuture);

    try {
      java.util.LinkedHashMap<String, Object> params = new java.util.LinkedHashMap<>();
      params.put("channel", channel);
      params.put("to", to);
      params.put("message", text);
      params.put("idempotencyKey", "im-" + id);
      if (sessionKey != null) params.put("sessionKey", sessionKey);

      java.util.LinkedHashMap<String, Object> frame = new java.util.LinkedHashMap<>();
      frame.put("type", "req");
      frame.put("id", id);
      frame.put("method", "chat.send");
      frame.put("params", params);

      ws.sendText(json.writeValueAsString(frame), false)
          .exceptionally(ex -> {
            pending.remove(id);
            respFuture.completeExceptionally(ex);
            return null;
          });

      return respFuture.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
          .thenApply(payload -> new ImSendResult(true, null, null))
          .exceptionally(ex -> new ImSendResult(false, null, "timeout or error: " + ex.getMessage()));
    } catch (Exception e) {
      pending.remove(id);
      return CompletableFuture.completedFuture(new ImSendResult(false, null, e.getMessage()));
    }
  }

  public record ImSendResult(boolean ok, String messageId, String error) {}
}