package ai.openclaw.gateway.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java-only smoke test client for the local gateway.
 *
 * Run after starting the Spring Boot gateway:
 *  1) mvn spring-boot:run (in openclaw-gateway)
 *  2) run this main class with your preferred classpath setup
 *
 * Purpose: validate the minimal JSON-RPC flow:
 * connect -> health -> config.get -> sessions.create -> chat.send -> node.invoke/node.pending.pull/node.invoke.result
 */
public final class JavaGatewayLocalSmokeClient {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final ConcurrentHashMap<String, CompletableFuture<JsonNode>> PENDING =
      new ConcurrentHashMap<>();
  private static final AtomicBoolean SHUTDOWN = new AtomicBoolean(false);

  public static void main(String[] args) {
    String url = System.getenv().getOrDefault("JAVA_GATEWAY_WS_URL", "ws://127.0.0.1:18789/ws");
    try {
      WebSocket ws =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .connectTimeout(Duration.ofSeconds(5))
              .buildAsync(
                  URI.create(url),
                  new WebSocket.Listener() {
                    @Override
                    public java.util.concurrent.CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                      String text = data.toString();
                      try {
                        JsonNode obj = MAPPER.readTree(text);
                        String type = obj.path("type").asText();
                        if ("res".equals(type) && obj.hasNonNull("id")) {
                          String id = obj.get("id").asText();
                          CompletableFuture<JsonNode> f = PENDING.get(id);
                          if (f != null) f.complete(obj);
                        }
                      } catch (Exception ignored) {
                        // ignore malformed frames
                      }
                      webSocket.request(1);
                      return CompletableFuture.completedFuture(null);
                    }
                  })
              .join();

      run(ws);
      System.out.println("SUCCESS: java gateway local chain ok.");
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    } finally {
      SHUTDOWN.set(true);
    }
  }

  private static void run(WebSocket ws) throws Exception {
    String nodeId = "node-1";

    JsonNode connect = sendReq(ws, "connect", buildConnectParams(nodeId), 8000);
    expectOk(connect, "connect");

    JsonNode health = sendReq(ws, "health", Map.of("probe", true), 8000);
    expectOk(health, "health");

    JsonNode configGet = sendReq(ws, "config.get", Map.of(), 8000);
    expectOk(configGet, "config.get");

    JsonNode sessionsCreate =
        sendReq(
            ws,
            "sessions.create",
            Map.of(
                "agentId", "default",
                "label", "smoke",
                "model", "unknown",
                "message", "hello from java gateway"),
            8000);
    expectOk(sessionsCreate, "sessions.create");
    JsonNode payload = sessionsCreate.path("payload");
    String sessionKey = payload.path("key").asText(null);
    if (sessionKey == null || sessionKey.isBlank()) {
      throw new IllegalStateException("sessions.create: missing payload.key");
    }

    JsonNode chatSend =
        sendReq(
            ws, "chat.send", Map.of("sessionKey", sessionKey, "message", "second message"), 8000);
    expectOk(chatSend, "chat.send");

    // node.invoke waits for node.invoke.result; run it in parallel.
    CompletableFuture<JsonNode> invokeF =
        sendReqAsync(
            ws,
            "node.invoke",
            Map.of(
                "nodeId",
                nodeId,
                "command",
                "test.command",
                "params",
                Map.of(),
                "timeoutMs",
                5000,
                "idempotencyKey",
                "inv-1"),
            12_000);

    // node pending/pull -> ack -> invoke.result
    Thread.sleep(50);
    JsonNode pull = sendReq(ws, "node.pending.pull", Map.of(), 8000);
    expectOk(pull, "node.pending.pull");
    JsonNode actions = pull.path("payload").path("actions");
    if (!actions.isArray() || actions.size() == 0) {
      throw new IllegalStateException("node.pending.pull: no actions returned");
    }
    String pendingId = actions.get(0).path("id").asText(null);
    if (pendingId == null || pendingId.isBlank()) {
      throw new IllegalStateException("node.pending.pull: missing actions[0].id");
    }

    JsonNode ack =
        sendReq(ws, "node.pending.ack", Map.of("ids", new String[] {pendingId}), 8000);
    expectOk(ack, "node.pending.ack");

    JsonNode invokeResult =
        sendReq(
            ws,
            "node.invoke.result",
            Map.of(
                "id", pendingId,
                "nodeId", nodeId,
                "ok", true,
                "payload", Map.of("runId", "run-1")),
            8000);
    expectOk(invokeResult, "node.invoke.result");

    JsonNode invokeRes = invokeF.get(13, TimeUnit.SECONDS);
    expectOk(invokeRes, "node.invoke");
  }

  private static Map<String, Object> buildConnectParams(String nodeId) {
    Map<String, Object> device = new HashMap<>();
    device.put("id", nodeId);
    device.put("publicKey", "x");
    device.put("signature", "x");
    device.put("signedAt", 0);

    Map<String, Object> client = new HashMap<>();
    client.put("id", "webchat");
    client.put("version", "1.0.0");
    client.put("platform", "test");
    client.put("mode", "test");

    Map<String, Object> auth = new HashMap<>();
    // Java gateway auth checks `auth.token` or `auth.password`.
    String token = System.getenv().getOrDefault("OPENCLAW_GATEWAY_TOKEN", "").trim();
    if (!token.isEmpty()) {
      auth.put("token", token);
    } else {
      String password = System.getenv().getOrDefault("OPENCLAW_GATEWAY_PASSWORD", "").trim();
      if (!password.isEmpty()) auth.put("password", password);
    }
    Map<String, Object> connectParamsAuth = auth.isEmpty() ? null : auth;

    return Map.of(
        "client", client,
        "minProtocol", 1,
        "maxProtocol", 1,
        "role", "operator",
        "scopes", new String[] {"operator.admin", "operator.read", "operator.write"},
        "device", device,
        "auth", connectParamsAuth == null ? Map.of() : connectParamsAuth);
  }

  private static CompletableFuture<JsonNode> sendReqAsync(
      WebSocket ws, String method, Object params, long timeoutMs) {
    String id = UUID.randomUUID().toString();
    CompletableFuture<JsonNode> f = new CompletableFuture<>();
    PENDING.put(id, f);
    try {
      String req =
          MAPPER.writeValueAsString(
              Map.of(
                  "type", "req",
                  "id", id,
                  "method", method,
                  "params", params == null ? Map.of() : params));
      ws.sendText(req, true);
    } catch (Exception e) {
      PENDING.remove(id);
      f.completeExceptionally(e);
      return f;
    }
    return f.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((v, t) -> PENDING.remove(id));
  }

  private static JsonNode sendReq(WebSocket ws, String method, Object params, long timeoutMs)
      throws TimeoutException, Exception {
    String id = UUID.randomUUID().toString();
    CompletableFuture<JsonNode> f = new CompletableFuture<>();
    PENDING.put(id, f);
    try {
      String req =
          MAPPER.writeValueAsString(
              Map.of(
                  "type",
                  "req",
                  "id",
                  id,
                  "method",
                  method,
                  "params",
                  params == null ? Map.of() : params));
      ws.sendText(req, true);
    } catch (Exception e) {
      PENDING.remove(id);
      throw e;
    }
    try {
      JsonNode out = f.get(timeoutMs, TimeUnit.MILLISECONDS);
      return out;
    } finally {
      PENDING.remove(id);
    }
  }

  private static void expectOk(JsonNode res, String ctx) {
    if (res == null || !res.has("ok")) {
      throw new IllegalStateException("invalid response for " + ctx + ": " + res);
    }
    boolean ok = res.path("ok").asBoolean(false);
    if (!ok) {
      JsonNode err = res.get("error");
      String code = err == null ? "unknown" : err.path("code").asText("unknown");
      String msg = err == null ? "unknown" : err.path("message").asText("unknown");
      throw new IllegalStateException("gateway failed for " + ctx + ": " + code + " " + msg);
    }
  }
}

