package ai.openclaw.gateway.ws;

import ai.openclaw.config.ConfigLoader;
import ai.openclaw.config.ConfigSnapshot;
import ai.openclaw.config.ConfigWriter;
import ai.openclaw.config.ConfigParsers;
import ai.openclaw.gateway.auth.MethodScopes;
import ai.openclaw.config.ConfigMergePatch;
import ai.openclaw.config.ConfigEnvRestorer;
import ai.openclaw.protocol.EventFrame;
import ai.openclaw.gateway.sessions.InMemorySessionStore;
import ai.openclaw.protocol.ErrorCodes;
import ai.openclaw.protocol.ErrorShape;
import ai.openclaw.protocol.RequestFrame;
import ai.openclaw.protocol.ResponseFrame;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket JSON-RPC handler: connect, health, config.get.
 * Auth: Bearer token from query or first message (connect params); aligns with Node gateway auth.
 */
@Component
public class GatewayWebSocketHandler extends TextWebSocketHandler {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long HEALTH_REFRESH_INTERVAL_MS = 60_000;
  private static final Set<String> EVENT_SLOTS =
      Set.of("sessions.changed", "sessions.messages");

  // Align with Node server-methods-list.ts:
  // - BASE_METHODS are advertised in hello-ok.features.methods.
  // - GATEWAY_EVENTS are advertised in hello-ok.features.events.
  private static final List<String> NODE_BASE_METHODS =
      List.of(
          "health",
          "doctor.memory.status",
          "logs.tail",
          "channels.status",
          "channels.logout",
          "status",
          "usage.status",
          "usage.cost",
          "tts.status",
          "tts.providers",
          "tts.enable",
          "tts.disable",
          "tts.convert",
          "tts.setProvider",
          "config.get",
          "config.set",
          "config.apply",
          "config.patch",
          "config.schema",
          "config.schema.lookup",
          "exec.approvals.get",
          "exec.approvals.set",
          "exec.approvals.node.get",
          "exec.approvals.node.set",
          "exec.approval.request",
          "exec.approval.waitDecision",
          "exec.approval.resolve",
          "wizard.start",
          "wizard.next",
          "wizard.cancel",
          "wizard.status",
          "talk.config",
          "talk.mode",
          "models.list",
          "tools.catalog",
          "agents.list",
          "agents.create",
          "agents.update",
          "agents.delete",
          "agents.files.list",
          "agents.files.get",
          "agents.files.set",
          "skills.status",
          "skills.bins",
          "skills.install",
          "skills.update",
          "update.run",
          "voicewake.get",
          "voicewake.set",
          "secrets.reload",
          "secrets.resolve",
          "sessions.list",
          "sessions.subscribe",
          "sessions.unsubscribe",
          "sessions.messages.subscribe",
          "sessions.messages.unsubscribe",
          "sessions.preview",
          "sessions.create",
          "sessions.send",
          "sessions.abort",
          "sessions.patch",
          "sessions.reset",
          "sessions.delete",
          "sessions.compact",
          "last-heartbeat",
          "set-heartbeats",
          "wake",
          "node.pair.request",
          "node.pair.list",
          "node.pair.approve",
          "node.pair.reject",
          "node.pair.verify",
          "device.pair.list",
          "device.pair.approve",
          "device.pair.reject",
          "device.pair.remove",
          "device.token.rotate",
          "device.token.revoke",
          "node.rename",
          "node.list",
          "node.describe",
          "node.pending.drain",
          "node.pending.enqueue",
          "node.invoke",
          "node.pending.pull",
          "node.pending.ack",
          "node.invoke.result",
          "node.event",
          "node.canvas.capability.refresh",
          "cron.list",
          "cron.status",
          "cron.add",
          "cron.update",
          "cron.remove",
          "cron.run",
          "cron.runs",
          "gateway.identity.get",
          "system-presence",
          "system-event",
          "send",
          "agent",
          "agent.identity.get",
          "agent.wait",
          "browser.request",
          "chat.history",
          "chat.abort",
          "chat.send");

  // Note: Node base methods list doesn't include `poll` (it is implemented as a gateway
  // method, but not part of base feature negotiation). We still advertise it for compatibility.
  private static final String POLL_METHOD = "poll";

  private static final List<String> NODE_GATEWAY_EVENTS =
      List.of(
          "connect.challenge",
          "agent",
          "chat",
          "session.message",
          "session.tool",
          "sessions.changed",
          "presence",
          "tick",
          "talk.mode",
          "shutdown",
          "health",
          "heartbeat",
          "cron",
          "node.pair.requested",
          "node.pair.resolved",
          "device.pair.requested",
          "device.pair.resolved",
          "voicewake.changed",
          "exec.approval.requested",
          "exec.approval.resolved",
          "update.available");

  private static final List<String> FEATURE_METHODS;
  private static final Set<String> FEATURE_METHODS_SET;
  private static final List<String> FEATURE_EVENTS;

  static {
    LinkedHashSet<String> methods = new LinkedHashSet<>(NODE_BASE_METHODS);
    methods.add("connect");
    methods.add(POLL_METHOD);
    FEATURE_METHODS = List.copyOf(methods);
    FEATURE_METHODS_SET = Set.copyOf(methods);

    LinkedHashSet<String> events = new LinkedHashSet<>(NODE_GATEWAY_EVENTS);
    // Java port emits/declares these transcript events.
    events.addAll(EVENT_SLOTS);
    FEATURE_EVENTS = List.copyOf(events);
  }

  private static final ConcurrentHashMap<String, WebSocketSession> ACTIVE_SESSIONS =
      new ConcurrentHashMap<>();

  private static final ConcurrentHashMap<String, WsContext> CONTEXTS_BY_CONN_ID =
      new ConcurrentHashMap<>();

  @Value("${openclaw.version:2026.3.14}")
  private String version;

  private final long startedAtMs = System.currentTimeMillis();

  private final ConfigLoader configLoader;
  private final String gatewayToken;

  private interface WsMethodHandler {
    void handle(WebSocketSession session, RequestFrame req, Map<String, Object> params);
  }

  // Very small in-memory cache for health responses (first slice).
  // In Node this is in server-maintenance.ts; we mirror behavior for now.
  private volatile Map<String, Object> cachedHealthPayload;
  private volatile long cachedHealthTs;

  // Minimal in-memory node action queue + invoke-result waiting.
  private static final ConcurrentHashMap<String, ConcurrentLinkedQueue<PendingNodeAction>>
      NODE_PENDING_ACTIONS_BY_NODE_ID = new ConcurrentHashMap<>();
  private static final long NODE_PENDING_ACTION_TTL_MS = 10 * 60_000;
  private static final int NODE_PENDING_ACTION_MAX_PER_NODE = 64;
  private static final ConcurrentHashMap<String, CompletableFuture<NodeInvokeResolution>>
      NODE_INVOKE_WAITERS_BY_ID = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, PendingNodeInvokeMeta>
      NODE_INVOKE_META_BY_ID = new ConcurrentHashMap<>();

  private static final ExecutorService NODE_INVOKE_EXECUTOR =
      Executors.newCachedThreadPool(
          new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
              Thread t = new Thread(r);
              t.setDaemon(true);
              t.setName("openclaw-node-invoke-waiter");
              return t;
            }
          });

  private static final ConcurrentHashMap<String, Map<String, Object>> POLL_DEDUPE_BY_ID =
      new ConcurrentHashMap<>();

  // node.pending.enqueue/node.pending.drain (status.request/location.request) work store.
  // Separated from node.invoke queue (node.pending.pull/ack).
  private static final String DEFAULT_STATUS_ITEM_ID = "baseline-status";
  private static final String DEFAULT_STATUS_PRIORITY = "default";
  private static final String DEFAULT_WORK_PRIORITY = "normal";
  private static final int DEFAULT_NODE_PENDING_MAX_ITEMS = 4;
  private static final int MAX_NODE_PENDING_MAX_ITEMS = 10;
  private static final Map<String, Integer> PRIORITY_RANK =
      Map.of("high", 3, "normal", 2, "default", 1);

  private static final class PendingNodeDrainWorkState {
    long revision;
    final Map<String, PendingNodeDrainWork> itemsById = new HashMap<>();
  }

  private static final ConcurrentHashMap<String, PendingNodeDrainWorkState>
      NODE_DRAIN_STATE_BY_NODE_ID = new ConcurrentHashMap<>();

  private static final class PendingNodeAction {
    final String id;
    final String command;
    final String paramsJSON;
    final long enqueuedAtMs;

    PendingNodeAction(String id, String command, String paramsJSON, long enqueuedAtMs) {
      this.id = id;
      this.command = command;
      this.paramsJSON = paramsJSON;
      this.enqueuedAtMs = enqueuedAtMs;
    }
  }

  private static final class PendingNodeInvokeMeta {
    final String nodeId;
    final String command;

    PendingNodeInvokeMeta(String nodeId, String command) {
      this.nodeId = nodeId;
      this.command = command;
    }
  }

  private static final class NodeInvokeResolution {
    final boolean ok;
    final Object payload;
    final String payloadJSON;
    final ErrorShape error;

    NodeInvokeResolution(boolean ok, Object payload, String payloadJSON, ErrorShape error) {
      this.ok = ok;
      this.payload = payload;
      this.payloadJSON = payloadJSON;
      this.error = error;
    }
  }

  private static final class PendingNodeDrainWork {
    final String id;
    final String type;
    final String priority;
    final long createdAtMs;
    final Long expiresAtMs;

    PendingNodeDrainWork(
        String id, String type, String priority, long createdAtMs, Long expiresAtMs) {
      this.id = id;
      this.type = type;
      this.priority = priority;
      this.createdAtMs = createdAtMs;
      this.expiresAtMs = expiresAtMs;
    }
  }

  private final Map<String, WsMethodHandler> methodHandlers;
  private final ConfigWriter configWriter;
  private final InMemorySessionStore sessionStore;

  public GatewayWebSocketHandler(
      ConfigLoader configLoader,
      ConfigWriter configWriter,
      InMemorySessionStore sessionStore,
      Environment env) {
    this.configLoader = configLoader;
    this.configWriter = configWriter;
    this.sessionStore = sessionStore;
    this.gatewayToken = env.getProperty("OPENCLAW_GATEWAY_TOKEN", "");
    this.methodHandlers =
        Map.of(
            "health",
            (session, req, params) -> handleHealth(session, req, params),
            "poll",
            (session, req, params) -> handlePoll(session, req, params),
            "config.get",
            (session, req, params) -> handleConfigGet(session, req, params),
            "config.apply",
            (session, req, params) -> handleConfigApply(session, req, params),
            "config.patch",
            (session, req, params) -> handleConfigPatch(session, req, params),
            "node.invoke",
            (session, req, params) -> handleNodeInvoke(session, req, params),
            "node.invoke.result",
            (session, req, params) -> handleNodeInvokeResult(session, req, params),
            "node.event",
            (session, req, params) -> handleNodeEvent(session, req, params),
            "node.pending.drain",
            (session, req, params) -> handleNodePendingDrain(session, req, params),
            "node.pending.pull",
            (session, req, params) -> handleNodePendingPull(session, req, params),
            "node.pending.ack",
            (session, req, params) -> handleNodePendingAck(session, req, params),
            "node.pending.enqueue",
            (session, req, params) -> handleNodePendingEnqueue(session, req, params),
            "sessions.create",
            (session, req, params) -> handleSessionsCreate(session, req, params),
            "sessions.list",
            (session, req, params) -> handleSessionsList(session, req, params),
            "sessions.get",
            (session, req, params) -> handleSessionsGet(session, req, params),
            "sessions.delete",
            (session, req, params) -> handleSessionsDelete(session, req, params),
            "sessions.subscribe",
            (session, req, params) -> handleSessionsSubscribe(session, req, params),
            "sessions.unsubscribe",
            (session, req, params) -> handleSessionsUnsubscribe(session, req, params),
            "sessions.messages.subscribe",
            (session, req, params) -> handleSessionsMessagesSubscribe(session, req, params),
            "sessions.messages.unsubscribe",
            (session, req, params) -> handleSessionsMessagesUnsubscribe(session, req, params),
            "chat.send",
            (session, req, params) -> handleChatSend(session, req, params),
            "status",
            (session, req, params) -> handleStatus(session, req));
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    WsContext ctx = new WsContext();
    ctx.connId = session.getId();
    session.getAttributes().put(WsContext.KEY, ctx);
    ACTIVE_SESSIONS.put(ctx.connId, session);
    CONTEXTS_BY_CONN_ID.put(ctx.connId, ctx);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    String payload = message.getPayload();
    WsContext ctx = (WsContext) session.getAttributes().get(WsContext.KEY);
    if (ctx == null) {
      ctx = new WsContext();
      session.getAttributes().put(WsContext.KEY, ctx);
    }

    try {
      RequestFrame req = MAPPER.readValue(payload, RequestFrame.class);
      if (req == null || req.getId() == null || req.getMethod() == null) {
        sendResponse(session, req != null ? req.getId() : null, false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, "missing id or method"));
        return;
      }

      String method = req.getMethod();
      Map<String, Object> params = req.getParams();

      if ("connect".equals(method)) {
        handleConnect(session, ctx, req, params);
        return;
      }

      // Node behavior: "health" is allowed without connect.
      boolean isHealth = "health".equals(method);
      if (!ctx.connected && !isHealth) {
        sendResponse(
            session,
            req.getId(),
            false,
            null,
            ErrorShape.of(ErrorCodes.INVALID_REQUEST, "send connect first"));
        return;
      }

      // Only enforce operator scope for methods other than health.
      if (!isHealth) {
        if (ctx.role != null && "node".equalsIgnoreCase(ctx.role)) {
          // When gateway connection role is `node`, operator scopes are not required,
          // but only node-role methods are allowed.
          if (!MethodScopes.isNodeRoleMethod(method)) {
            sendResponse(
                session,
                req.getId(),
                false,
                null,
                ErrorShape.of(ErrorCodes.INVALID_REQUEST, "unauthorized role: node"));
            return;
          }
        } else {
          List<String> scopes = ctx.scopes;
          String missingScope = MethodScopes.authorize(method, scopes);
          if (missingScope != null) {
            sendResponse(
                session,
                req.getId(),
                false,
                null,
                ErrorShape.of(ErrorCodes.INVALID_REQUEST, "missing scope: " + missingScope));
            return;
          }
        }
      }

      WsMethodHandler handler = methodHandlers.get(method);
      if (handler == null) {
        if (FEATURE_METHODS_SET.contains(method)) {
          sendResponse(
              session,
              req.getId(),
              false,
              null,
              ErrorShape.of(ErrorCodes.UNAVAILABLE, "method not implemented: " + method));
        } else {
          sendResponse(
              session,
              req.getId(),
              false,
              null,
              ErrorShape.of(ErrorCodes.INVALID_REQUEST, "unknown method: " + method));
        }
        return;
      }
      handler.handle(session, req, params);
    } catch (Exception e) {
      sendResponse(session, null, false, null, ErrorShape.of(ErrorCodes.UNAVAILABLE, e.getMessage()));
    }
  }

  private void handleConnect(WebSocketSession session, WsContext ctx, RequestFrame req, Map<String, Object> params) {
    String token = tokenFromParams(params);
    if (gatewayToken != null && !gatewayToken.isBlank()) {
          if (token == null || !java.util.Objects.equals(token, gatewayToken)) {
            sendResponse(session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, "unauthorized"));
            return;
          }
        }
    ctx.connected = true;
    ctx.role = optionalNonEmptyString(params, "role");
    if (ctx.role == null) ctx.role = "operator";
    ctx.nodeId = resolveNodeIdFromConnectParams(params);
    ctx.scopes = scopesFromParams(params);
    if (ctx.scopes == null) {
      ctx.scopes = List.of(MethodScopes.READ_SCOPE, MethodScopes.WRITE_SCOPE, MethodScopes.ADMIN_SCOPE);
    }

    // Node snapshot schema expects required fields:
    // presence, health, stateVersion, uptimeMs.
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("presence", List.of());
    snapshot.put("health", healthPayload());
    snapshot.put("stateVersion", Map.of("presence", 0, "health", 0));
    snapshot.put("uptimeMs", Math.max(0, System.currentTimeMillis() - startedAtMs));
    snapshot.put("configPath", configWriter.getConfigPath());
    snapshot.put("stateDir", configLoader.getPaths().getStateDir());

    // HelloOkSchema.auth is optional, but if present it must include non-empty `deviceToken`.
    String deviceToken = deviceTokenFromConnectParams(params);
    Map<String, Object> auth = null;
    if (deviceToken != null) {
      auth =
          new LinkedHashMap<>(
              Map.of(
                  "deviceToken", deviceToken,
                  "role", ctx.role,
                  "scopes", ctx.scopes));
      // issuedAtMs is optional in schema; omit for now to keep payload minimal.
    }

    Map<String, Object> hello = new LinkedHashMap<>();
    hello.put("type", "hello-ok");
    hello.put("protocol", 1);
    hello.put("server", Map.of("version", version, "connId", ctx.connId));
    hello.put(
        "features",
        Map.of("methods", FEATURE_METHODS, "events", FEATURE_EVENTS));
    hello.put("snapshot", snapshot);
    hello.put(
        "policy",
        Map.of(
            "maxPayload", 25 * 1024 * 1024,
            "maxBufferedBytes", 50 * 1024 * 1024,
            "tickIntervalMs", 30_000));
    if (auth != null) {
      hello.put("auth", auth);
    }
    // Node behavior: connect (req) response payload is `hello-ok`.
    sendResponse(session, req.getId(), true, hello, null);
  }

  @SuppressWarnings("unchecked")
  private String deviceTokenFromConnectParams(Map<String, Object> params) {
    if (params == null) return null;
    Object auth = params.get("auth");
    if (!(auth instanceof Map)) return null;
    Object dt = ((Map<String, Object>) auth).get("deviceToken");
    if (!(dt instanceof String s)) return null;
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  @SuppressWarnings("unchecked")
  private String tokenFromParams(Map<String, Object> params) {
    if (params == null) return null;
    Object auth = params.get("auth");
    if (auth instanceof Map) {
      Object t = ((Map<String, Object>) auth).get("token");
      if (t instanceof String) return (String) t;
      Object p = ((Map<String, Object>) auth).get("password");
      if (p instanceof String) return (String) p;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private List<String> scopesFromParams(Map<String, Object> params) {
    if (params == null) return null;
    Object s = params.get("scopes");
    if (s instanceof List) {
      return (List<String>) s;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private String resolveNodeIdFromConnectParams(Map<String, Object> params) {
    if (params == null) return null;
    Object device = params.get("device");
    if (device instanceof Map) {
      Map<String, Object> dev = (Map<String, Object>) device;
      String id = optionalNonEmptyString(dev, "id");
      if (id != null) return id;
    }
    Object client = params.get("client");
    if (client instanceof Map) {
      Map<String, Object> c = (Map<String, Object>) client;
      String id = optionalNonEmptyString(c, "id");
      if (id != null) return id;
    }
    return null;
  }

  private void handleHealth(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    boolean wantsProbe = params != null && Boolean.TRUE.equals(params.get("probe"));
    long now = System.currentTimeMillis();
    Map<String, Object> cached = cachedHealthPayload;
    if (!wantsProbe && cached != null && now - cachedHealthTs < HEALTH_REFRESH_INTERVAL_MS) {
      // Return cached snapshot; include a small indicator similar to Node.
      Map<String, Object> withHint = Map.of(
          "ok", cached.get("ok"),
          "version", cached.get("version"),
          "ts", cached.get("ts"),
          "cached", true);
      sendResponse(session, req.getId(), true, withHint, null);
      return;
    }

    Map<String, Object> payload = healthPayload();
    cachedHealthPayload = payload;
    cachedHealthTs = now;
    sendResponse(session, req.getId(), true, payload, null);
  }

  private Map<String, Object> healthPayload() {
    return Map.of(
        "ok", true,
        "version", version,
        "ts", System.currentTimeMillis());
  }

  private void handleConfigGet(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    ConfigSnapshot snapshot = configLoader.load();
    Map<String, Object> payload = Map.of(
        "config", snapshot.getConfig(),
        "path", snapshot.getConfigPath() != null ? snapshot.getConfigPath() : "",
        "exists", snapshot.isExists());
    sendResponse(session, req.getId(), true, payload, null);
  }

  @SuppressWarnings("unchecked")
  private void handleConfigApply(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String raw = requireNonEmptyString(params, "raw");
    if (raw == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "config.apply: raw (string) required"));
      return;
    }

    Map<String, Object> parsed;
    try {
      parsed = parseJsonObject(raw);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "config.apply: invalid raw json: " + e.getMessage()));
      return;
    }

    configWriter.write(parsed);
    Long delayMs = optionalNonNegativeLong(params, "restartDelayMs");
    Map<String, Object> restart = new LinkedHashMap<>();
    restart.put("reason", "config.apply");
    restart.put("delayMs", delayMs);
    Map<String, Object> sentinel = new LinkedHashMap<>();
    sentinel.put("path", null);
    sentinel.put("payload", null);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("path", configWriter.getConfigPath());
    payload.put("config", parsed);
    payload.put("restart", restart);
    payload.put("sentinel", sentinel);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleConfigPatch(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String raw = requireNonEmptyString(params, "raw");
    if (raw == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "config.patch: raw (string) required"));
      return;
    }

    ConfigSnapshot resolvedSnapshot = configLoader.load();
    if (!resolvedSnapshot.isExists()) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "invalid config; fix before patching"));
      return;
    }

    ConfigSnapshot rawSnapshot = configLoader.loadRaw();
    Map<String, String> envForRestore = ConfigLoader.buildEnvMap(rawSnapshot.getConfig());

    Map<String, Object> patch;
    try {
      patch = parseJsonObject(raw);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "config.patch: invalid raw json: " + e.getMessage()));
      return;
    }

    Map<String, Object> mergedResolved = ConfigMergePatch.merge(resolvedSnapshot.getConfig(), patch);
    Object mergedRestored =
        ConfigEnvRestorer.restoreEnvVarRefs(mergedResolved, rawSnapshot.getConfig(), envForRestore);

    @SuppressWarnings("unchecked")
    Map<String, Object> mergedToWrite = (Map<String, Object>) mergedRestored;
    configWriter.write(mergedToWrite);
    Long delayMs = optionalNonNegativeLong(params, "restartDelayMs");
    Map<String, Object> restart = new LinkedHashMap<>();
    restart.put("reason", "config.patch");
    restart.put("delayMs", delayMs);
    Map<String, Object> sentinel = new LinkedHashMap<>();
    sentinel.put("path", null);
    sentinel.put("payload", null);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("path", configWriter.getConfigPath());
    payload.put("config", merged);
    payload.put("restart", restart);
    payload.put("sentinel", sentinel);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleSessionsCreate(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String agentId = optionalNonEmptyString(params, "agentId");
    if (agentId == null) agentId = "default";
    String parentSessionKey = optionalNonEmptyString(params, "parentSessionKey");
    String label = optionalNonEmptyString(params, "label");
    String model = optionalNonEmptyString(params, "model");

    String key = optionalNonEmptyString(params, "key");
    if (key == null) {
      key = "agent:" + agentId + ":dashboard:" + UUID.randomUUID().toString();
    }

    InMemorySessionStore.SessionEntry entry =
        sessionStore.create(key, agentId, parentSessionKey, label, model);

    String message = optionalNonEmptyString(params, "message");
    int messageSeq = 0;
    int beforeCount = entry.messages.size();
    if (message != null) {
      sessionStore.addMessage(key, message);
      int afterCount = entry.messages.size();
      if (afterCount > beforeCount) {
        messageSeq = beforeCount + 1;
      } else {
        messageSeq = afterCount;
      }
    }

    Map<String, Object> entryPayload = new LinkedHashMap<>();
    entryPayload.put("key", entry.key);
    entryPayload.put("sessionId", entry.sessionId);
    entryPayload.put("agentId", entry.agentId);
    entryPayload.put("parentSessionKey", entry.parentSessionKey);
    entryPayload.put("label", entry.label);
    entryPayload.put("model", entry.model);
    entryPayload.put("createdAt", entry.createdAt);
    entryPayload.put("updatedAt", entry.updatedAt);
    entryPayload.put("messagesCount", entry.messages.size());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("key", key);
    payload.put("sessionId", entry.sessionId);
    payload.put("entry", entryPayload);
    payload.put("runStarted", false);
    sendResponse(session, req.getId(), true, payload, null);

    // Broadcast session change to all sessions subscribed via sessions.subscribe.
    emitSessionsChanged(key, "create");

    // If the create request also provided an initial message, push it as well.
    if (message != null && messageSeq > 0) {
      emitSessionsMessage(key, messageSeq, message);
    }
  }

  private void handleSessionsList(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    int limit = optionalPositiveInt(params, "limit", 50);
    String label = optionalNonEmptyString(params, "label");
    String search = optionalNonEmptyString(params, "search");

    List<Map<String, Object>> rows = sessionStore.listSessions(limit, label, search);
    long now = System.currentTimeMillis();
    Map<String, Object> payload =
        Map.of(
            "ts", now,
            "path", "in-memory",
            "count", rows.size(),
            "defaults", Map.of(),
            "sessions", rows);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleSessionsGet(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String key = optionalNonEmptyString(params, "key");
    if (key == null) {
      key = optionalNonEmptyString(params, "sessionKey");
    }
    if (key == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "sessions.get: key required"));
      return;
    }
    int limit = optionalPositiveInt(params, "limit", 200);
    List<String> messages = sessionStore.listMessages(key, limit);
    Map<String, Object> payload = Map.of("messages", messages);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleSessionsDelete(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String key = optionalNonEmptyString(params, "key");
    if (key == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "sessions.delete: key required"));
      return;
    }
    boolean deleted = sessionStore.delete(key);
    Map<String, Object> payload =
        Map.of(
            "ok", true,
            "key", key,
            "deleted", deleted,
            "archived", List.of());
    sendResponse(session, req.getId(), true, payload, null);

    // Broadcast session change.
    if (deleted) {
      emitSessionsChanged(key, "delete");
    }
  }

  private void handleChatSend(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String sessionKey = optionalNonEmptyString(params, "sessionKey");
    String message = optionalNonEmptyString(params, "message");
    if (sessionKey == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "chat.send: sessionKey required"));
      return;
    }
    if (message == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "chat.send: message required"));
      return;
    }

    InMemorySessionStore.SessionEntry entry = sessionStore.get(sessionKey);
    if (entry == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "chat.send: session not found"));
      return;
    }

    int before = entry.messages.size();
    sessionStore.addMessage(sessionKey, message);
    int after = entry.messages.size();
    int messageSeq = after > before ? before + 1 : after;

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("aborted", false);
    payload.put("runIds", List.of());
    payload.put("sessionKey", sessionKey);
    payload.put("messageSeq", messageSeq);
    sendResponse(session, req.getId(), true, payload, null);

    // Push events to subscribers.
    emitSessionsChanged(sessionKey, "send");
    emitSessionsMessage(sessionKey, messageSeq, message);
  }

  private void handlePoll(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    // poll: minimal compatibility with Node `gateway/server-methods/send.ts` shape.
    String to = optionalNonEmptyString(params, "to");
    String question = optionalNonEmptyString(params, "question");
    String idempotencyKey = optionalNonEmptyString(params, "idempotencyKey");
    String channel = optionalNonEmptyString(params, "channel");

    List<String> options = new ArrayList<>();
    Object optionsObj = params != null ? params.get("options") : null;
    if (optionsObj instanceof List) {
      for (Object v : (List<?>) optionsObj) {
        if (v instanceof String s) {
          String t = s.trim();
          if (!t.isEmpty()) options.add(t);
        }
      }
    }

    if (to == null || question == null || idempotencyKey == null || options.isEmpty()) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(
              ErrorCodes.INVALID_REQUEST,
              "poll: to/question/options/idempotencyKey required"));
      return;
    }

    // Node restrictions: durationSeconds and isAnonymous are only supported for Telegram polls.
    Long durationSeconds = optionalNonNegativeLong(params, "durationSeconds");
    Long durationHours = optionalNonNegativeLong(params, "durationHours");
    Object silentObj = params != null ? params.get("silent") : null;
    Object isAnonymousObj = params != null ? params.get("isAnonymous") : null;
    Boolean silent = silentObj instanceof Boolean b ? b : null;
    Boolean isAnonymous = isAnonymousObj instanceof Boolean b ? b : null;

    String resolvedChannel = channel != null ? channel : "unknown";
    boolean telegram = "telegram".equalsIgnoreCase(resolvedChannel);
    if (durationSeconds != null && !telegram) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "durationSeconds is only supported for Telegram polls"));
      return;
    }
    if (isAnonymous != null && !telegram) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "isAnonymous is only supported for Telegram polls"));
      return;
    }

    // Lightweight idempotency: repeat requests get the same messageId.
    Map<String, Object> cached = POLL_DEDUPE_BY_ID.get(idempotencyKey);
    if (cached != null) {
      sendResponse(session, req.getId(), true, cached, null);
      return;
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("runId", idempotencyKey);
    payload.put("messageId", UUID.randomUUID().toString());
    payload.put("channel", resolvedChannel);
    if (durationSeconds != null) payload.put("durationSeconds", durationSeconds);
    if (durationHours != null) payload.put("durationHours", durationHours);
    if (silent != null) payload.put("silent", silent);
    if (isAnonymous != null) payload.put("isAnonymous", isAnonymous);
    payload.put("question", question);
    payload.put("options", options);

    POLL_DEDUPE_BY_ID.put(idempotencyKey, payload);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleNodeInvoke(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String nodeId = optionalNonEmptyString(params, "nodeId");
    String command = optionalNonEmptyString(params, "command");
    String id = optionalNonEmptyString(params, "idempotencyKey");
    Long timeoutMs = optionalNonNegativeLong(params, "timeoutMs");

    if (nodeId == null || command == null || id == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "node.invoke: nodeId/command/idempotencyKey required"));
      return;
    }

    long waitTimeoutMs = timeoutMs != null && timeoutMs > 0 ? timeoutMs : 10_000;

    CompletableFuture<NodeInvokeResolution> waiter = new CompletableFuture<>();
    NODE_INVOKE_WAITERS_BY_ID.put(id, waiter);
    NODE_INVOKE_META_BY_ID.put(id, new PendingNodeInvokeMeta(nodeId, command));

    Object rawParams = params.get("params");
    String paramsJSON = null;
    if (rawParams != null) {
      try {
        paramsJSON = MAPPER.writeValueAsString(rawParams);
      } catch (Exception ignored) {
        paramsJSON = null;
      }
    }
    enqueueNodeAction(nodeId, id, command, paramsJSON);

    final String responseId = req.getId();
    NODE_INVOKE_EXECUTOR.submit(
        () -> {
          try {
            NodeInvokeResolution resolution = waiter.get(waitTimeoutMs, TimeUnit.MILLISECONDS);
            if (resolution.ok) {
              Map<String, Object> payload = new LinkedHashMap<>();
              payload.put("ok", true);
              payload.put("nodeId", nodeId);
              payload.put("command", command);
              payload.put("payload", resolution.payload);
              payload.put("payloadJSON", resolution.payloadJSON);
              sendResponse(session, responseId, true, payload, null);
            } else {
              sendResponse(session, responseId, false, null, resolution.error);
            }
          } catch (TimeoutException e) {
            NODE_INVOKE_WAITERS_BY_ID.remove(id);
            NODE_INVOKE_META_BY_ID.remove(id);
            ErrorShape err =
                ErrorShape.of(
                    ErrorCodes.AGENT_TIMEOUT,
                    "node.invoke timeout waiting for node.invoke.result");
            sendResponse(session, responseId, false, null, err);
          } catch (Exception e) {
            NODE_INVOKE_WAITERS_BY_ID.remove(id);
            NODE_INVOKE_META_BY_ID.remove(id);
            ErrorShape err =
                ErrorShape.of(
                    ErrorCodes.UNAVAILABLE, "node.invoke failed: " + e.getMessage());
            sendResponse(session, responseId, false, null, err);
          }
        });
  }

  private void handleNodeInvokeResult(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String id = optionalNonEmptyString(params, "id");
    String nodeId = optionalNonEmptyString(params, "nodeId");
    Object okObj = params.get("ok");
    Boolean ok = okObj instanceof Boolean b ? b : null;

    if (id == null || nodeId == null || ok == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "node.invoke.result: id/nodeId/ok required"));
      return;
    }

    // Remove from pending list regardless of ok; ack will be later slice.
    ConcurrentLinkedQueue<PendingNodeAction> q = NODE_PENDING_ACTIONS_BY_NODE_ID.get(nodeId);
    if (q != null) {
      q.removeIf((a) -> id.equals(a.id));
    }

    CompletableFuture<NodeInvokeResolution> waiter = NODE_INVOKE_WAITERS_BY_ID.remove(id);
    NODE_INVOKE_META_BY_ID.remove(id);
    if (waiter == null) {
      // Late-arriving result expected: return success and mark ignored.
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("ok", true);
      payload.put("ignored", true);
      sendResponse(session, req.getId(), true, payload, null);
      return;
    }

    Object payloadObj = params.get("payload");
    String payloadJSON = optionalNonEmptyString(params, "payloadJSON");
    ErrorShape err = null;
    if (!ok) {
      err = buildErrorShapeFromNodeError(params.get("error"));
    }
    waiter.complete(new NodeInvokeResolution(ok, payloadObj, payloadJSON, err));

    sendResponse(session, req.getId(), true, Map.of("ok", true), null);
  }

  private void handleNodeEvent(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    // Minimal no-op: just acknowledge.
    sendResponse(session, req.getId(), true, Map.of("ok", true), null);
  }

  private Map<String, Object> drainWorkItemPayload(PendingNodeDrainWork item) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", item.id);
    out.put("type", item.type);
    out.put("priority", item.priority);
    out.put("createdAtMs", item.createdAtMs);
    out.put("expiresAtMs", item.expiresAtMs);
    return out;
  }

  private PendingNodeDrainWorkState getOrCreateNodeDrainState(String nodeId) {
    return NODE_DRAIN_STATE_BY_NODE_ID.computeIfAbsent(
        nodeId, (k) -> new PendingNodeDrainWorkState());
  }

  private boolean isNodeConnected(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) return false;
    for (WsContext ctx : CONTEXTS_BY_CONN_ID.values()) {
      if (ctx == null) continue;
      if (!ctx.connected) continue;
      if (ctx.nodeId == null) continue;
      if (!nodeId.equals(ctx.nodeId)) continue;
      if (ctx.role != null && "node".equalsIgnoreCase(ctx.role)) return true;
    }
    return false;
  }

  private boolean pruneExpiredDrainItems(PendingNodeDrainWorkState state, long nowMs) {
    if (state == null || state.itemsById == null || state.itemsById.isEmpty()) return false;
    boolean changed = false;
    List<String> toRemove = new ArrayList<>();
    for (Map.Entry<String, PendingNodeDrainWork> e : state.itemsById.entrySet()) {
      PendingNodeDrainWork item = e.getValue();
      if (item == null) continue;
      if (item.expiresAtMs != null && item.expiresAtMs <= nowMs) {
        toRemove.add(e.getKey());
      }
    }
    if (!toRemove.isEmpty()) {
      for (String id : toRemove) {
        state.itemsById.remove(id);
      }
      changed = true;
    }
    if (changed) state.revision += 1;
    return changed;
  }

  private List<PendingNodeDrainWork> sortedExplicitDrainItems(PendingNodeDrainWorkState state) {
    List<PendingNodeDrainWork> items = new ArrayList<>();
    if (state != null && state.itemsById != null && !state.itemsById.isEmpty()) {
      items.addAll(state.itemsById.values());
    }
    items.sort(
        (a, b) -> {
          int ra = PRIORITY_RANK.getOrDefault(a.priority, 1);
          int rb = PRIORITY_RANK.getOrDefault(b.priority, 1);
          int pr = rb - ra; // higher first
          if (pr != 0) return pr;
          if (a.createdAtMs != b.createdAtMs) return Long.compare(a.createdAtMs, b.createdAtMs);
          return a.id.compareTo(b.id);
        });
    return items;
  }

  private PendingNodeDrainWork makeBaselineStatusItem(long nowMs) {
    return new PendingNodeDrainWork(DEFAULT_STATUS_ITEM_ID, "status.request", DEFAULT_STATUS_PRIORITY, nowMs, null);
  }

  private void handleNodePendingDrain(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    WsContext ctx = (WsContext) session.getAttributes().get(WsContext.KEY);
    String nodeId = ctx != null ? ctx.nodeId : null;
    if (nodeId == null || nodeId.isBlank()) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "node.pending.drain: nodeId required in connect"));
      return;
    }

    int maxItems = optionalPositiveInt(params, "maxItems", DEFAULT_NODE_PENDING_MAX_ITEMS);
    if (maxItems > MAX_NODE_PENDING_MAX_ITEMS) maxItems = MAX_NODE_PENDING_MAX_ITEMS;
    if (maxItems < 1) maxItems = 1;

    long now = System.currentTimeMillis();
    PendingNodeDrainWorkState state = NODE_DRAIN_STATE_BY_NODE_ID.get(nodeId);
    if (state != null) {
      pruneExpiredDrainItems(state, now);
    }

    List<PendingNodeDrainWork> explicitItems = sortedExplicitDrainItems(state);
    long revision = state != null ? state.revision : 0;

    boolean hasExplicitStatus =
        explicitItems.stream().anyMatch((item) -> "status.request".equals(item.type));
    boolean includeBaseline = !hasExplicitStatus; // includeDefaultStatus=true always (this method)

    // First slice: explicit items only.
    List<PendingNodeDrainWork> items = new ArrayList<>();
    if (explicitItems.size() <= maxItems) {
      items.addAll(explicitItems);
    } else {
      items.addAll(explicitItems.subList(0, maxItems));
    }

    // Then conditionally inject baseline-status if missing and there's room.
    boolean baselineIncluded = false;
    if (includeBaseline && items.size() < maxItems) {
      items.add(makeBaselineStatusItem(now));
      baselineIncluded = true;
    }

    long explicitReturnedCount =
        items.stream().filter((item) -> !DEFAULT_STATUS_ITEM_ID.equals(item.id)).count();

    if (includeBaseline && !baselineIncluded) {
      baselineIncluded =
          items.stream().anyMatch((item) -> DEFAULT_STATUS_ITEM_ID.equals(item.id));
    }

    boolean hasMore =
        explicitItems.size() > explicitReturnedCount || (includeBaseline && !baselineIncluded);

    List<Map<String, Object>> payloadItems = new ArrayList<>();
    for (PendingNodeDrainWork item : items) {
      payloadItems.add(drainWorkItemPayload(item));
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("nodeId", nodeId);
    payload.put("revision", revision);
    payload.put("items", payloadItems);
    payload.put("hasMore", hasMore);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleNodePendingEnqueue(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String nodeId = optionalNonEmptyString(params, "nodeId");
    String type = optionalNonEmptyString(params, "type");

    if (nodeId == null || type == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "node.pending.enqueue: nodeId/type required"));
      return;
    }

    boolean supportedType = "status.request".equals(type) || "location.request".equals(type);
    if (!supportedType) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "node.pending.enqueue: unsupported type"));
      return;
    }

    String priorityRaw = optionalNonEmptyString(params, "priority");
    String priority = priorityRaw != null ? priorityRaw : DEFAULT_WORK_PRIORITY;
    if (!"normal".equals(priority) && !"high".equals(priority)) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "node.pending.enqueue: invalid priority"));
      return;
    }

    Long expiresInMs = optionalNonNegativeLong(params, "expiresInMs");
    Boolean wakeObj = params != null && params.get("wake") instanceof Boolean b ? b : null;
    boolean wake = wakeObj == null || wakeObj;

    long now = System.currentTimeMillis();
    if (expiresInMs != null && (expiresInMs < 1_000 || expiresInMs > 86_400_000)) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(
              ErrorCodes.INVALID_REQUEST, "node.pending.enqueue: expiresInMs out of range"));
      return;
    }

    Long expiresAtMs = null;
    if (expiresInMs != null) {
      expiresAtMs = now + Math.max(1_000, Math.trunc(expiresInMs));
    }

    PendingNodeDrainWorkState state = getOrCreateNodeDrainState(nodeId);
    pruneExpiredDrainItems(state, now);

    PendingNodeDrainWork existing =
        state.itemsById.values().stream()
            .filter((item) -> item != null && type.equals(item.type))
            .findFirst()
            .orElse(null);

    boolean deduped = existing != null;
    PendingNodeDrainWork queuedItem;
    long revision;
    if (deduped) {
      queuedItem = existing;
      revision = state.revision;
    } else {
      queuedItem =
          new PendingNodeDrainWork(
              UUID.randomUUID().toString(), type, priority, now, expiresAtMs);
      state.itemsById.put(queuedItem.id, queuedItem);
      state.revision += 1;
      revision = state.revision;
    }

    boolean wakeTriggered = wake && !deduped && !isNodeConnected(nodeId);

    Map<String, Object> queued = drainWorkItemPayload(queuedItem);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("nodeId", nodeId);
    payload.put("revision", revision);
    payload.put("queued", queued);
    payload.put("wakeTriggered", wakeTriggered);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleNodePendingPull(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    WsContext ctx = (WsContext) session.getAttributes().get(WsContext.KEY);
    String nodeId = ctx != null ? ctx.nodeId : null;
    if (nodeId == null || nodeId.isBlank()) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "node.pending.pull: nodeId required in connect"));
      return;
    }
    prunePendingNodeActions(nodeId, System.currentTimeMillis());
    ConcurrentLinkedQueue<PendingNodeAction> q = NODE_PENDING_ACTIONS_BY_NODE_ID.get(nodeId);
    List<Map<String, Object>> actions = new ArrayList<>();
    if (q != null) {
      for (PendingNodeAction a : q) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("id", a.id);
        action.put("command", a.command);
        action.put("paramsJSON", a.paramsJSON);
        action.put("enqueuedAtMs", a.enqueuedAtMs);
        actions.add(action);
      }
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("nodeId", nodeId);
    payload.put("actions", actions);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleNodePendingAck(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    WsContext ctx = (WsContext) session.getAttributes().get(WsContext.KEY);
    String nodeId = ctx != null ? ctx.nodeId : null;
    if (nodeId == null || nodeId.isBlank()) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "node.pending.ack: nodeId required in connect"));
      return;
    }

    prunePendingNodeActions(nodeId, System.currentTimeMillis());
    Object idsObj = params != null ? params.get("ids") : null;
    List<String> ids = new ArrayList<>();
    if (idsObj instanceof List) {
      for (Object v : (List<?>) idsObj) {
        if (v instanceof String s) {
          String t = s.trim();
          if (!t.isEmpty()) ids.add(t);
        }
      }
    }
    if (ids.isEmpty()) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "node.pending.ack: ids (non-empty) required"));
      return;
    }

    ConcurrentLinkedQueue<PendingNodeAction> q = NODE_PENDING_ACTIONS_BY_NODE_ID.get(nodeId);
    if (q != null) {
      HashSet<String> toAck = new HashSet<>(ids);
      q.removeIf((a) -> toAck.contains(a.id));
    }
    if (q != null && q.isEmpty()) {
      NODE_PENDING_ACTIONS_BY_NODE_ID.remove(nodeId);
    }
    int remaining = q != null ? q.size() : 0;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("nodeId", nodeId);
    payload.put("ackedIds", ids);
    payload.put("remainingCount", remaining);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void prunePendingNodeActions(String nodeId, long nowMs) {
    if (nodeId == null || nodeId.isBlank()) return;
    ConcurrentLinkedQueue<PendingNodeAction> q = NODE_PENDING_ACTIONS_BY_NODE_ID.get(nodeId);
    if (q == null || q.isEmpty()) return;

    long minTimestampMs = nowMs - NODE_PENDING_ACTION_TTL_MS;
    q.removeIf((a) -> a != null && a.enqueuedAtMs < minTimestampMs);

    while (q.size() > NODE_PENDING_ACTION_MAX_PER_NODE) {
      // ConcurrentLinkedQueue iterator preserves insertion order, so removing from the front
      // approximates Node's splice(0, ... ) behavior.
      PendingNodeAction toRemove = q.peek();
      if (toRemove == null) break;
      q.remove(toRemove);
    }

    if (q.isEmpty()) {
      NODE_PENDING_ACTIONS_BY_NODE_ID.remove(nodeId);
    }
  }

  private void enqueueNodeAction(String nodeId, String id, String command, String paramsJSON) {
    PendingNodeAction action =
        new PendingNodeAction(id, command, paramsJSON, System.currentTimeMillis());
    ConcurrentLinkedQueue<PendingNodeAction> q =
        NODE_PENDING_ACTIONS_BY_NODE_ID.computeIfAbsent(nodeId, k -> new ConcurrentLinkedQueue<>());
    // Ensure idempotency in first slice: remove any existing action with same id.
    q.removeIf((a) -> id.equals(a.id));
    q.add(action);

    prunePendingNodeActions(nodeId, System.currentTimeMillis());
  }

  private ErrorShape buildErrorShapeFromNodeError(Object errorObj) {
    if (errorObj instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> err = (Map<String, Object>) errorObj;
      String code = err.get("code") instanceof String s ? s : null;
      String message = err.get("message") instanceof String s ? s : null;
      String normalizedCode =
          code != null
              ? switch (code) {
                case ErrorCodes.NOT_LINKED,
                    ErrorCodes.NOT_PAIRED,
                    ErrorCodes.AGENT_TIMEOUT,
                    ErrorCodes.INVALID_REQUEST,
                    ErrorCodes.UNAVAILABLE -> code;
                default -> ErrorCodes.UNAVAILABLE;
              }
              : ErrorCodes.UNAVAILABLE;
      String normalizedMessage = message != null ? message : "node error";
      return ErrorShape.of(normalizedCode, normalizedMessage);
    }
    return ErrorShape.of(ErrorCodes.UNAVAILABLE, "node error");
  }

  private void handleStatus(WebSocketSession session, RequestFrame req) {
    // Minimal shape compatible with Node `StatusSummary` (src/commands/status.types.ts).
    Map<String, Object> sessions = new LinkedHashMap<>();
    sessions.put("paths", List.of());
    sessions.put("count", 0);
    Map<String, Object> defaults = new LinkedHashMap<>();
    defaults.put("model", null);
    defaults.put("contextTokens", null);
    sessions.put("defaults", defaults);
    sessions.put("recent", List.of());
    sessions.put("byAgent", List.of());

    Map<String, Object> heartbeat = new LinkedHashMap<>();
    heartbeat.put("defaultAgentId", "default");
    heartbeat.put("agents", List.of());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("runtimeVersion", version);
    payload.put("linkChannel", null);
    payload.put("heartbeat", heartbeat);
    payload.put("channelSummary", List.of());
    payload.put("queuedSystemEvents", List.of());
    payload.put("sessions", sessions);

    sendResponse(session, req.getId(), true, payload, null);
  }

  private void sendResponse(WebSocketSession session, String id, boolean ok, Object payload, ErrorShape error) {
    if (id == null) return;
    ResponseFrame res = new ResponseFrame(id, ok, payload, error);
    send(session, res);
  }

  private void send(WebSocketSession session, Object obj) {
    try {
      if (session.isOpen()) {
        session.sendMessage(new TextMessage(MAPPER.writeValueAsString(obj)));
      }
    } catch (Exception e) {
      // log
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    WsContext ctx = (WsContext) session.getAttributes().get(WsContext.KEY);
    session.getAttributes().remove(WsContext.KEY);
    if (ctx != null && ctx.connId != null) {
      ACTIVE_SESSIONS.remove(ctx.connId);
      CONTEXTS_BY_CONN_ID.remove(ctx.connId);
    }
  }

  public static final class WsContext {
    static final String KEY = "ws.ctx";
    boolean connected;
    String role;
    List<String> scopes;
    String connId;
    String nodeId;

    // subscriptions (first slice)
    volatile boolean sessionsSubscribed;
    Set<String> subscribedMessageKeys = ConcurrentHashMap.newKeySet();
    AtomicLong nextEventSeq = new AtomicLong(1);
  }

  private String requireNonEmptyString(Map<String, Object> params, String key) {
    if (params == null) return null;
    Object v = params.get(key);
    if (!(v instanceof String s)) return null;
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private Long optionalNonNegativeLong(Map<String, Object> params, String key) {
    if (params == null) return null;
    Object v = params.get(key);
    if (v instanceof Integer i) {
      return i >= 0 ? (long) i : null;
    }
    if (v instanceof Long l) {
      return l >= 0 ? l : null;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseJsonObject(String raw) throws Exception {
    return ConfigParsers.parseJson5Object(raw);
  }

  private String optionalNonEmptyString(Map<String, Object> params, String key) {
    if (params == null) return null;
    Object v = params.get(key);
    if (!(v instanceof String s)) return null;
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private int optionalPositiveInt(Map<String, Object> params, String key, int defaultValue) {
    if (params == null) return defaultValue;
    Object v = params.get(key);
    if (v instanceof Integer i) {
      return i > 0 ? i : defaultValue;
    }
    if (v instanceof Long l) {
      return l > 0 && l <= Integer.MAX_VALUE ? (int) l : defaultValue;
    }
    return defaultValue;
  }

  private void handleSessionsSubscribe(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    WsContext ctx = (WsContext) session.getAttributes().get(WsContext.KEY);
    if (ctx != null) {
      ctx.sessionsSubscribed = true;
    }
    sendResponse(session, req.getId(), true, Map.of("subscribed", true), null);
  }

  private void handleSessionsUnsubscribe(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    WsContext ctx = (WsContext) session.getAttributes().get(WsContext.KEY);
    if (ctx != null) {
      ctx.sessionsSubscribed = false;
    }
    sendResponse(session, req.getId(), true, Map.of("subscribed", false), null);
  }

  private void handleSessionsMessagesSubscribe(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String key = optionalNonEmptyString(params, "key");
    WsContext ctx = (WsContext) session.getAttributes().get(WsContext.KEY);
    if (key == null) {
      sendResponse(session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, "sessions.messages.subscribe: key required"));
      return;
    }
    if (ctx != null) {
      ctx.subscribedMessageKeys.add(key);
    }
    sendResponse(session, req.getId(), true, Map.of("subscribed", true, "key", key), null);
  }

  private void handleSessionsMessagesUnsubscribe(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String key = optionalNonEmptyString(params, "key");
    WsContext ctx = (WsContext) session.getAttributes().get(WsContext.KEY);
    if (key == null) {
      sendResponse(session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, "sessions.messages.unsubscribe: key required"));
      return;
    }
    if (ctx != null) {
      ctx.subscribedMessageKeys.remove(key);
    }
    sendResponse(session, req.getId(), true, Map.of("subscribed", false, "key", key), null);
  }

  private void emitSessionsChanged(String sessionKey, String reason) {
    for (Map.Entry<String, WsContext> e : CONTEXTS_BY_CONN_ID.entrySet()) {
      WsContext ctx = e.getValue();
      if (ctx == null || !ctx.sessionsSubscribed) continue;
      WebSocketSession ws = ACTIVE_SESSIONS.get(e.getKey());
      if (ws == null || !ws.isOpen()) continue;
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("sessionKey", sessionKey);
      payload.put("reason", reason);
      payload.put("ts", System.currentTimeMillis());
      emitEvent(ws, ctx, "sessions.changed", payload);
    }
  }

  private void emitSessionsMessage(String sessionKey, int seq, String message) {
    for (Map.Entry<String, WsContext> e : CONTEXTS_BY_CONN_ID.entrySet()) {
      WsContext ctx = e.getValue();
      if (ctx == null || ctx.subscribedMessageKeys == null || !ctx.subscribedMessageKeys.contains(sessionKey)) continue;
      WebSocketSession ws = ACTIVE_SESSIONS.get(e.getKey());
      if (ws == null || !ws.isOpen()) continue;

      Map<String, Object> messagePayload = new LinkedHashMap<>();
      messagePayload.put("text", message);
      messagePayload.put("seq", seq);

      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("key", sessionKey);
      payload.put("message", messagePayload);
      payload.put("messageSeq", seq);
      payload.put("ts", System.currentTimeMillis());
      emitEvent(ws, ctx, "sessions.messages", payload);
    }
  }

  private void emitEvent(WebSocketSession session, WsContext ctx, String eventName, Map<String, Object> payload) {
    if (ctx == null) return;
    if (!EVENT_SLOTS.contains(eventName)) return;
    EventFrame frame = new EventFrame();
    frame.setEvent(eventName);
    frame.setPayload(payload);
    frame.setSeq(ctx.nextEventSeq.getAndIncrement());
    send(session, frame);
  }
}
