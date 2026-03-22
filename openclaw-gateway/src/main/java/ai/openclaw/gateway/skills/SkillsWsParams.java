package ai.openclaw.gateway.skills;

import java.util.Map;

/** Validates WebSocket params for skills.* methods (Node additionalProperties: false). */
public final class SkillsWsParams {

  private SkillsWsParams() {}

  public static String validateStatus(Map<String, Object> params) {
    if (params == null || params.isEmpty()) {
      return null;
    }
    for (String k : params.keySet()) {
      if (!"agentId".equals(k)) {
        return "invalid skills.status params: unknown key " + k;
      }
    }
    Object agentId = params.get("agentId");
    if (agentId != null && !(agentId instanceof String)) {
      return "invalid skills.status params: agentId must be a string";
    }
    return null;
  }

  public static String validateBins(Map<String, Object> params) {
    if (params == null || params.isEmpty()) {
      return null;
    }
    return "invalid skills.bins params: must be an empty object";
  }

  public static String validateInstall(Map<String, Object> params) {
    if (params == null) {
      return "invalid skills.install params: missing name";
    }
    for (String k : params.keySet()) {
      if (!"name".equals(k) && !"installId".equals(k) && !"timeoutMs".equals(k)) {
        return "invalid skills.install params: unknown key " + k;
      }
    }
    if (!(params.get("name") instanceof String) || ((String) params.get("name")).isBlank()) {
      return "invalid skills.install params: name required";
    }
    if (!(params.get("installId") instanceof String) || ((String) params.get("installId")).isBlank()) {
      return "invalid skills.install params: installId required";
    }
    Object t = params.get("timeoutMs");
    if (t != null) {
      long v;
      if (t instanceof Integer i) {
        v = i.longValue();
      } else if (t instanceof Long l) {
        v = l;
      } else {
        return "invalid skills.install params: timeoutMs must be an integer";
      }
      if (v < 1000) {
        return "invalid skills.install params: timeoutMs must be >= 1000";
      }
    }
    return null;
  }

  public static String validateUpdate(Map<String, Object> params) {
    if (params == null) {
      return "invalid skills.update params: missing skillKey";
    }
    for (String k : params.keySet()) {
      if (!"skillKey".equals(k)
          && !"enabled".equals(k)
          && !"apiKey".equals(k)
          && !"env".equals(k)) {
        return "invalid skills.update params: unknown key " + k;
      }
    }
    if (!(params.get("skillKey") instanceof String) || ((String) params.get("skillKey")).isBlank()) {
      return "invalid skills.update params: skillKey required";
    }
    Object env = params.get("env");
    if (env != null && !(env instanceof Map<?, ?>)) {
      return "invalid skills.update params: env must be an object";
    }
    if (env instanceof Map<?, ?> em) {
      for (Map.Entry<?, ?> e : em.entrySet()) {
        if (!(e.getKey() instanceof String) || e.getKey().toString().isBlank()) {
          return "invalid skills.update params: env keys must be non-empty strings";
        }
        if (e.getValue() != null && !(e.getValue() instanceof String)) {
          return "invalid skills.update params: env values must be strings";
        }
      }
    }
    Object en = params.get("enabled");
    if (en != null && !(en instanceof Boolean)) {
      return "invalid skills.update params: enabled must be boolean";
    }
    Object ak = params.get("apiKey");
    if (ak != null && !(ak instanceof String)) {
      return "invalid skills.update params: apiKey must be a string";
    }
    return null;
  }
}
