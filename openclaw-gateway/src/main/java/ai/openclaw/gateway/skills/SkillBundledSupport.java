package ai.openclaw.gateway.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bundled allowlist + skill config entries; aligned with Node {@code agents/skills/config.ts}. */
public final class SkillBundledSupport {

  private static final String BUNDLED_SOURCE = "openclaw-bundled";

  private SkillBundledSupport() {}

  public static boolean isBundledSource(String source) {
    return BUNDLED_SOURCE.equals(source);
  }

  @SuppressWarnings("unchecked")
  public static List<String> allowBundledList(Map<String, Object> cfg) {
    if (cfg == null) {
      return null;
    }
    Object skills = cfg.get("skills");
    if (!(skills instanceof Map<?, ?> sm)) {
      return null;
    }
    Object raw = sm.get("allowBundled");
    if (!(raw instanceof List<?> list)) {
      return null;
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      if (o instanceof String s && !s.isBlank()) {
        out.add(s.trim());
      }
    }
    return out.isEmpty() ? null : List.copyOf(out);
  }

  public static boolean isBundledAllowed(SkillCatalogEntry entry, List<String> allowlist) {
    if (allowlist == null || allowlist.isEmpty()) {
      return true;
    }
    if (!isBundledSource(entry.source())) {
      return true;
    }
    String key = entry.skillKey();
    String name = entry.name();
    return allowlist.contains(key) || allowlist.contains(name);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> skillConfigEntry(Map<String, Object> cfg, String skillKey) {
    if (cfg == null || skillKey == null) {
      return null;
    }
    Object skills = cfg.get("skills");
    if (!(skills instanceof Map<?, ?> sm)) {
      return null;
    }
    Object entries = sm.get("entries");
    if (!(entries instanceof Map<?, ?> em)) {
      return null;
    }
    Object row = em.get(skillKey);
    return row instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }

  public static boolean isExplicitlyDisabled(Map<String, Object> skillEntry) {
    if (skillEntry == null) {
      return false;
    }
    return Boolean.FALSE.equals(skillEntry.get("enabled"));
  }
}
