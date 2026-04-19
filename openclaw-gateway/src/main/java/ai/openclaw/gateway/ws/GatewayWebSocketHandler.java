package ai.openclaw.gateway.ws;

import ai.openclaw.config.ConfigLoader;
import ai.openclaw.config.ConfigSnapshot;
import ai.openclaw.config.ConfigWriter;
import ai.openclaw.config.ConfigParsers;
import ai.openclaw.gateway.auth.MethodScopes;
import ai.openclaw.config.ConfigMergePatch;
import ai.openclaw.config.ConfigEnvRestorer;
import ai.openclaw.gateway.config.ConfigRpcSupport;
import ai.openclaw.gateway.autonomous.AutonomousGoalService;
import ai.openclaw.gateway.chat.ChatRunRegistry;
import ai.openclaw.gateway.cron.GatewayCronService;
import ai.openclaw.gateway.node.NodeInvokeService;
import ai.openclaw.gateway.node.NodeInvokeService.NodeInvokeResolution;
import ai.openclaw.gateway.nodebridge.BrowserProxyNodeBridge;
import ai.openclaw.gateway.nodebridge.BrowserProxyNodeBridge.BrowserRequestOutcome;
import ai.openclaw.gateway.skills.GatewaySkillsService;
import ai.openclaw.gateway.skills.SkillsWsParams;
import ai.openclaw.gateway.business.ProjectService;
import ai.openclaw.gateway.business.StaffService;
import ai.openclaw.gateway.ws.ChatLlmExecutor;
import ai.openclaw.gateway.ws.MentionDispatcher;
import ai.openclaw.agent.runtime.AgentTraceSink;
import ai.openclaw.agent.runtime.AgentTurnRunner;
import ai.openclaw.agent.runtime.LlmInvocationParams;
import ai.openclaw.agent.runtime.OpenAiToolsMerge;
import ai.openclaw.agent.runtime.ToolExecutionContext;
import ai.openclaw.agent.tools.OpenClawToolRegistry;
import ai.openclaw.gateway.plugins.OpenClawPluginLoader;
import ai.openclaw.llm.OpenAiCompatibleChatClient;
import ai.openclaw.memory.MemoryHit;
import ai.openclaw.memory.SqliteMemoryStore;
import com.fasterxml.jackson.databind.JsonNode;
import ai.openclaw.protocol.EventFrame;
import ai.openclaw.gateway.sessions.InMemorySessionStore;
import ai.openclaw.protocol.ErrorCodes;
import ai.openclaw.protocol.ErrorShape;
import ai.openclaw.protocol.RequestFrame;
import ai.openclaw.protocol.ResponseFrame;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
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
import java.util.function.BiConsumer;
import org.springframework.beans.factory.annotation.Autowired;
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
  /** Subset of events we actually emit today (Web UI + sessions). */
  private static final Set<String> EMITTABLE_EVENTS =
      Set.of("sessions.changed", "sessions.messages", "chat", "autonomous.goal");

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
          "autonomous.goals.create",
          "autonomous.goals.get",
          "autonomous.goals.list",
          "autonomous.goals.patch",
          "chat.send");

  /** Extra gateway events this Java port declares beyond {@link #NODE_GATEWAY_EVENTS}. */
  private static final List<String> EVENT_SLOTS =
      List.of("session.tool", "session.message", "autonomous.goal");

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
    // Dev-only in-memory LLM config for local integration.
    methods.add("llm.config.set");
    methods.add("memory.put");
    methods.add("memory.search");
    methods.add("plugins.list");
    methods.add("agent.tools.list");
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

  private static final ExecutorService LLM_EXECUTOR =
      Executors.newCachedThreadPool(
          new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
              Thread t = new Thread(r);
              t.setDaemon(true);
              t.setName("openclaw-llm-executor");
              return t;
            }
          });

  private static final class LlmConfig {
    final String chatCompletionsUrl;
    final String apiKey;
    final String model;
    final Double temperature;
    final Integer maxTokens;
    final String systemPrompt;
    final Object tools;
    final Object toolChoice;

    LlmConfig(
        String chatCompletionsUrl,
        String apiKey,
        String model,
        Double temperature,
        Integer maxTokens,
        String systemPrompt,
        Object tools,
        Object toolChoice) {
      this.chatCompletionsUrl = chatCompletionsUrl;
      this.apiKey = apiKey;
      this.model = model;
      this.temperature = temperature;
      this.maxTokens = maxTokens;
      this.systemPrompt = systemPrompt;
      this.tools = tools;
      this.toolChoice = toolChoice;
    }
  }

  private static volatile LlmConfig currentLlmConfig;

  private static final ConcurrentHashMap<String, Object> LLM_SESSION_LOCKS =
      new ConcurrentHashMap<>();

  private interface WsMethodHandler {
    void handle(WebSocketSession session, RequestFrame req, Map<String, Object> params);
  }

  // Very small in-memory cache for health responses (first slice).
  // In Node this is in server-maintenance.ts; we mirror behavior for now.
  private volatile Map<String, Object> cachedHealthPayload;
  private volatile long cachedHealthTs;

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

  private static final String DEFAULT_REFLECTION_PROMPT =
      "Review your previous reply in this conversation (including tool calls already reflected above). "
          + "If anything is incomplete, incorrect, or missing relative to the user's goal, produce an improved final answer. "
          + "If the previous answer is fully adequate, you may repeat it unchanged or briefly confirm.";

  private record ChatSendLlmOptions(int reflectionRounds, String reflectionPrompt, String autonomousGoalId) {
    private static final ChatSendLlmOptions DEFAULT = new ChatSendLlmOptions(0, null, null);
  }

  private static final class PendingNodeDrainWorkState {
    long revision;
    final Map<String, PendingNodeDrainWork> itemsById = new HashMap<>();
  }

  private static final ConcurrentHashMap<String, PendingNodeDrainWorkState>
      NODE_DRAIN_STATE_BY_NODE_ID = new ConcurrentHashMap<>();

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
  private final SqliteMemoryStore sqlMemory;
  private final OpenClawToolRegistry toolRegistry;
  private final OpenClawPluginLoader pluginLoader;
  private final GatewaySkillsService gatewaySkillsService;
  private final NodeInvokeService nodeInvoke;
  private final BrowserProxyNodeBridge browserProxyNodeBridge;
  private final ChatRunRegistry chatRunRegistry;
  private final GatewayCronService gatewayCronService;
  private final AutonomousGoalService autonomousGoalService;
  @Autowired private ProjectService projectService;
  @Autowired private StaffService staffService;
  private SessionContextBuilder sessionCtxBuilder;
  private MentionDispatcher mentionDispatcher;
  private SessionContextBuilder getCtx() {
    if (sessionCtxBuilder == null) {
      sessionCtxBuilder = new SessionContextBuilder(projectService, staffService);
    }
    return sessionCtxBuilder;
  }

  private MentionDispatcher getMentionDispatcher() {
    if (mentionDispatcher == null) {
      mentionDispatcher = new MentionDispatcher(
          projectService,
          staffService,
          sessionStore,
          getCtx(),
          (k, r) -> LLM_EXECUTOR.submit(r));
    }
    return mentionDispatcher;
  }

  public GatewayWebSocketHandler(
      ConfigLoader configLoader,
      ConfigWriter configWriter,
      InMemorySessionStore sessionStore,
      SqliteMemoryStore sqlMemory,
      OpenClawToolRegistry toolRegistry,
      OpenClawPluginLoader pluginLoader,
      GatewaySkillsService gatewaySkillsService,
      NodeInvokeService nodeInvoke,
      BrowserProxyNodeBridge browserProxyNodeBridge,
      ChatRunRegistry chatRunRegistry,
      GatewayCronService gatewayCronService,
      AutonomousGoalService autonomousGoalService,
      Environment env) {
    this.configLoader = configLoader;
    this.configWriter = configWriter;
    this.sessionStore = sessionStore;
    this.sqlMemory = sqlMemory;
    this.toolRegistry = toolRegistry;
    this.pluginLoader = pluginLoader;
    this.gatewaySkillsService = gatewaySkillsService;
    this.nodeInvoke = nodeInvoke;
    this.browserProxyNodeBridge = browserProxyNodeBridge;
    this.chatRunRegistry = chatRunRegistry;
    this.gatewayCronService = gatewayCronService;
    this.autonomousGoalService = autonomousGoalService;
    this.gatewayToken = env.getProperty("OPENCLAW_GATEWAY_TOKEN", "");
    Map<String, WsMethodHandler> handlers = new LinkedHashMap<>();
    handlers.put("health", (session, req, params) -> handleHealth(session, req, params));
    handlers.put("models.list", (session, req, params) -> handleModelsList(session, req, params));
    handlers.put("agents.list", (session, req, params) -> handleAgentsList(session, req, params));
    handlers.put("channels.status", (session, req, params) -> handleChannelsStatus(session, req, params));
    handlers.put("poll", (session, req, params) -> handlePoll(session, req, params));
    handlers.put("config.get", (session, req, params) -> handleConfigGet(session, req, params));
    handlers.put("config.apply", (session, req, params) -> handleConfigApply(session, req, params));
    handlers.put("config.patch", (session, req, params) -> handleConfigPatch(session, req, params));
    handlers.put("node.invoke", (session, req, params) -> handleNodeInvoke(session, req, params));
    handlers.put(
        "node.invoke.result",
        (session, req, params) -> handleNodeInvokeResult(session, req, params));
    handlers.put("node.event", (session, req, params) -> handleNodeEvent(session, req, params));
    handlers.put(
        "node.pending.drain",
        (session, req, params) -> handleNodePendingDrain(session, req, params));
    handlers.put(
        "node.pending.pull",
        (session, req, params) -> handleNodePendingPull(session, req, params));
    handlers.put(
        "node.pending.ack",
        (session, req, params) -> handleNodePendingAck(session, req, params));
    handlers.put(
        "node.pending.enqueue",
        (session, req, params) -> handleNodePendingEnqueue(session, req, params));
    handlers.put(
        "sessions.create",
        (session, req, params) -> handleSessionsCreate(session, req, params));
    handlers.put("sessions.list", (session, req, params) -> handleSessionsList(session, req, params));
    handlers.put("sessions.get", (session, req, params) -> handleSessionsGet(session, req, params));
    handlers.put(
        "sessions.delete",
        (session, req, params) -> handleSessionsDelete(session, req, params));
    handlers.put(
        "sessions.subscribe",
        (session, req, params) -> handleSessionsSubscribe(session, req, params));
    handlers.put(
        "sessions.unsubscribe",
        (session, req, params) -> handleSessionsUnsubscribe(session, req, params));
    handlers.put(
        "sessions.messages.subscribe",
        (session, req, params) -> handleSessionsMessagesSubscribe(session, req, params));
    handlers.put(
        "sessions.messages.unsubscribe",
        (session, req, params) -> handleSessionsMessagesUnsubscribe(session, req, params));
    handlers.put("chat.send", (session, req, params) -> handleChatSend(session, req, params));
    handlers.put(
        "autonomous.goals.create",
        (session, req, params) -> handleAutonomousGoalsCreate(session, req, params));
    handlers.put(
        "autonomous.goals.get", (session, req, params) -> handleAutonomousGoalsGet(session, req, params));
    handlers.put(
        "autonomous.goals.list",
        (session, req, params) -> handleAutonomousGoalsList(session, req, params));
    handlers.put(
        "autonomous.goals.patch",
        (session, req, params) -> handleAutonomousGoalsPatch(session, req, params));
    handlers.put("llm.config.set", (session, req, params) -> handleLlmConfigSet(session, req, params));
    handlers.put("memory.put", (session, req, params) -> handleMemoryPut(session, req, params));
    handlers.put("memory.search", (session, req, params) -> handleMemorySearch(session, req, params));
    handlers.put("plugins.list", (session, req, params) -> handlePluginsList(session, req, params));
    handlers.put(
        "agent.tools.list", (session, req, params) -> handleAgentToolsList(session, req, params));
    handlers.put("status", (session, req, params) -> handleStatus(session, req));
    handlers.put("skills.status", (session, req, params) -> handleSkillsStatus(session, req, params));
    handlers.put("skills.bins", (session, req, params) -> handleSkillsBins(session, req, params));
    handlers.put("skills.install", (session, req, params) -> handleSkillsInstall(session, req, params));
    handlers.put("skills.update", (session, req, params) -> handleSkillsUpdate(session, req, params));
    handlers.put("chat.history", (session, req, params) -> handleChatHistory(session, req, params));
    handlers.put("chat.abort", (session, req, params) -> handleChatAbort(session, req, params));
    handlers.put("sessions.patch", (session, req, params) -> handleSessionsPatch(session, req, params));
    handlers.put("sessions.reset", (session, req, params) -> handleSessionsReset(session, req, params));
    handlers.put(
        "sessions.compact", (session, req, params) -> handleSessionsCompact(session, req, params));
    handlers.put("config.set", (session, req, params) -> handleConfigSet(session, req, params));
    handlers.put("logs.tail", (session, req, params) -> handleLogsTail(session, req, params));
    handlers.put("last-heartbeat", (session, req, params) -> handleLastHeartbeat(session, req, params));
    handlers.put(
        "agent.identity.get", (session, req, params) -> handleAgentIdentityGet(session, req, params));
    handlers.put(
        "system-presence", (session, req, params) -> handleSystemPresence(session, req, params));
    handlers.put("usage.cost", (session, req, params) -> handleUsageCost(session, req, params));
    handlers.put(
        "sessions.usage", (session, req, params) -> handleSessionsUsage(session, req, params));
    handlers.put(
        "sessions.usage.timeseries",
        (session, req, params) -> handleSessionsUsageTimeseries(session, req, params));
    handlers.put(
        "sessions.usage.logs", (session, req, params) -> handleSessionsUsageLogs(session, req, params));
    handlers.put(
        "exec.approval.resolve",
        (session, req, params) -> handleExecApprovalResolve(session, req, params));
    handlers.put(
        "config.openFile", (session, req, params) -> handleConfigOpenFile(session, req, params));
    handlers.put("tts.status", (session, req, params) -> handleTtsStatus(session, req, params));
    handlers.put("tts.providers", (session, req, params) -> handleTtsProviders(session, req, params));
    handlers.put(
        "browser.request", (session, req, params) -> handleBrowserRequest(session, req, params));
    handlers.put(
        "device.pair.list", (session, req, params) -> handleDevicePairList(session, req, params));
    handlers.put(
        "device.pair.approve",
        (session, req, params) -> handleDevicePairApprove(session, req, params));
    handlers.put(
        "device.pair.reject", (session, req, params) -> handleDevicePairReject(session, req, params));
    handlers.put(
        "device.token.revoke", (session, req, params) -> handleDeviceTokenRevoke(session, req, params));
    handlers.put("cron.list", (session, req, params) -> handleCronList(session, req, params));
    handlers.put("cron.status", (session, req, params) -> handleCronStatus(session, req, params));
    handlers.put("cron.add", (session, req, params) -> handleCronAdd(session, req, params));
    handlers.put("cron.update", (session, req, params) -> handleCronUpdate(session, req, params));
    handlers.put("cron.remove", (session, req, params) -> handleCronRemove(session, req, params));
    handlers.put("cron.run", (session, req, params) -> handleCronRun(session, req, params));
    handlers.put("cron.runs", (session, req, params) -> handleCronRuns(session, req, params));
    this.methodHandlers = handlers;
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

  private void handleModelsList(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    if (!ConfigRpcSupport.isEmptyParamsOnly(params)) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "models.list: invalid params (expected {})"));
      return;
    }
    try {
      ConfigSnapshot snap = configLoader.load();
      @SuppressWarnings("unchecked")
      Map<String, Object> cfg = (Map<String, Object>) snap.getConfig();
      List<Map<String, Object>> models = ConfigRpcSupport.buildModelsList(cfg);
      sendResponse(session, req.getId(), true, Map.of("models", models), null);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.UNAVAILABLE, "models.list: " + e.getMessage()));
    }
  }

  private void handleAgentsList(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    if (!ConfigRpcSupport.isEmptyParamsOnly(params)) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "agents.list: invalid params (expected {})"));
      return;
    }
    try {
      ConfigSnapshot snap = configLoader.load();
      @SuppressWarnings("unchecked")
      Map<String, Object> cfg = (Map<String, Object>) snap.getConfig();
      Map<String, Object> payload = ConfigRpcSupport.buildAgentsListPayload(cfg);
      sendResponse(session, req.getId(), true, payload, null);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.UNAVAILABLE, "agents.list: " + e.getMessage()));
    }
  }

  @SuppressWarnings("unused")
  private void handleChannelsStatus(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    List<Map<String, Object>> channels = new ArrayList<>();
    Map<String, Object> http = new LinkedHashMap<>();
    http.put("id", "http");
    http.put("kind", "http");
    http.put("enabled", true);
    http.put(
        "inbound",
        Map.of(
            "method", "POST",
            "path", "/api/channel/http/message",
            "auth", "Authorization: Bearer <OPENCLAW_GATEWAY_TOKEN>"));
    channels.add(http);
    sendResponse(session, req.getId(), true, Map.of("channels", channels), null);
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
    payload.put("config", mergedResolved);
    payload.put("restart", restart);
    payload.put("sentinel", sentinel);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleLlmConfigSet(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    Object baseUrlObj = params != null ? params.get("baseUrl") : null;
    Object chatCompletionsUrlObj = params != null ? params.get("chatCompletionsUrl") : null;
    Object apiKeyObj = params != null ? params.get("apiKey") : null;
    Object modelObj = params != null ? params.get("model") : null;
    Object temperatureObj = params != null ? params.get("temperature") : null;
    Object maxTokensObj = params != null ? params.get("maxTokens") : null;
    Object systemPromptObj = params != null ? params.get("systemPrompt") : null;
    Object toolsObj = params != null ? params.get("tools") : null;
    Object toolChoiceObj = params != null ? params.get("toolChoice") : null;

    String baseUrl = baseUrlObj instanceof String s ? s.trim() : null;
    String chatCompletionsUrl =
        chatCompletionsUrlObj instanceof String s ? s.trim() : null;
    String apiKey = apiKeyObj instanceof String s ? s.trim() : null;
    String model = modelObj instanceof String s ? s.trim() : null;
    String systemPrompt =
        systemPromptObj instanceof String s ? s.trim() : null;

    if ((chatCompletionsUrl == null || chatCompletionsUrl.isBlank()) && (baseUrl == null || baseUrl.isBlank())) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "llm.config.set: baseUrl or chatCompletionsUrl required"));
      return;
    }
    if (apiKey == null || apiKey.isBlank()) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "llm.config.set: apiKey required"));
      return;
    }
    if (model == null || model.isBlank()) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "llm.config.set: model required"));
      return;
    }

    if (chatCompletionsUrl == null || chatCompletionsUrl.isBlank()) {
      chatCompletionsUrl = buildChatCompletionsUrlFromBaseUrl(baseUrl);
    }

    Double temperature = null;
    if (temperatureObj instanceof Number n) {
      temperature = n.doubleValue();
    }
    Integer maxTokens = null;
    if (maxTokensObj instanceof Number n) {
      int v = n.intValue();
      if (v > 0) maxTokens = v;
    }

    currentLlmConfig =
        new LlmConfig(
            chatCompletionsUrl,
            apiKey,
            model,
            temperature,
            maxTokens,
            systemPrompt,
            toolsObj,
            toolChoiceObj);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("chatCompletionsUrl", chatCompletionsUrl);
    payload.put("model", model);
    payload.put("temperature", temperature);
    payload.put("maxTokens", maxTokens);
    if (systemPrompt != null && !systemPrompt.isBlank()) {
      payload.put("systemPrompt", systemPrompt);
    }
    sendResponse(session, req.getId(), true, payload, null);
  }

  private String buildChatCompletionsUrlFromBaseUrl(String baseUrl) {
    String b = baseUrl.trim();
    if (b.endsWith("/chat/completions")) {
      return b;
    }
    if (b.contains("/chat/completions")) {
      return b;
    }
    // Volcengine CodingPlan OpenAI-compatible base (avoid appending /v1/chat/completions).
    if (b.endsWith("/api/coding/v3")) {
      return b + "/chat/completions";
    }
    if (b.endsWith("/v1")) {
      return b + "/chat/completions";
    }
    if (b.endsWith("/")) {
      return b + "v1/chat/completions";
    }
    return b + "/v1/chat/completions";
  }

  private LlmConfig resolveLlmConfigOrNull() {
    LlmConfig cfg = currentLlmConfig;
    if (cfg != null && cfg.chatCompletionsUrl != null && !cfg.chatCompletionsUrl.isBlank()) {
      return cfg;
    }

    String chatCompletionsUrl = System.getenv().getOrDefault("OPENCLAW_LLM_CHAT_COMPLETIONS_URL", "").trim();
    String baseUrl = System.getenv().getOrDefault("OPENCLAW_LLM_BASE_URL", "").trim();
    String apiKey = System.getenv().getOrDefault("OPENCLAW_LLM_API_KEY", "").trim();
    String model = System.getenv().getOrDefault("OPENCLAW_LLM_MODEL", "").trim();

    if ((chatCompletionsUrl == null || chatCompletionsUrl.isBlank()) && (baseUrl != null && !baseUrl.isBlank())) {
      chatCompletionsUrl = buildChatCompletionsUrlFromBaseUrl(baseUrl);
    }
    if (apiKey == null || apiKey.isBlank()) return null;
    if (model == null || model.isBlank()) return null;
    if (chatCompletionsUrl == null || chatCompletionsUrl.isBlank()) return null;

    Double temperature = null;
    String tRaw = System.getenv().getOrDefault("OPENCLAW_LLM_TEMPERATURE", "").trim();
    if (!tRaw.isBlank()) {
      try {
        temperature = Double.parseDouble(tRaw);
      } catch (Exception ignored) {
        temperature = null;
      }
    }

    Integer maxTokens = null;
    String mtRaw = System.getenv().getOrDefault("OPENCLAW_LLM_MAX_TOKENS", "").trim();
    if (!mtRaw.isBlank()) {
      try {
        maxTokens = Integer.parseInt(mtRaw);
      } catch (Exception ignored) {
        maxTokens = null;
      }
    }

    return new LlmConfig(
        chatCompletionsUrl,
        apiKey,
        model,
        temperature,
        maxTokens,
        null,
        null,
        null);
  }

  private void handleMemoryPut(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String agentId = optionalNonEmptyString(params, "agentId");
    if (agentId == null) agentId = "default";
    String path = optionalNonEmptyString(params, "path");
    String content = optionalNonEmptyString(params, "content");
    if (path == null || content == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "memory.put: path and content required"));
      return;
    }
    try {
      sqlMemory.put(agentId, path, content);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "memory.put: " + e.getMessage()));
      return;
    }
    sendResponse(session, req.getId(), true, Map.of("ok", true, "agentId", agentId), null);
  }

  private void handleMemorySearch(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String agentId = optionalNonEmptyString(params, "agentId");
    if (agentId == null) agentId = "default";
    String query = optionalNonEmptyString(params, "query");
    if (query == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "memory.search: query required"));
      return;
    }
    int limit = optionalPositiveInt(params, "limit", 20);
    try {
      List<MemoryHit> hits = sqlMemory.search(agentId, query, limit);
      List<Map<String, Object>> rows = new ArrayList<>();
      for (MemoryHit h : hits) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", h.id());
        row.put("path", h.path());
        row.put("content", h.content());
        row.put("createdAtMs", h.createdAtMs());
        row.put("chunkIndex", h.chunkIndex());
        rows.add(row);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("agentId", agentId);
      payload.put("query", query);
      payload.put("hits", rows);
      sendResponse(session, req.getId(), true, payload, null);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "memory.search: " + e.getMessage()));
    }
  }

  @SuppressWarnings("unused")
  private void handlePluginsList(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("plugins", pluginLoader.listLoaded());
    sendResponse(session, req.getId(), true, payload, null);
  }

  @SuppressWarnings("unused")
  private void handleAgentToolsList(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tools", toolRegistry.openAiTools());
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

  private ChatSendLlmOptions parseChatSendLlmOptions(Map<String, Object> params) {
    if (params == null) {
      return ChatSendLlmOptions.DEFAULT;
    }
    int rr = 0;
    Object o = params.get("reflectionRounds");
    if (o instanceof Number n) {
      rr = Math.min(3, Math.max(0, n.intValue()));
    }
    String rp = optionalNonEmptyString(params, "reflectionPrompt");
    String ag = optionalNonEmptyString(params, "autonomousGoalId");
    if (ag == null) {
      ag = optionalNonEmptyString(params, "autonomousTaskId");
    }
    return new ChatSendLlmOptions(rr, rp, ag);
  }

  private void handleChatSend(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String sessionKey = optionalNonEmptyString(params, "sessionKey");
    String message = optionalNonEmptyString(params, "message");
    if (message == null) {
      message = "";
    }
    boolean hasAttachments =
        params != null
            && params.get("attachments") instanceof List<?> att
            && !att.isEmpty();
    if (sessionKey == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "chat.send: sessionKey required"));
      return;
    }
    if (message.isBlank() && !hasAttachments) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "chat.send: message or attachments required"));
      return;
    }
    if (hasAttachments) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(
              ErrorCodes.INVALID_REQUEST,
              "chat.send: attachments are not supported on the Java gateway yet"));
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

    String runId = optionalNonEmptyString(params, "idempotencyKey");
    if (runId == null) {
      runId = UUID.randomUUID().toString();
    }
    ChatSendLlmOptions llmOpts = parseChatSendLlmOptions(params);
    chatRunRegistry.register(sessionKey, runId, llmOpts.autonomousGoalId());

    int before = entry.messages.size();
    sessionStore.addMessage(sessionKey, message);
    int after = entry.messages.size();
    int messageSeq = after > before ? before + 1 : after;

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("aborted", false);
    payload.put("runId", runId);
    payload.put("runIds", List.of(runId));
    payload.put("sessionKey", sessionKey);
    payload.put("messageSeq", messageSeq);
    sendResponse(session, req.getId(), true, payload, null);

    publishUserMessageSideEffects(sessionKey, message, messageSeq, "chat.send", llmOpts, runId);
  }

  /**
   * HTTP channel inbound: append user message and schedule the same LLM path as {@code chat.send}
   * (no WebSocket response).
   *
   * @return assigned {@code messageSeq}
   */
  public int ingestHttpChannelMessage(String sessionKey, String message) {
    if (sessionKey == null || sessionKey.isBlank()) {
      throw new IllegalArgumentException("sessionKey required");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message required");
    }
    InMemorySessionStore.SessionEntry entry = sessionStore.get(sessionKey);
    if (entry == null) {
      throw new IllegalArgumentException("session not found");
    }
    int before = entry.messages.size();
    sessionStore.addMessage(sessionKey, message);
    int after = entry.messages.size();
    int messageSeq = after > before ? before + 1 : after;
    publishUserMessageSideEffects(
        sessionKey,
        message,
        messageSeq,
        "channel.http.message",
        ChatSendLlmOptions.DEFAULT,
        null);
    return messageSeq;
  }

  private void publishUserMessageSideEffects(
      String sessionKey,
      String message,
      int messageSeq,
      String traceEventType,
      ChatSendLlmOptions llmOptions,
      String chatRunId) {
    emitSessionsChanged(sessionKey, "send");
    emitSessionsMessage(sessionKey, messageSeq, message);

    Map<String, Object> tracePayload = new LinkedHashMap<>();
    tracePayload.put("messageSeq", messageSeq);
    tracePayload.put("message", message);
    tracePayload.put("ts", System.currentTimeMillis());
    if (llmOptions != null && llmOptions.reflectionRounds() > 0) {
      tracePayload.put("reflectionRounds", llmOptions.reflectionRounds());
    }
    sessionStore.addEvent(sessionKey, traceEventType, tracePayload);

    // Dispatch @mentions to staff sessions for project-* sessions
    getMentionDispatcher().dispatch(sessionKey, message, messageSeq);

    scheduleLlmAfterUserMessage(sessionKey, message, llmOptions, chatRunId);
  }

  private void scheduleLlmAfterUserMessage(
      String sessionKey,
      String lastUserMessage,
      ChatSendLlmOptions llmOptions,
      String chatRunId) {
    final String taskSessionKey = sessionKey;
    final ChatSendLlmOptions opts = llmOptions != null ? llmOptions : ChatSendLlmOptions.DEFAULT;
    final String rid = chatRunId;
    LLM_EXECUTOR.submit(
        () -> {
          try {
            handleLlmForChatSend(taskSessionKey, lastUserMessage, opts, rid);
          } catch (Exception e) {
            handleLlmError(taskSessionKey, e, rid);
          }
        });
  }

  private void handleLlmError(String sessionKey, Exception e, String chatRunId) {
    InMemorySessionStore.SessionEntry entry = sessionStore.get(sessionKey);
    if (entry == null) {
      if (chatRunId != null) {
        chatRunRegistry.unregister(sessionKey, chatRunId);
      }
      return;
    }
    String assistantText = "LLM error: " + String.valueOf(e.getMessage());

    int before = entry.messages.size();
    sessionStore.addMessage(sessionKey, assistantText);
    int after = entry.messages.size();
    int assistantSeq = after > before ? before + 1 : after;

    emitSessionsChanged(sessionKey, "llm.error");
    emitSessionsMessage(sessionKey, assistantSeq, assistantText);

    Map<String, Object> errPayload = new LinkedHashMap<>();
    errPayload.put("ts", System.currentTimeMillis());
    errPayload.put("message", e.getMessage());
    sessionStore.addEvent(sessionKey, "llm.error", errPayload);

    if (chatRunId != null) {
      String gid = chatRunRegistry.getAutonomousGoalId(chatRunId);
      if (gid != null) {
        Map<String, Object> pl = new LinkedHashMap<>();
        pl.put("sessionKey", sessionKey);
        pl.put("runId", chatRunId);
        pl.put("errorPreview", trimPreview(String.valueOf(e.getMessage()), 400));
        recordAutonomousGoalRound(gid, "round.error", pl);
      }
      emitChatFinal(sessionKey, chatRunId, assistantText);
      chatRunRegistry.unregister(sessionKey, chatRunId);
    }
  }

  private void handleLlmForChatSend(
      String sessionKey,
      String lastUserMessage,
      ChatSendLlmOptions llmOptions,
      String chatRunId) throws Exception {
    ChatLlmExecutor.ChatSendLlmOptions execOpts =
        new ChatLlmExecutor.ChatSendLlmOptions(
            llmOptions.reflectionRounds(),
            llmOptions.reflectionPrompt(),
            llmOptions.autonomousGoalId());
    ChatLlmExecutor.LlmConfigResolver resolver = () -> {
      LlmConfig c = resolveLlmConfigOrNull();
      if (c == null) return null;
      return new ChatLlmExecutor.LlmConfig(
          c.chatCompletionsUrl,
          c.apiKey,
          c.model,
          c.systemPrompt,
          c.temperature != null ? c.temperature : 0.7,
          c.maxTokens != null ? c.maxTokens : 4096,
          c.tools,
          c.toolChoice);
    };
    try {
      ChatLlmExecutor.forSession(
          sessionKey,
          lastUserMessage,
          execOpts,
          chatRunId,
          sessionStore,
          sqlMemory,
          toolRegistry,
          getCtx(),
          resolver,
          chatRunRegistry).
          execute();
    } catch (Exception e) {
      handleLlmError(
            sessionKey,
            new IllegalStateException(
                "missing LLM config (llm.config.set or OPENCLAW_LLM_* env vars)"),
            chatRunId);
        return;
      }
  }

  private void handleChatHistory(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String sessionKey = optionalNonEmptyString(params, "sessionKey");
    if (sessionKey == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "chat.history: sessionKey required"));
      return;
    }
    InMemorySessionStore.SessionEntry entry = sessionStore.get(sessionKey);
    if (entry == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "chat.history: session not found"));
      return;
    }
    int limit = optionalPositiveInt(params, "limit", 200);
    List<Map<String, Object>> messages = sessionStore.buildChatHistoryMessages(sessionKey, limit);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sessionKey", sessionKey);
    payload.put("sessionId", entry.sessionId);
    payload.put("messages", messages);
    payload.put("thinkingLevel", entry.thinkingLevel);
    payload.put("verboseLevel", entry.verboseLevel);
    payload.put("fastMode", entry.fastMode);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleChatAbort(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String sessionKey = optionalNonEmptyString(params, "sessionKey");
    if (sessionKey == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "chat.abort: sessionKey required"));
      return;
    }
    String runId = optionalNonEmptyString(params, "runId");
    int n;
    if (runId != null) {
      n = chatRunRegistry.cancelRun(runId) ? 1 : 0;
    } else {
      n = chatRunRegistry.cancelAllForSession(sessionKey);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("aborted", n > 0);
    payload.put("cancelledCount", n);
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleSessionsPatch(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String key = optionalNonEmptyString(params, "key");
    if (key == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "sessions.patch: key required"));
      return;
    }
    if (sessionStore.get(key) == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "sessions.patch: session not found"));
      return;
    }
    sessionStore.patchSession(key, params);
    sendResponse(session, req.getId(), true, Map.of("ok", true, "key", key), null);
    emitSessionsChanged(key, "patch");
  }

  private void handleSessionsReset(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String key = optionalNonEmptyString(params, "key");
    if (key == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "sessions.reset: key required"));
      return;
    }
    if (sessionStore.get(key) == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "sessions.reset: session not found"));
      return;
    }
    sessionStore.resetTranscript(key);
    sendResponse(session, req.getId(), true, Map.of("ok", true, "key", key), null);
    emitSessionsChanged(key, "reset");
  }

  private void handleSessionsCompact(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String key = optionalNonEmptyString(params, "key");
    if (key == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "sessions.compact: key required"));
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("key", key);
    payload.put("compacted", false);
    payload.put("reason", "java_gateway: compaction not implemented (no-op)");
    sendResponse(session, req.getId(), true, payload, null);
  }

  private void handleConfigSet(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String raw = requireNonEmptyString(params, "raw");
    if (raw == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "config.set: raw required"));
      return;
    }
    String baseHash = optionalNonEmptyString(params, "baseHash");
    if (baseHash != null && !baseHash.isBlank()) {
      try {
        ConfigSnapshot rawSnap = configLoader.loadRaw();
        if (rawSnap.isExists() && rawSnap.getConfigPath() != null) {
          byte[] bytes = Files.readAllBytes(Path.of(rawSnap.getConfigPath()));
          String actual = sha256Hex(bytes);
          if (!baseHash.equalsIgnoreCase(actual)) {
            sendResponse(
                session,
                req.getId(),
                false,
                null,
                ErrorShape.of(ErrorCodes.INVALID_REQUEST, "config.set: baseHash mismatch"));
            return;
          }
        }
      } catch (Exception e) {
        sendResponse(
            session,
            req.getId(),
            false,
            null,
            ErrorShape.of(ErrorCodes.UNAVAILABLE, "config.set: " + e.getMessage()));
        return;
      }
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
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "config.set: invalid json: " + e.getMessage()));
      return;
    }
    try {
      configWriter.write(parsed);
      sendResponse(
          session,
          req.getId(),
          true,
          Map.of("ok", true, "path", configWriter.getConfigPath()),
          null);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.UNAVAILABLE, "config.set: " + e.getMessage()));
    }
  }

  private static String sha256Hex(byte[] data) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(md.digest(data));
  }

  private void handleLogsTail(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    int limit = optionalPositiveInt(params, "limit", 200);
    limit = Math.min(2000, Math.max(1, limit));
    String envPath = System.getenv("OPENCLAW_LOG_FILE");
    Path path = envPath != null && !envPath.isBlank() ? Path.of(envPath.trim()) : null;
    if (path == null) {
      try {
        ConfigSnapshot snap = configLoader.load();
        if (snap.getConfigPath() != null) {
          path = Path.of(snap.getConfigPath()).getParent().resolve("gateway-java.log");
        }
      } catch (Exception ignored) {
        path = null;
      }
    }
    if (path == null || !Files.isRegularFile(path)) {
      sendResponse(
          session,
          req.getId(),
          true,
          Map.of("lines", List.of(), "path", path != null ? path.toString() : ""),
          null);
      return;
    }
    try {
      List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
      int start = Math.max(0, all.size() - limit);
      sendResponse(
          session,
          req.getId(),
          true,
          Map.of("lines", all.subList(start, all.size()), "path", path.toString()),
          null);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.UNAVAILABLE, "logs.tail: " + e.getMessage()));
    }
  }

  private void handleLastHeartbeat(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(
        session,
        req.getId(),
        true,
        Map.of("ts", System.currentTimeMillis(), "ok", true),
        null);
  }

  @SuppressWarnings("unchecked")
  private void handleAgentIdentityGet(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    try {
      ConfigSnapshot snap = configLoader.load();
      Map<String, Object> cfg = (Map<String, Object>) snap.getConfig();
      Map<String, Object> agents = cfg.get("agents") instanceof Map ? (Map<String, Object>) cfg.get("agents") : Map.of();
      Map<String, Object> defaults =
          agents.get("defaults") instanceof Map ? (Map<String, Object>) agents.get("defaults") : Map.of();
      String name =
          defaults.get("name") instanceof String s
              ? s
              : defaults.get("displayName") instanceof String d ? d : "OpenClaw";
      String model = defaults.get("model") instanceof String m ? m : null;
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("name", name);
      payload.put("model", model);
      sendResponse(session, req.getId(), true, payload, null);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.UNAVAILABLE, "agent.identity.get: " + e.getMessage()));
    }
  }

  private void handleSystemPresence(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(
        session,
        req.getId(),
        true,
        Map.of("online", true, "ts", System.currentTimeMillis()),
        null);
  }

  private void handleUsageCost(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(
        session,
        req.getId(),
        true,
        Map.of("cost", List.of(), "currency", "USD", "note", "java_gateway_stub"),
        null);
  }

  private void handleSessionsUsage(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(
        session,
        req.getId(),
        true,
        Map.of("usage", Map.of(), "note", "java_gateway_stub"),
        null);
  }

  private void handleSessionsUsageTimeseries(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(
        session,
        req.getId(),
        true,
        Map.of("points", List.of(), "note", "java_gateway_stub"),
        null);
  }

  private void handleSessionsUsageLogs(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(
        session,
        req.getId(),
        true,
        Map.of("items", List.of(), "note", "java_gateway_stub"),
        null);
  }

  private void handleExecApprovalResolve(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(session, req.getId(), true, Map.of("ok", true, "note", "java_gateway_noop"), null);
  }

  private void handleConfigOpenFile(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(
        session,
        req.getId(),
        false,
        null,
        ErrorShape.of(
            ErrorCodes.UNAVAILABLE,
            "config.openFile is not supported on the Java gateway (open the file locally)"));
  }

  private void handleTtsStatus(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(
        session,
        req.getId(),
        true,
        Map.of("enabled", false, "provider", null, "note", "java_gateway_stub"),
        null);
  }

  private void handleTtsProviders(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(session, req.getId(), true, Map.of("providers", List.of()), null);
  }

  private void handleBrowserRequest(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String sessionKey = optionalNonEmptyString(params, "sessionKey");
    BrowserRequestOutcome out = browserProxyNodeBridge.handleRequest(params, sessionKey);
    if (out instanceof BrowserProxyNodeBridge.BrowserOk ok) {
      sendResponse(session, req.getId(), true, ok.result(), null);
    } else if (out instanceof BrowserProxyNodeBridge.BrowserErr err) {
      sendResponse(session, req.getId(), false, null, err.error());
    } else {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.UNAVAILABLE, "browser.request: unexpected outcome"));
    }
  }

  private void handleDevicePairList(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(session, req.getId(), true, Map.of("requests", List.of()), null);
  }

  private void handleDevicePairApprove(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(session, req.getId(), true, Map.of("ok", true, "note", "java_gateway_noop"), null);
  }

  private void handleDevicePairReject(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(session, req.getId(), true, Map.of("ok", true, "note", "java_gateway_noop"), null);
  }

  private void handleDeviceTokenRevoke(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(session, req.getId(), true, Map.of("ok", true, "note", "java_gateway_noop"), null);
  }

  private void handleAutonomousGoalsCreate(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    try {
      sendResponse(session, req.getId(), true, autonomousGoalService.create(params), null);
    } catch (IllegalArgumentException e) {
      sendResponse(
          session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, e.getMessage()));
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "autonomous.goals.create: " + e.getMessage()));
    }
  }

  private void handleAutonomousGoalsGet(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String id = optionalNonEmptyString(params, "id");
    if (id == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "autonomous.goals.get: id required"));
      return;
    }
    Integer eventLimit = null;
    if (params != null && params.get("eventLimit") instanceof Number n) {
      eventLimit = n.intValue();
    }
    try {
      Map<String, Object> got = autonomousGoalService.get(id, eventLimit);
      if (got == null) {
        sendResponse(
            session,
            req.getId(),
            false,
            null,
            ErrorShape.of(ErrorCodes.INVALID_REQUEST, "autonomous.goals.get: goal not found"));
        return;
      }
      sendResponse(session, req.getId(), true, got, null);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "autonomous.goals.get: " + e.getMessage()));
    }
  }

  private void handleAutonomousGoalsList(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    try {
      sendResponse(
          session,
          req.getId(),
          true,
          Map.of("goals", autonomousGoalService.listSummaries()),
          null);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "autonomous.goals.list: " + e.getMessage()));
    }
  }

  private void handleAutonomousGoalsPatch(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String id = optionalNonEmptyString(params, "id");
    if (id == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "autonomous.goals.patch: id required"));
      return;
    }
    Map<String, Object> patch = new LinkedHashMap<>();
    if (params != null) {
      patch.putAll(params);
    }
    patch.remove("id");
    if (patch.isEmpty()) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "autonomous.goals.patch: no fields to patch"));
      return;
    }
    try {
      Map<String, Object> out = autonomousGoalService.patch(id, patch);
      if (out == null) {
        sendResponse(
            session,
            req.getId(),
            false,
            null,
            ErrorShape.of(ErrorCodes.INVALID_REQUEST, "autonomous.goals.patch: goal not found"));
        return;
      }
      String gid = id;
      Object gObj = out.get("goal");
      if (gObj instanceof Map<?, ?> gm && gm.get("id") instanceof String ids) {
        gid = ids;
      }
      recordAutonomousGoalRound(
          gid, "goal.patched", Map.of("patchedKeys", new ArrayList<>(patch.keySet())));
      sendResponse(session, req.getId(), true, out, null);
    } catch (IllegalArgumentException e) {
      sendResponse(
          session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, e.getMessage()));
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "autonomous.goals.patch: " + e.getMessage()));
    }
  }

  private void handleCronList(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(session, req.getId(), true, gatewayCronService.listJobsRpc(params), null);
  }

  private void handleCronStatus(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(session, req.getId(), true, gatewayCronService.statusRpc(), null);
  }

  private void handleCronAdd(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    try {
      sendResponse(session, req.getId(), true, gatewayCronService.addJob(params), null);
    } catch (IllegalArgumentException e) {
      sendResponse(
          session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, e.getMessage()));
    }
  }

  private void handleCronUpdate(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    try {
      sendResponse(session, req.getId(), true, gatewayCronService.updateJobRpc(params), null);
    } catch (IllegalArgumentException e) {
      sendResponse(
          session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, e.getMessage()));
    }
  }

  private void handleCronRemove(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String id = optionalNonEmptyString(params, "id");
    if (id == null) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "cron.remove: id required"));
      return;
    }
    gatewayCronService.removeJob(id);
    sendResponse(session, req.getId(), true, Map.of("ok", true), null);
  }

  private void handleCronRun(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    try {
      sendResponse(session, req.getId(), true, gatewayCronService.runJobRpc(params), null);
    } catch (IllegalArgumentException e) {
      sendResponse(
          session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, e.getMessage()));
    }
  }

  private void handleCronRuns(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    sendResponse(session, req.getId(), true, gatewayCronService.listRunsRpc(params), null);
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

    String sessionKey = optionalNonEmptyString(params, "sessionKey");

    Object rawParams = params.get("params");
    String paramsJSON = null;
    // Support passing `sessionKey` inside the opaque `params` blob as a convenience.
    if (sessionKey == null && rawParams instanceof Map<?, ?> m) {
      Object sk = m.get("sessionKey");
      if (sk instanceof String s) {
        String trimmed = s.trim();
        if (!trimmed.isEmpty()) sessionKey = trimmed;
      }
    }
    if (sessionKey == null) {
      sessionKey = optionalNonEmptyString(params, "taskSessionKey");
    }

    if (rawParams != null) {
      try {
        paramsJSON = MAPPER.writeValueAsString(rawParams);
      } catch (Exception ignored) {
        paramsJSON = null;
      }
    }

    CompletableFuture<NodeInvokeResolution> waiter =
        nodeInvoke.registerWaiterAndEnqueue(nodeId, id, command, paramsJSON, sessionKey);

    final String responseId = req.getId();
    nodeInvoke.runCallback(
        () -> {
          try {
            NodeInvokeResolution resolution = waiter.get(waitTimeoutMs, TimeUnit.MILLISECONDS);
            if (resolution.ok()) {
              Map<String, Object> payload = new LinkedHashMap<>();
              payload.put("ok", true);
              payload.put("nodeId", nodeId);
              payload.put("command", command);
              payload.put("payload", resolution.payload());
              payload.put("payloadJSON", resolution.payloadJSON());
              sendResponse(session, responseId, true, payload, null);
            } else {
              sendResponse(session, responseId, false, null, resolution.error());
            }
          } catch (TimeoutException e) {
            nodeInvoke.discardWaiter(id);
            ErrorShape err =
                ErrorShape.of(
                    ErrorCodes.AGENT_TIMEOUT,
                    "node.invoke timeout waiting for node.invoke.result");
            sendResponse(session, responseId, false, null, err);
          } catch (Exception e) {
            nodeInvoke.discardWaiter(id);
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

    boolean hadWaiter =
        nodeInvoke.completeInvokeResult(
            id,
            nodeId,
            ok,
            params.get("payload"),
            optionalNonEmptyString(params, "payloadJSON"),
            params.get("error"),
            sessionStore);

    if (!hadWaiter) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("ok", true);
      payload.put("ignored", true);
      sendResponse(session, req.getId(), true, payload, null);
      return;
    }

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
      expiresAtMs = now + Math.max(1_000L, (long) Math.floor(expiresInMs.doubleValue()));
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
    List<Map<String, Object>> actions =
        nodeInvoke.snapshotPendingActions(nodeId);
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

    nodeInvoke.prunePendingNodeActions(nodeId, System.currentTimeMillis());
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

    int remaining = nodeInvoke.ackPendingActions(nodeId, ids);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("nodeId", nodeId);
    payload.put("ackedIds", ids);
    payload.put("remainingCount", remaining);
    sendResponse(session, req.getId(), true, payload, null);
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

  private void handleSkillsStatus(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String perr = SkillsWsParams.validateStatus(params);
    if (perr != null) {
      sendResponse(
          session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, perr));
      return;
    }
    String agentId = optionalNonEmptyString(params, "agentId");
    try {
      Map<String, Object> report = gatewaySkillsService.skillsStatus(agentId);
      sendResponse(session, req.getId(), true, report, null);
    } catch (IllegalArgumentException e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, e.getMessage()));
    }
  }

  private void handleSkillsBins(WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String perr = SkillsWsParams.validateBins(params);
    if (perr != null) {
      sendResponse(
          session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, perr));
      return;
    }
    sendResponse(session, req.getId(), true, gatewaySkillsService.skillsBins(), null);
  }

  private void handleSkillsInstall(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String perr = SkillsWsParams.validateInstall(params);
    if (perr != null) {
      sendResponse(
          session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, perr));
      return;
    }
    String name = optionalNonEmptyString(params, "name");
    String installId = optionalNonEmptyString(params, "installId");
    Long timeoutMs = null;
    Object t = params != null ? params.get("timeoutMs") : null;
    if (t instanceof Integer i) {
      timeoutMs = i.longValue();
    } else if (t instanceof Long l) {
      timeoutMs = l;
    }
    try {
      Map<String, Object> result = gatewaySkillsService.skillsInstall(name, installId, timeoutMs);
      if (!Boolean.TRUE.equals(result.get("ok"))) {
        sendResponse(
            session,
            req.getId(),
            false,
            null,
            ErrorShape.of(
                ErrorCodes.UNAVAILABLE, String.valueOf(result.getOrDefault("message", "install failed"))));
        return;
      }
      sendResponse(session, req.getId(), true, result, null);
    } catch (Exception e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.UNAVAILABLE, e.getMessage()));
    }
  }

  @SuppressWarnings("unchecked")
  private void handleSkillsUpdate(
      WebSocketSession session, RequestFrame req, Map<String, Object> params) {
    String perr = SkillsWsParams.validateUpdate(params);
    if (perr != null) {
      sendResponse(
          session, req.getId(), false, null, ErrorShape.of(ErrorCodes.INVALID_REQUEST, perr));
      return;
    }
    String skillKey = optionalNonEmptyString(params, "skillKey");
    Boolean enabled = null;
    Object en = params.get("enabled");
    if (en instanceof Boolean b) {
      enabled = b;
    }
    String apiKey = null;
    Object ak = params.get("apiKey");
    if (ak instanceof String s) {
      apiKey = s;
    }
    Map<String, String> envPatch = null;
    Object env = params.get("env");
    if (env instanceof Map<?, ?> em) {
      envPatch = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : em.entrySet()) {
        if (e.getKey() instanceof String k && e.getValue() instanceof String v) {
          envPatch.put(k, v);
        }
      }
    }
    try {
      Map<String, Object> out = gatewaySkillsService.skillsUpdate(skillKey, enabled, apiKey, envPatch);
      sendResponse(session, req.getId(), true, out, null);
    } catch (IllegalArgumentException e) {
      sendResponse(
          session,
          req.getId(),
          false,
          null,
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, e.getMessage()));
    }
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
      long lv = l;
      return lv > 0 && lv <= Integer.MAX_VALUE ? (int) lv : defaultValue;
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

  private void emitChatBroadcast(Map<String, Object> payload) {
    for (Map.Entry<String, WebSocketSession> e : ACTIVE_SESSIONS.entrySet()) {
      WebSocketSession ws = e.getValue();
      if (ws == null || !ws.isOpen()) {
        continue;
      }
      WsContext ctx = (WsContext) ws.getAttributes().get(WsContext.KEY);
      if (ctx == null || !ctx.connected) {
        continue;
      }
      emitEvent(ws, ctx, "chat", payload);
    }
  }

  private void emitChatFinal(String sessionKey, String runId, String assistantText) {
    if (runId == null) {
      return;
    }
    long seq = chatRunRegistry.nextChatSeq();
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("role", "assistant");
    msg.put("content", List.of(Map.of("type", "text", "text", assistantText)));
    msg.put("timestamp", System.currentTimeMillis());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("runId", runId);
    payload.put("sessionKey", sessionKey);
    payload.put("seq", seq);
    payload.put("state", "final");
    payload.put("message", msg);
    emitChatBroadcast(payload);
  }

  private void emitChatAborted(String sessionKey, String runId) {
    if (runId == null) {
      return;
    }
    long seq = chatRunRegistry.nextChatSeq();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("runId", runId);
    payload.put("sessionKey", sessionKey);
    payload.put("seq", seq);
    payload.put("state", "aborted");
    emitChatBroadcast(payload);
    String gid = chatRunRegistry.getAutonomousGoalId(runId);
    if (gid != null) {
      Map<String, Object> pl = new LinkedHashMap<>();
      pl.put("sessionKey", sessionKey);
      pl.put("runId", runId);
      recordAutonomousGoalRound(gid, "round.aborted", pl);
    }
  }

  private static String trimPreview(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    String t = text.trim();
    if (t.length() <= maxChars) {
      return t;
    }
    return t.substring(0, maxChars) + "…";
  }

  private void recordAutonomousGoalRound(String goalId, String type, Map<String, Object> payload) {
    if (goalId == null || goalId.isBlank()) {
      return;
    }
    try {
      if (autonomousGoalService.appendEvent(goalId, type, payload)) {
        emitAutonomousGoalBroadcast(goalId, type, payload);
      }
    } catch (Exception ignored) {
      // Do not break chat delivery on autonomous-goal persistence failures.
    }
  }

  private void emitAutonomousGoalBroadcast(String goalId, String type, Map<String, Object> payload) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("goalId", goalId);
    envelope.put("type", type);
    envelope.put("ts", System.currentTimeMillis());
    if (payload != null && !payload.isEmpty()) {
      envelope.put("payload", payload);
    }
    for (Map.Entry<String, WebSocketSession> e : ACTIVE_SESSIONS.entrySet()) {
      WebSocketSession ws = e.getValue();
      if (ws == null || !ws.isOpen()) {
        continue;
      }
      WsContext ctx = (WsContext) ws.getAttributes().get(WsContext.KEY);
      if (ctx == null || !ctx.connected) {
        continue;
      }
      emitEvent(ws, ctx, "autonomous.goal", envelope);
    }
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
    if (!EMITTABLE_EVENTS.contains(eventName)) return;
    EventFrame frame = new EventFrame();
    frame.setEvent(eventName);
    frame.setPayload(payload);
    frame.setSeq(ctx.nextEventSeq.getAndIncrement());
    send(session, frame);
  }
}
