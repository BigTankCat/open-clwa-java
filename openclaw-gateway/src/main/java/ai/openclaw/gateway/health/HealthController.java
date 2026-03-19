package ai.openclaw.gateway.health;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP health/ready endpoints; aligned with Node server-http.ts
 * (/health, /healthz = liveness; /ready, /readyz = readiness).
 */
@RestController
public class HealthController {

  @Value("${openclaw.version:2026.3.14}")
  private String version;

  @GetMapping(value = {"/health", "/healthz"})
  public ResponseEntity<Map<String, Object>> live() {
    return ResponseEntity.ok(
        Map.of(
            "ok", true,
            "version", version,
            "ts", System.currentTimeMillis()));
  }

  @GetMapping(value = {"/ready", "/readyz"})
  public ResponseEntity<Map<String, Object>> ready() {
    return ResponseEntity.ok(
        Map.of(
            "ok", true,
            "version", version,
            "ts", System.currentTimeMillis()));
  }
}
