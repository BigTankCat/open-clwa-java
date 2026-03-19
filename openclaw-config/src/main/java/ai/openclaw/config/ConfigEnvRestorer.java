package ai.openclaw.config;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Restores pre-substitution `${VAR}` environment references during write-back.
 *
 * <p>Port of Node {@code src/config/env-preserve.ts}.
 *
 * <p>Given:
 * - incoming: the resolved config we are about to write
 * - parsed: the pre-substitution parsed config from the current file
 * - env: env vars used for substitution (process env + optional config.env overrides)
 *
 * <p>For each leaf string where {@code parsed} contains a `${VAR}` template, we attempt
 * to resolve that template with the given env. If the resolved value equals the incoming
 * string, we restore the original template string to preserve round-trips.
 */
public final class ConfigEnvRestorer {

  private static final Pattern ENV_VAR_PATTERN =
      Pattern.compile("\\$\\{[A-Z_][A-Z0-9_]*\\}");

  private static final Pattern ENV_VAR_NAME_PATTERN =
      Pattern.compile("^[A-Z_][A-Z0-9_]*$");

  private ConfigEnvRestorer() {}

  public static Object restoreEnvVarRefs(
      Object incoming, Object parsed, Map<String, String> env) {
    if (parsed == null) return incoming;

    if (incoming instanceof String incomingStr && parsed instanceof String parsedStr) {
      if (hasEnvVarRef(parsedStr)) {
        String resolved = tryResolveString(parsedStr, env);
        if (resolved != null && Objects.equals(resolved, incomingStr)) {
          return parsedStr;
        }
      }
      return incoming;
    }

    if (incoming instanceof java.util.List<?> incomingList && parsed instanceof java.util.List<?> parsedList) {
      java.util.List<Object> out = new ArrayList<>(incomingList.size());
      int i = 0;
      for (Object item : incomingList) {
        if (i < parsedList.size()) {
          out.add(restoreEnvVarRefs(item, parsedList.get(i), env));
        } else {
          out.add(item);
        }
        i++;
      }
      return out;
    }

    if (incoming instanceof Map<?, ?> incomingMap && parsed instanceof Map<?, ?> parsedMap) {
      // Only restore keys that exist in parsed.
      java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
      for (Map.Entry<?, ?> e : incomingMap.entrySet()) {
        Object kObj = e.getKey();
        if (!(kObj instanceof String k)) continue;
        Object v = e.getValue();
        if (parsedMap.containsKey(k)) {
          out.put(k, restoreEnvVarRefs(v, parsedMap.get(k), env));
        } else {
          out.put(k, v);
        }
      }
      return out;
    }

    // Mismatched types / primitives: keep incoming.
    return incoming;
  }

  private static boolean hasEnvVarRef(String value) {
    return ENV_VAR_PATTERN.matcher(value).find();
  }

  private static String tryResolveString(String template, Map<String, String> env) {
    // Mirrors ConfigEnvSubstitutor's string substitution semantics.
    StringBuilder out = new StringBuilder(template.length());

    for (int i = 0; i < template.length(); i++) {
      if (template.charAt(i) != '$') {
        out.append(template.charAt(i));
        continue;
      }

      // Escaped: $${VAR} -> literal ${VAR}
      if (i + 2 < template.length() && template.charAt(i + 1) == '$' && template.charAt(i + 2) == '{') {
        int start = i + 3;
        int end = template.indexOf("}", start);
        if (end != -1) {
          String name = template.substring(start, end);
          if (ENV_VAR_NAME_PATTERN.matcher(name).matches()) {
            out.append("${").append(name).append("}");
            i = end;
            continue;
          }
        }
      }

      // Substitution: ${VAR} -> env value (returns null if missing/empty)
      if (i + 1 < template.length() && template.charAt(i + 1) == '{') {
        int start = i + 2;
        int end = template.indexOf("}", start);
        if (end != -1) {
          String name = template.substring(start, end);
          if (ENV_VAR_NAME_PATTERN.matcher(name).matches()) {
            String val = env.get(name);
            if (val == null || val.isEmpty()) {
              return null;
            }
            out.append(val);
            i = end;
            continue;
          }
        }
      }

      // Not recognized: keep '$'
      out.append('$');
    }

    return out.toString();
  }
}

