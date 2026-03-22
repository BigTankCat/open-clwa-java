package ai.openclaw.gateway.skills;

import java.util.List;
import java.util.Map;

/**
 * Ports {@code shouldIncludeSkill} / {@code evaluateRuntimeEligibility} from Node {@code
 * agents/skills/config.ts} and {@code shared/config-eval.ts}.
 */
public final class SkillRuntimeFilter {

  private SkillRuntimeFilter() {}

  public static boolean shouldInclude(
      SkillCatalogEntry entry,
      Map<String, Object> cfg,
      SkillRemoteEligibility remote) {
    Map<String, Object> skillCfg = SkillBundledSupport.skillConfigEntry(cfg, entry.skillKey());
    if (SkillBundledSupport.isExplicitlyDisabled(skillCfg)) {
      return false;
    }
    List<String> allow = SkillBundledSupport.allowBundledList(cfg);
    if (!SkillBundledSupport.isBundledAllowed(entry, allow)) {
      return false;
    }
    return evaluateRuntimeEligibility(entry, skillCfg, cfg, remote);
  }

  @SuppressWarnings("unchecked")
  static boolean evaluateRuntimeEligibility(
      SkillCatalogEntry entry,
      Map<String, Object> skillCfg,
      Map<String, Object> cfgRoot,
      SkillRemoteEligibility remote) {
    List<String> osList = SkillRequirementsEvaluator.stringList(entry.openclaw().get("os"));
    List<String> remotePlats = remote != null ? remote.platforms() : List.of();
    String platform = ConfigPathUtil.runtimePlatform();
    if (!osList.isEmpty()
        && !osList.contains(platform)
        && remotePlats.stream().noneMatch(osList::contains)) {
      return false;
    }
    if (Boolean.TRUE.equals(entry.openclaw().get("always"))) {
      return true;
    }
    Map<String, Object> requires =
        entry.openclaw().get("requires") instanceof Map<?, ?> rm
            ? (Map<String, Object>) rm
            : Map.of();

    List<String> bins = SkillRequirementsEvaluator.stringList(requires.get("bins"));
    for (String bin : bins) {
      if (!ProcessPathProbe.hasBinary(bin)) {
        if (remote == null || !remote.hasBin().test(bin)) {
          return false;
        }
      }
    }
    List<String> anyBins = SkillRequirementsEvaluator.stringList(requires.get("anyBins"));
    if (!anyBins.isEmpty()) {
      boolean any =
          anyBins.stream().anyMatch(ProcessPathProbe::hasBinary)
              || (remote != null && remote.hasAnyBin().test(anyBins));
      if (!any) {
        return false;
      }
    }
    List<String> envReq = SkillRequirementsEvaluator.stringList(requires.get("env"));
    String primaryEnv = stringOrNull(entry.openclaw().get("primaryEnv"));
    for (String envName : envReq) {
      if (!isEnvSatisfied(envName, skillCfg, primaryEnv)) {
        return false;
      }
    }
    List<String> configReq = SkillRequirementsEvaluator.stringList(requires.get("config"));
    for (String path : configReq) {
      if (!ConfigPathUtil.isConfigPathTruthy(cfgRoot, path)) {
        return false;
      }
    }
    return true;
  }

  static boolean isEnvSatisfied(String envName, Map<String, Object> skillCfg, String primaryEnv) {
    if (envName == null || envName.isBlank()) {
      return true;
    }
    String v = System.getenv(envName);
    if (v != null && !v.isBlank()) {
      return true;
    }
    if (skillCfg != null) {
      Object envMap = skillCfg.get("env");
      if (envMap instanceof Map<?, ?> em) {
        Object ev = em.get(envName);
        if (ev instanceof String es && !es.isBlank()) {
          return true;
        }
      }
      Object apiKey = skillCfg.get("apiKey");
      if (apiKey instanceof String aks
          && !aks.isBlank()
          && envName.equals(primaryEnv)) {
        return true;
      }
    }
    return false;
  }

  static String stringOrNull(Object v) {
    if (v instanceof String s) {
      String t = s.trim();
      return t.isEmpty() ? null : t;
    }
    return null;
  }
}
