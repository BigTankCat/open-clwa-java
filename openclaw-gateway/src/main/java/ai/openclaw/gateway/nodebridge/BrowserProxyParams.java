package ai.openclaw.gateway.nodebridge;

import ai.openclaw.protocol.ErrorCodes;
import ai.openclaw.protocol.ErrorShape;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses and validates {@code browser.request} params to match Node {@code browser.ts} rules before
 * building the {@link NodeCapabilityCommands#BROWSER_PROXY} payload.
 */
public final class BrowserProxyParams {

  private BrowserProxyParams() {}

  public sealed interface ParseResult permits Parsed, ParseError {
    record Parsed(
        String method,
        String path,
        Map<String, Object> query,
        Object body,
        Long timeoutMs,
        String profile)
        implements ParseResult {}

    record ParseError(ErrorShape error) implements ParseResult {}
  }

  public static ParseResult parse(Map<String, Object> params) {
    String methodRaw =
        params.get("method") instanceof String s ? s.trim().toUpperCase() : "";
    String path = params.get("path") instanceof String s ? s.trim() : "";
    if (methodRaw.isEmpty() || path.isEmpty()) {
      return new ParseResult.ParseError(
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "method and path are required"));
    }
    if (!methodRaw.equals("GET") && !methodRaw.equals("POST") && !methodRaw.equals("DELETE")) {
      return new ParseResult.ParseError(
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "method must be GET, POST, or DELETE"));
    }
    if (isPersistentBrowserProfileMutation(methodRaw, path)) {
      return new ParseResult.ParseError(
          ErrorShape.of(
              ErrorCodes.INVALID_REQUEST,
              "browser.request cannot create or delete persistent browser profiles"));
    }

    Map<String, Object> query = null;
    Object q = params.get("query");
    if (q instanceof Map<?, ?> m) {
      query = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (e.getKey() instanceof String k) {
          query.put(k, e.getValue());
        }
      }
    }
    Object body = params.get("body");
    Long timeoutMs = null;
    Object t = params.get("timeoutMs");
    if (t instanceof Number n) {
      double v = n.doubleValue();
      if (Double.isFinite(v) && v >= 1) {
        timeoutMs = Math.max(1L, (long) Math.floor(v));
      }
    }

    String profile = resolveRequestedProfile(query, body);
    return new ParseResult.Parsed(methodRaw, path, query, body, timeoutMs, profile);
  }

  static String normalizeBrowserRequestPath(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return trimmed;
    }
    String withLeading = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    if (withLeading.length() <= 1) {
      return withLeading;
    }
    return withLeading.replaceAll("/+$", "");
  }

  static boolean isPersistentBrowserProfileMutation(String method, String path) {
    String normalizedPath = normalizeBrowserRequestPath(path);
    if ("POST".equals(method) && "/profiles/create".equals(normalizedPath)) {
      return true;
    }
    return "DELETE".equals(method) && normalizedPath.matches("^/profiles/[^/]+$");
  }

  static String resolveRequestedProfile(Map<String, Object> query, Object body) {
    if (query != null) {
      Object qp = query.get("profile");
      if (qp instanceof String s) {
        String t = s.trim();
        if (!t.isEmpty()) {
          return t;
        }
      }
    }
    if (body instanceof Map<?, ?> bm) {
      Object bp = bm.get("profile");
      if (bp instanceof String s) {
        String t = s.trim();
        if (!t.isEmpty()) {
          return t;
        }
      }
    }
    return null;
  }

  /** Builds the JSON object passed as {@code browser.proxy} params on the Node side. */
  public static Map<String, Object> toProxyCommandParams(ParseResult.Parsed p) {
    Map<String, Object> proxy = new LinkedHashMap<>();
    proxy.put("method", p.method());
    proxy.put("path", p.path());
    if (p.query() != null && !p.query().isEmpty()) {
      proxy.put("query", p.query());
    }
    if (p.body() != null) {
      proxy.put("body", p.body());
    }
    if (p.timeoutMs() != null) {
      proxy.put("timeoutMs", p.timeoutMs());
    }
    if (p.profile() != null) {
      proxy.put("profile", p.profile());
    }
    return proxy;
  }
}
