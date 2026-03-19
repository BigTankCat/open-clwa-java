package ai.openclaw.gateway.http;

import ai.openclaw.gateway.sessions.InMemorySessionStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionsHttpController {

  private final InMemorySessionStore sessionStore;

  @Value("${OPENCLAW_GATEWAY_TOKEN:}")
  private String gatewayToken;

  public SessionsHttpController(InMemorySessionStore sessionStore) {
    this.sessionStore = sessionStore;
  }

  @PostMapping("/sessions/{key}/kill")
  public ResponseEntity<Map<String, Object>> kill(
      @PathVariable("key") String key,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(error("forbidden", "invalid or missing bearer token"));
    }

    if (sessionStore.get(key) == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(notFound(key));
    }
    boolean deleted = sessionStore.delete(key);
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("ok", true);
    res.put("killed", deleted);
    return ResponseEntity.ok(res);
  }

  @GetMapping("/sessions/{key}/history")
  public ResponseEntity<Map<String, Object>> history(
      @PathVariable("key") String key,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "limit", required = false) Integer limit) {
    if (!authorized(authorization)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(error("forbidden", "invalid or missing bearer token"));
    }

    if (sessionStore.get(key) == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(notFound(key));
    }

    int effectiveLimit = (limit == null || limit < 1) ? 1000 : Math.min(1000, limit);
    List<String> messages = sessionStore.listMessages(key, effectiveLimit);

    // Keep shape close to Node's JSON mode; SSE streaming is a later slice.
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("sessionKey", key);
    res.put("items", messages);
    res.put("messages", messages);
    res.put("hasMore", false);
    return ResponseEntity.ok(res);
  }

  private boolean authorized(String authorizationHeader) {
    if (gatewayToken == null || gatewayToken.isBlank()) {
      // Token not configured -> allow (development first slice).
      return true;
    }
    if (authorizationHeader == null) return false;
    String trimmed = authorizationHeader.trim();
    if (!trimmed.toLowerCase().startsWith("bearer ")) return false;
    String token = trimmed.substring("bearer ".length()).trim();
    return token.equals(gatewayToken);
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

