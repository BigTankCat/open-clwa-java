package ai.openclaw.gateway.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds Node-shaped payloads for {@code models.list} / {@code agents.list} from config maps. */
public final class ConfigRpcSupport {

  private ConfigRpcSupport() {}

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> buildModelsList(Map<String, Object> cfg) {
    if (cfg == null) {
      return List.of();
    }
    Object modelsObj = cfg.get("models");
    if (!(modelsObj instanceof Map<?, ?>)) {
      return List.of();
    }
    Map<String, Object> models = (Map<String, Object>) modelsObj;
    Object providersObj = models.get("providers");
    if (!(providersObj instanceof Map<?, ?>)) {
      return List.of();
    }
    Map<String, Object> providers = (Map<String, Object>) providersObj;
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map.Entry<String, Object> pe : providers.entrySet()) {
      String providerKey = pe.getKey();
      if (!(pe.getValue() instanceof Map<?, ?>)) {
        continue;
      }
      Map<String, Object> prov = (Map<String, Object>) pe.getValue();
      Object modelsArr = prov.get("models");
      if (!(modelsArr instanceof List<?> list)) {
        continue;
      }
      for (Object m : list) {
        if (!(m instanceof Map<?, ?>)) {
          continue;
        }
        Map<String, Object> mm = (Map<String, Object>) m;
        String id = stringVal(mm.get("id"));
        if (id == null || id.isBlank()) {
          continue;
        }
        String fullId = providerKey + "/" + id;
        String name = stringVal(mm.get("name"));
        if (name == null || name.isBlank()) {
          name = id;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", fullId);
        row.put("name", name);
        row.put("provider", providerKey);
        Long cw = longVal(mm.get("contextWindow"));
        if (cw != null && cw > 0) {
          row.put("contextWindow", cw.intValue());
        }
        out.add(row);
      }
    }
    out.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));
    return List.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> buildAgentsListPayload(Map<String, Object> cfg) {
    String defaultId = "default";
    String mainKey = "main";
    String scope = "per-sender";
    if (cfg != null) {
      Object sessionObj = cfg.get("session");
      if (sessionObj instanceof Map<?, ?> sm) {
        String mk = stringVal(sm.get("mainKey"));
        if (mk != null && !mk.isBlank()) {
          mainKey = mk.trim();
        }
        String sc = stringVal(sm.get("scope"));
        if (sc != null && !sc.isBlank()) {
          scope = sc.trim();
        }
      }
    }
    List<Map<String, Object>> agents = new ArrayList<>();
    if (cfg != null) {
      Object agentsRoot = cfg.get("agents");
      if (agentsRoot instanceof Map<?, ?> ar) {
        Object listObj = ar.get("list");
        if (listObj instanceof List<?> list) {
          for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> em)) {
              continue;
            }
            String id = stringVal(em.get("id"));
            if (id == null || id.isBlank()) {
              continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id.trim());
            String name = stringVal(em.get("name"));
            if (name != null && !name.isBlank()) {
              row.put("name", name.trim());
            }
            Object def = em.get("default");
            if (Boolean.TRUE.equals(def)) {
              defaultId = id.trim();
            }
            Object identity = em.get("identity");
            if (identity instanceof Map<?, ?> idMap) {
              Map<String, Object> idOut = new LinkedHashMap<>();
              copyIfString(idOut, "name", idMap.get("name"));
              copyIfString(idOut, "theme", idMap.get("theme"));
              copyIfString(idOut, "emoji", idMap.get("emoji"));
              copyIfString(idOut, "avatar", idMap.get("avatar"));
              if (!idOut.isEmpty()) {
                row.put("identity", idOut);
              }
            }
            agents.add(row);
          }
        }
      }
    }
    if (agents.isEmpty()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", "default");
      row.put("name", "Default");
      agents.add(row);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("defaultId", defaultId);
    payload.put("mainKey", mainKey);
    payload.put("scope", scope);
    payload.put("agents", agents);
    return payload;
  }

  private static void copyIfString(Map<String, Object> out, String key, Object v) {
    if (v instanceof String s && !s.isBlank()) {
      out.put(key, s.trim());
    }
  }

  private static String stringVal(Object v) {
    return v instanceof String s ? s : null;
  }

  private static Long longVal(Object v) {
    if (v instanceof Number n) {
      return n.longValue();
    }
    if (v instanceof String s) {
      try {
        return Long.parseLong(s.trim());
      } catch (Exception ignored) {
        return null;
      }
    }
    return null;
  }

  /** Node {@code models.list} / {@code agents.list} allow only {@code null} or an empty object. */
  public static boolean isEmptyParamsOnly(Map<String, Object> params) {
    return params == null || params.isEmpty();
  }
}
