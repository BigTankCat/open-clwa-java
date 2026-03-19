package ai.openclaw.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Environment variable substitution for config values.
 *
 * <p>Supports `${VAR}` and escape with `$${VAR}` -> `${VAR}`.
 *
 * <p>Only uppercase env var names matching {@code [A-Z_][A-Z0-9_]*} are substituted.
 * Missing or empty env vars are preserved as placeholders and reported via `onMissing`.
 */
public final class ConfigEnvSubstitutor {

  private static final String ESCAPE_PREFIX = "$$";
  private static final java.util.regex.Pattern VAR_NAME_PATTERN =
      java.util.regex.Pattern.compile("^[A-Z_][A-Z0-9_]*$");

  // Prototype pollution guardrail parity with Node src/infra/prototype-keys.ts
  private static final java.util.Set<String> BLOCKED_OBJECT_KEYS =
      java.util.Set.of("__proto__", "prototype", "constructor");

  private ConfigEnvSubstitutor() {}

  public static List<String> substituteConfigEnvVars(Object obj, Map<String, String> env) {
    List<String> warnings = new ArrayList<>();
    substituteConfigEnvVars(obj, env, w -> warnings.add(w));
    return warnings;
  }

  public static Object substituteConfigEnvVars(
      Object obj, Map<String, String> env, Consumer<String> onMissing) {
    return substituteAny(obj, env, "", onMissing);
  }

  @SuppressWarnings("unchecked")
  private static Object substituteAny(
      Object value, Map<String, String> env, String path, Consumer<String> onMissing) {
    if (value instanceof String s) {
      return substituteString(s, env, path, onMissing);
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (int i = 0; i < list.size(); i++) {
        out.add(substituteAny(list.get(i), env, path + "[" + i + "]", onMissing));
      }
      return out;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new HashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!(e.getKey() instanceof String k)) continue;
        if (BLOCKED_OBJECT_KEYS.contains(k)) {
          continue;
        }
        Object child = e.getValue();
        String childPath = path.isEmpty() ? k : path + "." + k;
        out.put(k, substituteAny(child, env, childPath, onMissing));
      }
      return out;
    }
    return value;
  }

  private static Object substituteString(String value, Map<String, String> env, String path, Consumer<String> onMissing) {
    if (value == null) return null;
    if (!value.contains("$")) {
      return value;
    }

    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch != '$') {
        out.append(ch);
        continue;
      }

      // Try escape token: $${VAR}
      if (i + 2 < value.length() && value.charAt(i + 1) == '$' && value.charAt(i + 2) == '{') {
        int start = i + 3;
        int end = value.indexOf('}', start);
        if (end != -1) {
          String name = value.substring(start, end);
          if (VAR_NAME_PATTERN.matcher(name).matches()) {
            out.append("${").append(name).append("}");
            i = end;
            continue;
          }
        }
      }

      // Try substitution token: ${VAR}
      if (i + 1 < value.length() && value.charAt(i + 1) == '{') {
        int start = i + 2;
        int end = value.indexOf('}', start);
        if (end != -1) {
          String name = value.substring(start, end);
          if (VAR_NAME_PATTERN.matcher(name).matches()) {
            String envValue = env.get(name);
            if (envValue == null || envValue.isEmpty()) {
              onMissing.accept("Config (" + path + "): missing env var \"" + name + "\"");
              out.append("${").append(name).append("}");
            } else {
              out.append(envValue);
            }
            i = end;
            continue;
          }
        }
      }

      // Not a recognized token, keep '$'
      out.append('$');
    }
    return out.toString();
  }
}

