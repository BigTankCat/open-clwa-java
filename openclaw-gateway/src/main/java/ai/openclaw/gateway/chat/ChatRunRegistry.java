package ai.openclaw.gateway.chat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Tracks active {@code chat.send} runs (idempotencyKey / runId) for {@link
 * ai.openclaw.gateway.ws.GatewayWebSocketHandler#handleChatAbort}.
 */
@Component
public final class ChatRunRegistry {

  private final ConcurrentHashMap<String, AtomicBoolean> cancelledByRunId = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Set<String>> runIdsBySessionKey = new ConcurrentHashMap<>();
  /** Optional {@code autonomousGoalId} / {@code autonomousTaskId} tied to a chat run for progress logging. */
  private final ConcurrentHashMap<String, String> autonomousGoalIdByRunId = new ConcurrentHashMap<>();
  private final AtomicLong chatSeq = new AtomicLong(1);

  public long nextChatSeq() {
    return chatSeq.getAndIncrement();
  }

  public void register(String sessionKey, String runId) {
    register(sessionKey, runId, null);
  }

  public void register(String sessionKey, String runId, String autonomousGoalId) {
    if (runId == null || runId.isBlank()) {
      return;
    }
    cancelledByRunId.putIfAbsent(runId, new AtomicBoolean(false));
    if (autonomousGoalId != null && !autonomousGoalId.isBlank()) {
      autonomousGoalIdByRunId.put(runId.trim(), autonomousGoalId.trim());
    }
    if (sessionKey != null && !sessionKey.isBlank()) {
      runIdsBySessionKey
          .computeIfAbsent(sessionKey, k -> ConcurrentHashMap.newKeySet())
          .add(runId);
    }
  }

  /** Goal id passed with {@code chat.send} for this run, if any. */
  public String getAutonomousGoalId(String runId) {
    if (runId == null || runId.isBlank()) {
      return null;
    }
    return autonomousGoalIdByRunId.get(runId);
  }

  public void unregister(String sessionKey, String runId) {
    if (runId != null) {
      cancelledByRunId.remove(runId);
      autonomousGoalIdByRunId.remove(runId);
    }
    if (sessionKey != null && runId != null) {
      Set<String> set = runIdsBySessionKey.get(sessionKey);
      if (set != null) {
        set.remove(runId);
        if (set.isEmpty()) {
          runIdsBySessionKey.remove(sessionKey, set);
        }
      }
    }
  }

  public boolean isCancelled(String runId) {
    if (runId == null) {
      return false;
    }
    AtomicBoolean b = cancelledByRunId.get(runId);
    return b != null && b.get();
  }

  /** Marks the run cancelled. Returns false if the run was unknown. */
  public boolean cancelRun(String runId) {
    if (runId == null || runId.isBlank()) {
      return false;
    }
    AtomicBoolean b = cancelledByRunId.get(runId);
    if (b == null) {
      return false;
    }
    b.set(true);
    return true;
  }

  /** Cancels all registered runs for the session. Returns count cancelled. */
  public int cancelAllForSession(String sessionKey) {
    if (sessionKey == null || sessionKey.isBlank()) {
      return 0;
    }
    Set<String> ids = runIdsBySessionKey.get(sessionKey);
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    int n = 0;
    for (String id : Set.copyOf(ids)) {
      if (cancelRun(id)) {
        n++;
      }
    }
    return n;
  }
}
