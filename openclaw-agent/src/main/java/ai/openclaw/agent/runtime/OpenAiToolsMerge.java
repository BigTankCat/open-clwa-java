package ai.openclaw.agent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges built-in registry tools with user-supplied {@code tools} from {@code llm.config.set}.
 *
 * <p>Policy: user entries <strong>override</strong> registry tools with the same function name;
 * overridden names are reported for trace.
 */
public final class OpenAiToolsMerge {

  public record MergeResult(List<Map<String, Object>> tools, List<String> overriddenNames) {}

  @SuppressWarnings("unchecked")
  public static MergeResult mergeWithReport(
      List<Map<String, Object>> registryTools, Object userTools) {
    Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
    if (registryTools != null) {
      for (Map<String, Object> t : registryTools) {
        String n = functionName(t);
        if (n != null && !n.isBlank()) {
          byName.put(n, copyStringKeyed(t));
        }
      }
    }
    List<String> overridden = new ArrayList<>();
    if (userTools instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof Map<?, ?> raw) {
          Map<String, Object> m = copyStringKeyed(raw);
          String n = functionName(m);
          if (n != null && !n.isBlank()) {
            if (byName.containsKey(n)) {
              overridden.add(n);
            }
            byName.put(n, m);
          }
        }
      }
    }
    return new MergeResult(List.copyOf(byName.values()), List.copyOf(overridden));
  }

  static String functionName(Map<String, Object> tool) {
    Object fn = tool.get("function");
    if (fn instanceof Map<?, ?> fm) {
      Object name = fm.get("name");
      return name instanceof String s ? s : null;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> copyStringKeyed(Map<?, ?> raw) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : raw.entrySet()) {
      if (e.getKey() instanceof String k) {
        m.put(k, e.getValue());
      }
    }
    return m;
  }
}
