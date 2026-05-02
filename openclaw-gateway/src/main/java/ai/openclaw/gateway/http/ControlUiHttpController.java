package ai.openclaw.gateway.http;

import ai.openclaw.config.ConfigLoader;
import ai.openclaw.config.ConfigSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Control UI HTTP server — serves the built-in admin UI.
 *
 * <p>Serves the OpenClaw Control UI (web-based admin dashboard) and the bootstrap config JSON
 * that the UI reads on startup to learn the gateway URL, assistant identity, and base path.
 *
 * <p>Route structure (mirrors Node gateway server-http.ts):
 * <ul>
 *   <li>GET /ui — redirect to /ui/
 *   <li>GET /ui/** — serve static file from classpath:static/ (SPA fallback: index.html)
 *   <li>GET /__openclaw/control-ui-config.json — bootstrap config JSON
 * </ul>
 */
@RestController
public class ControlUiHttpController {

  private static final String CONTROL_UI_BOOTSTRAP_CONFIG_PATH = "/__openclaw/control-ui-config.json";
  private static final String DEFAULT_ASSISTANT_NAME = "OpenClaw";
  private static final String DEFAULT_AGENT_ID = "default";

  private final ConfigLoader configLoader;

  public ControlUiHttpController(@Autowired ConfigLoader configLoader) {
    this.configLoader = configLoader;
  }

  /**
   * Serves the bootstrap config JSON that the Control UI reads on startup.
   * Mirrors Node gateway: GET /__openclaw/control-ui-config.json
   */
  @GetMapping(CONTROL_UI_BOOTSTRAP_CONFIG_PATH)
  public ResponseEntity<Map<String, Object>> controlUiBootstrapConfig(
      @RequestHeader(value = "Host", required = false) String hostHeader) {
    ConfigSnapshot cfg = configLoader.load();
    Map<String, Object> cfgMap = cfg.getConfig();

    String basePath = normalizeBasePath(getString(cfgMap, "gateway.controlUi.basePath", ""));
    String wsUrl = resolveWsUrl(hostHeader, cfgMap);

    Map<String, Object> bootstrap = new LinkedHashMap<>();
    bootstrap.put("basePath", basePath);
    bootstrap.put("gatewayUrl", wsUrl);
    bootstrap.put("assistantName", getString(cfgMap, "assistant.name", DEFAULT_ASSISTANT_NAME));
    bootstrap.put("assistantAvatar", getString(cfgMap, "assistant.avatar", ""));
    bootstrap.put("assistantAgentId", DEFAULT_AGENT_ID);
    bootstrap.put("serverVersion", "2026.3.14");

    return ResponseEntity.ok(bootstrap);
  }

  /**
   * Serves the Control UI root (redirect to /ui/).
   * Mirrors Node gateway: GET /ui → redirect to /ui/
   */
  @GetMapping("/ui")
  public ResponseEntity<Void> redirectUi() {
    return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
        .header("Location", "/ui/")
        .build();
  }

  /**
   * Serves static files from classpath:static/ with SPA fallback.
   * Mirrors Node gateway: GET /ui/*
   *
   * <p>For unknown paths under /ui/, falls back to serving index.html so that
   * client-side routing works (e.g., /ui/sessions, /ui/settings).
   */
  @GetMapping("/ui/**")
  public ResponseEntity<byte[]> serveUiStatic(
      HttpServletRequest request,
      @RequestParam(value = "raw", required = false, defaultValue = "false") boolean raw) {
    String fullPath = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    String pathWithinHandler = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
    
    String resourcePath;
    if (pathWithinHandler != null) {
      String base = "/ui";
      if (pathWithinHandler.equals(base) || pathWithinHandler.equals(base + "/")) {
        resourcePath = "index.html";
      } else {
        String after = pathWithinHandler.startsWith(base) ? pathWithinHandler.substring(base.length()) : pathWithinHandler;
        resourcePath = after.startsWith("/") ? after.substring(1) : after;
      }
    } else {
      resourcePath = "index.html";
    }

    try {
      Resource resource = new ClassPathResource("static/" + resourcePath);
      if (!resource.exists() || !resource.isReadable()) {
        // Fall back to index.html for SPA routing
        resource = new ClassPathResource("static/index.html");
        if (!resource.exists() || !resource.isReadable()) {
          return ResponseEntity.notFound().build();
        }
      }

      byte[] content = StreamUtils.copyToByteArray(resource.getInputStream());
      String filename = resourcePath.contains("/")
          ? resourcePath.substring(resourcePath.lastIndexOf('/') + 1)
          : resourcePath;
      MediaType mediaType = contentTypeForFile(filename);

      return ResponseEntity.ok()
          .contentType(mediaType)
          .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofSeconds(3600)))
          .header("X-Content-Type-Options", "nosniff")
          .header("X-Frame-Options", "DENY")
          .body(content);

    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private String normalizeBasePath(String basePath) {
    if (basePath == null) return "";
    String normalized = basePath.trim();
    if (normalized.isEmpty()) return "";
    if (!normalized.startsWith("/")) normalized = "/" + normalized;
    if (normalized.equals("/")) return "";
    if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    return normalized;
  }

  private String resolveWsUrl(String hostHeader, Map<String, Object> cfgMap) {
    String protocol = "http";
    String host = "localhost";
    int port = 18789;

    if (hostHeader != null) {
      int colonIdx = hostHeader.lastIndexOf(':');
      if (colonIdx > 0) {
        host = hostHeader.substring(0, colonIdx);
        try {
          port = Integer.parseInt(hostHeader.substring(colonIdx + 1));
        } catch (NumberFormatException ignored) {}
      } else {
        host = hostHeader;
      }
    }

    String base = port == 80 || port == 443
        ? protocol + "://" + host
        : protocol + "://" + host + ":" + port;
    return base.replace("http", "ws") + "/ws";
  }

  private String getString(Map<String, Object> map, String key, String defaultValue) {
    Object val = getNestedValue(map, key);
    return val != null ? val.toString().trim() : defaultValue;
  }

  private Object getNestedValue(Map<String, Object> map, String path) {
    String[] parts = path.split("\\.");
    Object current = map;
    for (String part : parts) {
      if (current instanceof Map) {
        current = ((Map<?, ?>) current).get(part);
      } else {
        return null;
      }
    }
    return current;
  }

  private String normalizeResourcePath(String path) {
    String p = path;
    if (p.startsWith("/")) p = p.substring(1);
    // Block path traversal
    while (p.contains("..")) p = p.replace("..", "");
    // Normalize multiple slashes
    while (p.contains("//")) p = p.replace("//", "/");
    return p;
  }

  private MediaType contentTypeForFile(String filename) {
    if (filename.endsWith(".html")) return MediaType.TEXT_HTML;
    if (filename.endsWith(".js")) return MediaType.valueOf("application/javascript");
    if (filename.endsWith(".css")) return MediaType.valueOf("text/css");
    if (filename.endsWith(".json")) return MediaType.APPLICATION_JSON;
    if (filename.endsWith(".svg")) return MediaType.valueOf("image/svg+xml");
    if (filename.endsWith(".png")) return MediaType.IMAGE_PNG;
    if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
    if (filename.endsWith(".gif")) return MediaType.IMAGE_GIF;
    if (filename.endsWith(".ico")) return MediaType.valueOf("image/x-icon");
    if (filename.endsWith(".txt")) return MediaType.TEXT_PLAIN;
    if (filename.endsWith(".map")) return MediaType.APPLICATION_JSON;
    return MediaType.APPLICATION_OCTET_STREAM;
  }
}
