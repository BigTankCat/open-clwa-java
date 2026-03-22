package ai.openclaw.gateway.http;

import ai.openclaw.gateway.ws.GatewayWebSocketHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal HTTP inbound channel: same auth as other gateway HTTP APIs; body mirrors {@code chat.send}
 * fields. Schedules the same async LLM pipeline as WebSocket {@code chat.send}.
 */
@RestController
public class HttpInboundChannelController {

  private final GatewayWebSocketHandler gatewayWebSocketHandler;

  @Value("${OPENCLAW_GATEWAY_TOKEN:}")
  private String gatewayToken;

  public HttpInboundChannelController(GatewayWebSocketHandler gatewayWebSocketHandler) {
    this.gatewayWebSocketHandler = gatewayWebSocketHandler;
  }

  @PostMapping("/api/channel/http/message")
  public ResponseEntity<Map<String, Object>> postMessage(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body) {
    if (!authorized(authorization)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(error("forbidden", "invalid or missing bearer token"));
    }
    if (body == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(error("invalid_request", "JSON body required"));
    }
    String sessionKey = stringVal(body.get("sessionKey"));
    String message = stringVal(body.get("message"));
    if (sessionKey == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(error("invalid_request", "sessionKey required"));
    }
    if (message == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(error("invalid_request", "message required"));
    }
    try {
      int messageSeq = gatewayWebSocketHandler.ingestHttpChannelMessage(sessionKey, message);
      Map<String, Object> ok = new LinkedHashMap<>();
      ok.put("ok", true);
      ok.put("sessionKey", sessionKey);
      ok.put("messageSeq", messageSeq);
      ok.put("aborted", false);
      ok.put("runIds", List.of());
      return ResponseEntity.ok(ok);
    } catch (IllegalArgumentException e) {
      String msg = e.getMessage() != null ? e.getMessage() : "bad request";
      if (msg.contains("not found")) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(error("not_found", msg));
      }
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(error("invalid_request", msg));
    }
  }

  private static String stringVal(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof String s) {
      String t = s.trim();
      return t.isEmpty() ? null : t;
    }
    return String.valueOf(v).trim();
  }

  private boolean authorized(String authorizationHeader) {
    if (gatewayToken == null || gatewayToken.isBlank()) {
      return true;
    }
    if (authorizationHeader == null) {
      return false;
    }
    String trimmed = authorizationHeader.trim();
    if (!trimmed.toLowerCase().startsWith("bearer ")) {
      return false;
    }
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
}
