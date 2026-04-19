package ai.openclaw.gateway.skills;

import java.util.Locale;
import java.util.Map;

/** Dot-path reads and truthy checks; aligned with Node {@code shared/config-eval.ts}. */
public final class ConfigPathUtil {

  private static final Map<String, Boolean> DEFAULT_TRUTHY =
      Map.of(
          "browser.enabled", true,
          "browser.evaluateEnabled", true);

  private ConfigPathUtil() {}

  public static Object resolvePath(Object config, String pathStr) {
    if (pathStr == null || pathStr.isBlank() || config == null) {
      return null;
    }
    String[] parts = pathStr.split("\\.");
    Object cur = config;
    for (String part : parts) {
      if (part.isEmpty()) {
        continue;
      }
      if (!(cur instanceof Map<?, ?> m)) {
        return null;
      }
      cur = m.get(part);
    }
    return cur;
  }

  public static boolean isTruthy(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Number n) {
      return n.doubleValue() != 0;
    }
    if (value instanceof String s) {
      return !s.trim().isEmpty();
    }
    return true;
  }

  public static boolean isConfigPathTruthy(Object configRoot, String pathStr) {
    Object v = resolvePath(configRoot, pathStr);
    if (v == null && DEFAULT_TRUTHY.containsKey(pathStr)) {
      return Boolean.TRUE.equals(DEFAULT_TRUTHY.get(pathStr));
    }
    return isTruthy(v);
  }

  private static Object undefinedLike(Object v) {
    return v == null ? null : v;
  }

  public static String normalizeString(Object v) {
    if (v instanceof String s) {
      return s.trim();
    }
    return "";
  }

  /** Node {@code process.platform}: {@code darwin}, {@code linux}, {@code win32}. */
  public static String runtimePlatform() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("win")) {
      return "win32";
    }
    if (os.contains("mac")) {
      return "darwin";
    }
    return "linux";
  }
}
