package ai.openclaw.gateway.http;

import ai.openclaw.config.ConfigLoader;
import ai.openclaw.gateway.config.GatewaySettingsReader;
import ai.openclaw.gateway.sessions.InMemorySessionStore;
import ai.openclaw.gateway.sessions.SubagentRunRegistry;
import ai.openclaw.gateway.sessions.SubagentRunRegistry.Run;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * HTTP {@code POST /sessions/{key}/kill} aligned with Node {@code session-kill-http.ts}: local
 * admin, operator bearer token, or requester session owning a registered subagent run.
 */
@Service
public class SessionKillService {

  public static final String REQUESTER_SESSION_KEY_HEADER = "x-openclaw-requester-session-key";

  private final InMemorySessionStore sessionStore;
  private final SubagentRunRegistry subagentRunRegistry;
  private final ConfigLoader configLoader;

  @Value("${OPENCLAW_GATEWAY_TOKEN:}")
  private String gatewayToken;

  public SessionKillService(
      InMemorySessionStore sessionStore,
      SubagentRunRegistry subagentRunRegistry,
      ConfigLoader configLoader) {
    this.sessionStore = sessionStore;
    this.subagentRunRegistry = subagentRunRegistry;
    this.configLoader = configLoader;
  }

  public ResponseEntity<Map<String, Object>> kill(
      String sessionKey,
      String authorization,
      String requesterSessionKey,
      HttpServletRequest request) {
    String key = sessionKey == null ? "" : sessionKey.trim();
    if (key.isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(error("invalid_request", "session key required"));
    }

    Map<String, Object> cfg = configLoader.load().getConfig();
    List<String> trusted = GatewaySettingsReader.trustedProxies(cfg);
    boolean allowReal = GatewaySettingsReader.allowRealIpFallback(cfg);

    String bearer = parseBearer(authorization);
    boolean tokenConfigured = gatewayToken != null && !gatewayToken.isBlank();
    boolean bearerAuthOk = !tokenConfigured || (bearer != null && bearer.equals(gatewayToken));
    boolean allowBearerOperatorKill = tokenConfigured && bearer != null && bearerAuthOk;
    boolean allowLocalAdminKill =
        GatewayHttpNet.isLocalDirectRequest(request, trusted, allowReal);

    String reqKey = requesterSessionKey == null ? "" : requesterSessionKey.trim();

    if (reqKey.isEmpty() && !allowLocalAdminKill && !allowBearerOperatorKill) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(
              error(
                  "forbidden",
                  "Session kills require a local admin request, requester session ownership, or an authorized operator token."));
    }

    if (sessionStore.get(key) == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound(key));
    }

    boolean allowAdminKill = allowLocalAdminKill || allowBearerOperatorKill;
    boolean killed;

    if (!allowAdminKill) {
      Run run = subagentRunRegistry.getByChildSessionKey(key);
      if (run == null) {
        killed = false;
      } else if (!run.controllerSessionKey().equals(reqKey)) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(
                error(
                    "forbidden",
                    "Subagents can only control runs spawned from their own session."));
      } else {
        killed = sessionStore.delete(key);
        if (killed) {
          subagentRunRegistry.removeByChildSessionKey(key);
        }
      }
    } else {
      killed = sessionStore.delete(key);
      if (killed) {
        subagentRunRegistry.removeByChildSessionKey(key);
      }
    }

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("ok", true);
    res.put("killed", killed);
    return ResponseEntity.ok(res);
  }

  static String parseBearer(String authorizationHeader) {
    if (authorizationHeader == null || authorizationHeader.isBlank()) {
      return null;
    }
    String trimmed = authorizationHeader.trim();
    if (!trimmed.toLowerCase().startsWith("bearer ")) {
      return null;
    }
    String token = trimmed.substring("bearer ".length()).trim();
    return token.isEmpty() ? null : token;
  }

  private Map<String, Object> error(String type, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", false);
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("type", type);
    err.put("message", message);
    body.put("error", err);
    return body;
  }

  private Map<String, Object> notFound(String key) {
    return error("not_found", "Session not found: " + key);
  }
}
