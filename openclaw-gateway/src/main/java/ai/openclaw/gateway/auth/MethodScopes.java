package ai.openclaw.gateway.auth;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gateway method → operator scope mapping; aligned with Node src/gateway/method-scopes.ts.
 */
public final class MethodScopes {

  public static final String ADMIN_SCOPE = "operator.admin";
  public static final String READ_SCOPE = "operator.read";
  public static final String WRITE_SCOPE = "operator.write";
  public static final String APPROVALS_SCOPE = "operator.approvals";
  public static final String PAIRING_SCOPE = "operator.pairing";

  private static final Set<String> APPROVALS_METHODS =
      Set.of("exec.approval.request", "exec.approval.waitDecision", "exec.approval.resolve");

  private static final Set<String> PAIRING_METHODS =
      Set.of(
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
          "node.rename");

  private static final Set<String> READ_METHODS =
      Set.of(
          "health",
          "doctor.memory.status",
          "logs.tail",
          "channels.status",
          "status",
          "usage.status",
          "usage.cost",
          "tts.status",
          "tts.providers",
          "models.list",
          "tools.catalog",
          "agents.list",
          "agent.identity.get",
          "skills.status",
          "voicewake.get",
          "sessions.list",
          "sessions.get",
          "sessions.preview",
          "sessions.resolve",
          "sessions.subscribe",
          "sessions.unsubscribe",
          "sessions.messages.subscribe",
          "sessions.messages.unsubscribe",
          "sessions.usage",
          "sessions.usage.timeseries",
          "sessions.usage.logs",
          "cron.list",
          "cron.status",
          "cron.runs",
          "gateway.identity.get",
          "system-presence",
          "last-heartbeat",
          "node.list",
          "node.describe",
          "chat.history",
          "config.get",
          "config.schema.lookup",
          "talk.config",
          "agents.files.list",
          "agents.files.get");

  private static final Set<String> WRITE_METHODS =
      Set.of(
          "send",
          "poll",
          "agent",
          "agent.wait",
          "wake",
          "talk.mode",
          "tts.enable",
          "tts.disable",
          "tts.convert",
          "tts.setProvider",
          "voicewake.set",
          "node.invoke",
          "chat.send",
          "chat.abort",
          "sessions.create",
          "sessions.send",
          "sessions.abort",
          "browser.request",
          "push.test",
          "node.pending.enqueue");

  private static final Set<String> ADMIN_METHODS =
      Set.of(
          "channels.logout",
          "agents.create",
          "agents.update",
          "agents.delete",
          "skills.install",
          "skills.update",
          "secrets.reload",
          "secrets.resolve",
          "cron.add",
          "cron.update",
          "cron.remove",
          "cron.run",
          "sessions.patch",
          "sessions.reset",
          "sessions.delete",
          "sessions.compact",
          "connect",
          "chat.inject",
          "web.login.start",
          "web.login.wait",
          "set-heartbeats",
          "system-event",
          "agents.files.set");

  private static final List<String> ADMIN_PREFIXES = List.of("exec.approvals.", "config.", "wizard.", "update.");

  // Methods that are classified as "node-role" and should be allowed when
  // the gateway connection role is `node` (operator scopes are not required).
  private static final Set<String> NODE_ROLE_METHODS =
      Set.of(
          "node.invoke.result",
          "node.event",
          "node.pending.drain",
          "node.canvas.capability.refresh",
          "node.pending.pull",
          "node.pending.ack",
          "skills.bins");

  public static boolean isNodeRoleMethod(String method) {
    return NODE_ROLE_METHODS.contains(method);
  }

  /** Returns required scope for method, or null if unclassified. */
  public static String requiredScopeForMethod(String method) {
    if (APPROVALS_METHODS.contains(method)) {
      return APPROVALS_SCOPE;
    }
    if (PAIRING_METHODS.contains(method)) {
      return PAIRING_SCOPE;
    }
    if (READ_METHODS.contains(method)) {
      return READ_SCOPE;
    }
    if (WRITE_METHODS.contains(method)) {
      return WRITE_SCOPE;
    }
    if (ADMIN_METHODS.contains(method)) {
      return ADMIN_SCOPE;
    }
    for (String prefix : ADMIN_PREFIXES) {
      if (method.startsWith(prefix)) {
        return ADMIN_SCOPE;
      }
    }
    return null;
  }

  /**
   * Authorize client scopes for method. Returns null if allowed, or the missing scope if denied.
   */
  public static String authorize(String method, List<String> scopes) {
    if (scopes != null && scopes.contains(ADMIN_SCOPE)) {
      return null;
    }
    String required = requiredScopeForMethod(method);
    if (required == null) {
      return ADMIN_SCOPE;
    }
    if (READ_SCOPE.equals(required)) {
      if (scopes != null && (scopes.contains(READ_SCOPE) || scopes.contains(WRITE_SCOPE))) {
        return null;
      }
      return READ_SCOPE;
    }
    if (scopes != null && scopes.contains(required)) {
      return null;
    }
    return required;
  }
}

