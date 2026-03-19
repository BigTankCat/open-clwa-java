package ai.openclaw.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON merge patch for plain objects.
 *
 * <p>Notes:
 * <ul>
 *   <li>Null value in patch removes the key from the base map (RFC 7386 style).</li>
 *   <li>Arrays are replaced wholesale (Node's mergeObjectArraysById is a later slice).</li>
 * </ul>
 */
public final class ConfigMergePatch {

  private ConfigMergePatch() {}

  // Prototype pollution guardrail parity with Node src/infra/prototype-keys.ts
  private static final java.util.Set<String> BLOCKED_OBJECT_KEYS =
      java.util.Set.of("__proto__", "prototype", "constructor");

  @SuppressWarnings("unchecked")
  public static Map<String, Object> merge(
      Map<String, Object> base, Map<String, Object> patch) {
    Map<String, Object> out = base == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base);
    if (patch == null) return out;
    for (Map.Entry<String, Object> entry : patch.entrySet()) {
      String key = entry.getKey();
      if (BLOCKED_OBJECT_KEYS.contains(key)) {
        continue;
      }
      Object pv = entry.getValue();
      if (pv == null) {
        out.remove(key);
        continue;
      }
      Object bv = out.get(key);
      if (bv instanceof Map && pv instanceof Map) {
        out.put((String) key, merge((Map<String, Object>) bv, (Map<String, Object>) pv));
        continue;
      }
      // Replace primitives/arrays/objects when types don't match.
      out.put(key, pv);
    }
    return out;
  }
}

