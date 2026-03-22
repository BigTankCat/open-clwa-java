package ai.openclaw.gateway.node;

import ai.openclaw.gateway.sessions.InMemorySessionStore;
import ai.openclaw.protocol.ErrorCodes;
import ai.openclaw.protocol.ErrorShape;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.springframework.stereotype.Service;

/**
 * In-memory queue of pending actions for connected Node peers plus waiters for
 * {@code node.invoke} / {@code node.invoke.result}. Extracted from the WebSocket handler for reuse
 * (e.g. {@code node_invoke} agent tool).
 */
@Service
public final class NodeInvokeService {

  public record PendingNodeInvokeMeta(String nodeId, String command, String sessionKey) {}

  public record NodeInvokeResolution(boolean ok, Object payload, String payloadJSON, ErrorShape error) {}

  private static final long PENDING_ACTION_TTL_MS = 10 * 60_000;
  private static final int PENDING_ACTION_MAX_PER_NODE = 64;

  private final ConcurrentHashMap<String, ConcurrentLinkedQueue<PendingNodeAction>> pendingByNodeId =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CompletableFuture<NodeInvokeResolution>> waitersById =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, PendingNodeInvokeMeta> metaById = new ConcurrentHashMap<>();

  private final ExecutorService callbackExecutor =
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

  /** Runs async work tied to waiting on a Node invoke result (typically WS response send). */
  public void runCallback(Runnable task) {
    callbackExecutor.submit(task);
  }

  /**
   * Registers a waiter, stores metadata for session tracing, and enqueues the action for {@code
   * node.pending.pull}.
   */
  public CompletableFuture<NodeInvokeResolution> registerWaiterAndEnqueue(
      String nodeId,
      String idempotencyKey,
      String command,
      String paramsJSON,
      String sessionKey) {
    CompletableFuture<NodeInvokeResolution> waiter = new CompletableFuture<>();
    waitersById.put(idempotencyKey, waiter);
    metaById.put(idempotencyKey, new PendingNodeInvokeMeta(nodeId, command, sessionKey));
    enqueueNodeAction(nodeId, idempotencyKey, command, paramsJSON);
    return waiter;
  }

  /** Removes waiter/meta without completing the future (e.g. client timeout). */
  public void discardWaiter(String idempotencyKey) {
    waitersById.remove(idempotencyKey);
    metaById.remove(idempotencyKey);
  }

  public void prunePendingNodeActions(String nodeId, long nowMs) {
    if (nodeId == null || nodeId.isBlank()) {
      return;
    }
    ConcurrentLinkedQueue<PendingNodeAction> q = pendingByNodeId.get(nodeId);
    if (q == null || q.isEmpty()) {
      return;
    }

    long minTimestampMs = nowMs - PENDING_ACTION_TTL_MS;
    q.removeIf((a) -> a != null && a.enqueuedAtMs < minTimestampMs);

    while (q.size() > PENDING_ACTION_MAX_PER_NODE) {
      PendingNodeAction toRemove = q.peek();
      if (toRemove == null) {
        break;
      }
      q.remove(toRemove);
    }

    if (q.isEmpty()) {
      pendingByNodeId.remove(nodeId);
    }
  }

  public void enqueueNodeAction(String nodeId, String id, String command, String paramsJSON) {
    PendingNodeAction action =
        new PendingNodeAction(id, command, paramsJSON, System.currentTimeMillis());
    ConcurrentLinkedQueue<PendingNodeAction> q =
        pendingByNodeId.computeIfAbsent(nodeId, k -> new ConcurrentLinkedQueue<>());
    q.removeIf((a) -> id.equals(a.id));
    q.add(action);
    prunePendingNodeActions(nodeId, System.currentTimeMillis());
  }

  public List<Map<String, Object>> snapshotPendingActions(String nodeId) {
    prunePendingNodeActions(nodeId, System.currentTimeMillis());
    ConcurrentLinkedQueue<PendingNodeAction> q = pendingByNodeId.get(nodeId);
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
    return actions;
  }

  public int ackPendingActions(String nodeId, List<String> ids) {
    ConcurrentLinkedQueue<PendingNodeAction> q = pendingByNodeId.get(nodeId);
    if (q != null && !ids.isEmpty()) {
      HashSet<String> toAck = new HashSet<>(ids);
      q.removeIf((a) -> toAck.contains(a.id));
    }
    if (q != null && q.isEmpty()) {
      pendingByNodeId.remove(nodeId);
    }
    return q != null ? q.size() : 0;
  }

  /**
   * Applies a Node result: completes the matching waiter if any, updates the queue, and optionally
   * writes session trace events.
   *
   * @return {@code true} if a waiter was completed; {@code false} if the result was treated as late
   *     / ignored.
   */
  public boolean completeInvokeResult(
      String id,
      String nodeId,
      boolean ok,
      Object payloadObj,
      String payloadJSON,
      Object errorRaw,
      InMemorySessionStore sessionStore) {
    ConcurrentLinkedQueue<PendingNodeAction> q = pendingByNodeId.get(nodeId);
    if (q != null) {
      q.removeIf((a) -> id.equals(a.id));
    }

    CompletableFuture<NodeInvokeResolution> waiter = waitersById.remove(id);
    PendingNodeInvokeMeta meta = metaById.remove(id);
    if (waiter == null) {
      if (meta != null && meta.sessionKey() != null) {
        Map<String, Object> tracePayload = new LinkedHashMap<>();
        tracePayload.put("id", id);
        tracePayload.put("nodeId", nodeId);
        tracePayload.put("command", meta.command());
        tracePayload.put("ok", ok);
        tracePayload.put("payload", payloadObj);
        tracePayload.put("payloadJSON", payloadJSON);
        tracePayload.put("ts", System.currentTimeMillis());
        sessionStore.addEvent(meta.sessionKey(), "node.invoke.result", tracePayload);
      }
      return false;
    }

    ErrorShape err = null;
    if (!ok) {
      err = buildErrorShapeFromNodeError(errorRaw);
    }
    waiter.complete(new NodeInvokeResolution(ok, payloadObj, payloadJSON, err));

    if (meta != null && meta.sessionKey() != null) {
      Map<String, Object> tracePayload = new LinkedHashMap<>();
      tracePayload.put("id", id);
      tracePayload.put("nodeId", nodeId);
      tracePayload.put("command", meta.command());
      tracePayload.put("ok", ok);
      tracePayload.put("payload", payloadObj);
      tracePayload.put("payloadJSON", payloadJSON);
      tracePayload.put(
          "error",
          err != null ? Map.of("code", err.getCode(), "message", err.getMessage()) : null);
      tracePayload.put("ts", System.currentTimeMillis());
      sessionStore.addEvent(meta.sessionKey(), "node.invoke.result", tracePayload);
    }
    return true;
  }

  public ErrorShape buildErrorShapeFromNodeError(Object errorObj) {
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
}
