package ai.openclaw.gateway.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads {@code gateway.*} slices from the OpenClaw config map. */
public final class GatewaySettingsReader {

  private GatewaySettingsReader() {}

  @SuppressWarnings("unchecked")
  public static List<String> trustedProxies(Map<String, Object> cfg) {
    if (cfg == null) {
      return List.of();
    }
    Object gObj = cfg.get("gateway");
    if (!(gObj instanceof Map<?, ?> g)) {
      return List.of();
    }
    Object tp = g.get("trustedProxies");
    if (!(tp instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      if (o instanceof String s && !s.isBlank()) {
        out.add(s.trim());
      }
    }
    return List.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  public static boolean allowRealIpFallback(Map<String, Object> cfg) {
    if (cfg == null) {
      return false;
    }
    Object gObj = cfg.get("gateway");
    if (!(gObj instanceof Map<?, ?> g)) {
      return false;
    }
    Object v = g.get("allowRealIpFallback");
    return Boolean.TRUE.equals(v);
  }
}
