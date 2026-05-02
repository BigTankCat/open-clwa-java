package ai.openclaw.gateway.ws;

import ai.openclaw.agent.runtime.*;
import ai.openclaw.agent.tools.OpenClawToolRegistry;
import ai.openclaw.gateway.business.ProjectService;
import ai.openclaw.gateway.business.StaffService;
import ai.openclaw.gateway.chat.ChatRunRegistry;
import ai.openclaw.gateway.im.ImNodeBridge;
import ai.openclaw.gateway.sessions.InMemorySessionStore;
import ai.openclaw.llm.OpenAiCompatibleChatClient;
import ai.openclaw.memory.MemoryHit;
import ai.openclaw.memory.SqliteMemoryStore;
import java.util.*;

/**
 * Executes the LLM pipeline for a chat message in any session type.
 * Handles: memory resolution, system prompt building, agent tool-loop,
 * reflection rounds, and response broadcast.
 *
 * <p>This class is NOT thread-safe — one instance per request, created
 * via {@link #forSession}.
 */
public final class ChatLlmExecutor {

  // Shared as package-private for testing
  static final String DEFAULT_REFLECTION_PROMPT =
      "You are a thoughtful reviewer. Critique the response above for clarity, accuracy, and completeness. "
      + "Suggest specific improvements.";

  private final InMemorySessionStore sessionStore;
  private final SqliteMemoryStore sqlMemory;
  private final OpenClawToolRegistry toolRegistry;
  private final SessionContextBuilder ctxBuilder;
  private final LlmConfigResolver configResolver;
  private final ChatRunRegistry chatRunRegistry;
  private final String sessionKey;
  private final String lastUserMessage;
  private final String chatRunId;
  private final ChatSendLlmOptions llmOptions;
  private final OnCompleteCallback onComplete;

  /**
   * Called after LLM response is broadcast to all subscribers (WebSocket + internal).
   * Allows post-processing hooks like IM bridge delivery.
   */
  public interface OnCompleteCallback {
    void onComplete(String sessionKey, InMemorySessionStore.SessionEntry entry);
  }

  /** Convenience factory using default OnCompleteCallback = null. */
  public static ChatLlmExecutor forSession(
      String sessionKey,
      String lastUserMessage,
      ChatSendLlmOptions llmOptions,
      String chatRunId,
      InMemorySessionStore sessionStore,
      SqliteMemoryStore sqlMemory,
      OpenClawToolRegistry toolRegistry,
      SessionContextBuilder ctxBuilder,
      LlmConfigResolver configResolver,
      ChatRunRegistry chatRunRegistry) {
    return forSession(sessionKey, lastUserMessage, llmOptions, chatRunId,
        sessionStore, sqlMemory, toolRegistry, ctxBuilder, configResolver, chatRunRegistry, null);
  }

  /** Full factory with post-processing callback. */
  public static ChatLlmExecutor forSession(
      String sessionKey,
      String lastUserMessage,
      ChatSendLlmOptions llmOptions,
      String chatRunId,
      InMemorySessionStore sessionStore,
      SqliteMemoryStore sqlMemory,
      OpenClawToolRegistry toolRegistry,
      SessionContextBuilder ctxBuilder,
      LlmConfigResolver configResolver,
      ChatRunRegistry chatRunRegistry,
      OnCompleteCallback onComplete) {
    return new ChatLlmExecutor(
        sessionKey, lastUserMessage, llmOptions, chatRunId,
        sessionStore, sqlMemory, toolRegistry, ctxBuilder, configResolver, chatRunRegistry, onComplete);
  }

  private ChatLlmExecutor(
      String sessionKey,
      String lastUserMessage,
      ChatSendLlmOptions llmOptions,
      String chatRunId,
      InMemorySessionStore sessionStore,
      SqliteMemoryStore sqlMemory,
      OpenClawToolRegistry toolRegistry,
      SessionContextBuilder ctxBuilder,
      LlmConfigResolver configResolver,
      ChatRunRegistry chatRunRegistry,
      OnCompleteCallback onComplete) {
    this.sessionKey = sessionKey;
    this.lastUserMessage = lastUserMessage;
    this.llmOptions = llmOptions != null ? llmOptions : ChatSendLlmOptions.DEFAULT;
    this.chatRunId = chatRunId;
    this.sessionStore = sessionStore;
    this.sqlMemory = sqlMemory;
    this.toolRegistry = toolRegistry;
    this.ctxBuilder = ctxBuilder;
    this.configResolver = configResolver;
    this.chatRunRegistry = chatRunRegistry;
    this.onComplete = onComplete;
  }

  /** Executes the full LLM pipeline; returns the assistant text. */
  public String execute() throws Exception {
    InMemorySessionStore.SessionEntry entry = sessionStore.get(sessionKey);
    if (entry == null) {
      if (chatRunId != null) chatRunRegistry.unregister(sessionKey, chatRunId);
      return null;
    }
    if (chatRunId != null && chatRunRegistry.isCancelled(chatRunId)) {
      return null;
    }

    LlmConfig cfg = configResolver.resolve();
    if (cfg == null) {
      return "LLM config missing. Set llm.config.set or OPENCLAW_LLM_* env vars.";
    }

    // 1. Memory resolution
    List<MemoryHit> memoryHits = resolveMemory(entry);

    // 2. System prompt
    List<OpenAiCompatibleChatClient.ChatMessage> llmMessages = new ArrayList<>();
    buildSystemPrompt(entry, memoryHits, cfg, llmMessages);

    // 3. Chat history
    List<String> history = sessionStore.listMessages(sessionKey, 50);
    buildHistory(history, llmMessages);

    // 4. Tool merge
    OpenAiToolsMerge.MergeResult merged =
        OpenAiToolsMerge.mergeWithReport(toolRegistry.openAiTools(), cfg.tools);
    List<Map<String, Object>> mergedTools = merged.tools();
    Object toolsParam = mergedTools.isEmpty() ? null : mergedTools;
    Object toolChoiceParam = toolsParam == null ? null : cfg.toolChoice;

    // Trace
    traceTurnStart(entry, memoryHits, cfg, mergedTools);

    // 5. Agent tool loop
    OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient();
    AgentTurnRunner runner = new AgentTurnRunner(client, toolRegistry, 8);
    LlmInvocationParams inv = new LlmInvocationParams(
        cfg.chatCompletionsUrl, cfg.apiKey, cfg.model, cfg.temperature, cfg.maxTokens);
    ToolExecutionContext toolCtx = new ToolExecutionContext(entry.agentId, sessionKey);
    AgentTraceSink eventSink = (type, payload) -> {
      try { sessionStore.addEvent(sessionKey, type, payload); } catch (Exception ignored) {}
    };

    if (chatRunId != null && chatRunRegistry.isCancelled(chatRunId)) return null;
    String assistantText = runner.run(inv, llmMessages, toolsParam, toolChoiceParam, toolCtx, eventSink);

    if (chatRunId != null && chatRunRegistry.isCancelled(chatRunId)) return null;

    // 6. Reflection rounds
    if (llmOptions.reflectionRounds() > 0) {
      assistantText = runReflections(runner, inv, llmMessages, toolCtx, eventSink, assistantText);
    }

    // 7. Broadcast and persist
    if (assistantText != null) {
      broadcastResponse(assistantText);
    }

    if (chatRunId != null) chatRunRegistry.unregister(sessionKey, chatRunId);
    return assistantText;
  }

  // ─── Memory resolution ───────────────────────────────────────────────────────

  private List<MemoryHit> resolveMemory(InMemorySessionStore.SessionEntry entry) {
    try {
      String sessionType = ctxBuilder.detectSessionType(sessionKey);
      if ("project".equals(sessionType)) {
        Integer projectId = ctxBuilder.parseNumericId(sessionKey);
        List<MemoryHit> roleHits = sqlMemory.search(entry.agentId, lastUserMessage, 8);
        List<MemoryHit> projectHits = projectId != null
            ? sqlMemory.search("project-" + projectId, lastUserMessage, 8)
            : List.of();
        // Merge deduplicate
        Set<Long> seen = new HashSet<>();
        List<MemoryHit> merged = new ArrayList<>();
        for (MemoryHit h : roleHits) { if (seen.add(h.id())) merged.add(h); }
        for (MemoryHit h : projectHits) { if (seen.add(h.id())) merged.add(h); }
        return merged;
      } else {
        return sqlMemory.search(entry.agentId, lastUserMessage, 16);
      }
    } catch (Exception e) {
      return List.of();
    }
  }

  // ─── System prompt ───────────────────────────────────────────────────────────

  private void buildSystemPrompt(
      InMemorySessionStore.SessionEntry entry,
      List<MemoryHit> memoryHits,
      LlmConfig cfg,
      List<OpenAiCompatibleChatClient.ChatMessage> out) {
    String memoryBlock = sqlMemory.formatHitsForPrompt(memoryHits, 8000);
    String execContext = ctxBuilder.buildExecutionContext(sessionKey, entry);

    StringBuilder sb = new StringBuilder();
    if (!execContext.isBlank()) sb.append(execContext.trim());
    if (cfg.systemPrompt != null && !cfg.systemPrompt.isBlank()) {
      if (sb.length() > 0) sb.append("\n\n");
      sb.append(cfg.systemPrompt.trim());
    }
    if (!memoryBlock.isBlank()) {
      if (sb.length() > 0) sb.append("\n\n");
      sb.append("Relevant memory:\n").append(memoryBlock);
    }
    if (sb.length() > 0) {
      out.add(OpenAiCompatibleChatClient.ChatMessage.system(sb.toString()));
    }

    // Trace memory hits
    if (!memoryHits.isEmpty()) {
      Map<String, Object> memTrace = new LinkedHashMap<>();
      memTrace.put("ts", System.currentTimeMillis());
      memTrace.put("agentId", entry.agentId);
      memTrace.put("hitCount", memoryHits.size());
      memTrace.put("topPaths", memoryHits.stream()
          .map(MemoryHit::path).distinct().limit(8).toList());
      try { sessionStore.addEvent(sessionKey, "memory.context", memTrace); } catch (Exception ignored) {}
    }
  }

  // ─── History ─────────────────────────────────────────────────────────────────

  private void buildHistory(List<String> history,
      List<OpenAiCompatibleChatClient.ChatMessage> out) {
    for (int i = 0; i < history.size(); i++) {
      String content = history.get(i);
      String role = (i % 2 == 0) ? "user" : "assistant";
      if ("user".equals(role)) {
        out.add(OpenAiCompatibleChatClient.ChatMessage.user(content));
      } else {
        out.add(OpenAiCompatibleChatClient.ChatMessage.assistantText(content));
      }
    }
  }

  // ─── Turn start trace ─────────────────────────────────────────────────────────

  private void traceTurnStart(InMemorySessionStore.SessionEntry entry,
      List<MemoryHit> memoryHits, LlmConfig cfg, List<Map<String, Object>> mergedTools) {
    try {
      Map<String, Object> preTrace = new LinkedHashMap<>();
      preTrace.put("ts", System.currentTimeMillis());
      preTrace.put("memoryHitCount", memoryHits.size());
      if (cfg.systemPrompt != null && !cfg.systemPrompt.isBlank()) {
        preTrace.put("systemPrompt", cfg.systemPrompt);
      }
      preTrace.put("mergedToolCount", mergedTools.size());
      if (llmOptions.reflectionRounds() > 0) {
        preTrace.put("reflectionRounds", llmOptions.reflectionRounds());
      }
      sessionStore.addEvent(sessionKey, "agent.turn.start", preTrace);
    } catch (Exception ignored) {}
  }

  // ─── Reflection ───────────────────────────────────────────────────────────────

  private String runReflections(
      AgentTurnRunner runner,
      LlmInvocationParams inv,
      List<OpenAiCompatibleChatClient.ChatMessage> llmMessages,
      ToolExecutionContext toolCtx,
      AgentTraceSink eventSink,
      String priorText) {
    String critiqueBase = llmOptions.reflectionPrompt() != null
        && !llmOptions.reflectionPrompt().isBlank()
        ? llmOptions.reflectionPrompt().trim()
        : DEFAULT_REFLECTION_PROMPT;

    for (int r = 0; r < llmOptions.reflectionRounds(); r++) {
      try {
        sessionStore.addEvent(sessionKey, "agent.reflection.start",
            Map.of("ts", System.currentTimeMillis(), "round", r + 1,
                "maxRounds", llmOptions.reflectionRounds()));
      } catch (Exception ignored) {}

      StringBuilder userLine = new StringBuilder();
      userLine.append(critiqueBase);
      if (r > 0) userLine.append("\n\n(Follow-up round ").append(r + 1).append(".)");
      userLine.append("\n\nOriginal user request:\n").append(lastUserMessage);
      llmMessages.add(OpenAiCompatibleChatClient.ChatMessage.user(userLine.toString()));

      try {
        if (chatRunId != null && chatRunRegistry.isCancelled(chatRunId)) return priorText;
      } catch (Exception ignored) {}

      String reflected;
      try {
        reflected = runner.run(inv, llmMessages, null, null, toolCtx, eventSink);
      } catch (Exception e) {
        reflected = "Reflection error: " + e.getMessage();
      }

      try {
        sessionStore.addEvent(sessionKey, "agent.reflection.end",
            Map.of("ts", System.currentTimeMillis(), "round", r + 1,
                "chars", reflected != null ? reflected.length() : 0));
      } catch (Exception ignored) {}

      if (reflected != null) priorText = reflected;
    }
    return priorText;
  }

  // ─── Response broadcast ─────────────────────────────────────────────────────

  private void broadcastResponse(String assistantText) {
    InMemorySessionStore.SessionEntry entry;
    try {
      entry = sessionStore.get(sessionKey);
      int before = entry.messages.size();
      sessionStore.addMessage(sessionKey, assistantText);
      int after = entry.messages.size();
      int messageSeq = after > before ? before + 1 : after;

      sessionStore.addEvent(sessionKey, "agent.turn.end",
          Map.of("ts", System.currentTimeMillis(), "chars", assistantText.length(),
              "messageSeq", messageSeq));

      sessionStore.addEvent(sessionKey, "agent.message",
          Map.of("ts", System.currentTimeMillis(), "messageSeq", messageSeq,
              "text", assistantText));
    } catch (Exception ignored) {
      return;
    }

    // Invoke completion callback (IM bridge delivery, analytics, etc.)
    if (onComplete != null) {
      try {
        onComplete.onComplete(sessionKey, entry);
      } catch (Exception ignored) {}
    }
  }

  // ─── Supporting records ─────────────────────────────────────────────────────

  public record ChatSendLlmOptions(int reflectionRounds, String reflectionPrompt, String autonomousGoalId) {
    public static final ChatSendLlmOptions DEFAULT = new ChatSendLlmOptions(0, null, null);
  }

  /** Resolves LLM config from gateway environment. */
  public interface LlmConfigResolver {
    LlmConfig resolve();
  }

  /** LLM configuration values needed for an invocation. */
  public record LlmConfig(
      String chatCompletionsUrl,
      String apiKey,
      String model,
      String systemPrompt,
      double temperature,
      int maxTokens,
      Object tools,
      Object toolChoice) {}
}