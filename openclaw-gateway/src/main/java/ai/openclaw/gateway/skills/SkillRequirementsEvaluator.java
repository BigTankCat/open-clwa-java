package ai.openclaw.gateway.skills;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Ports {@code evaluateRequirementsFromMetadataWithRemote} from Node {@code shared/requirements.ts}.
 */
public final class SkillRequirementsEvaluator {

  public record Requirements(
      List<String> bins,
      List<String> anyBins,
      List<String> env,
      List<String> config,
      List<String> os) {}

  public record RequirementConfigCheck(String path, boolean satisfied) {}

  public record EvaluationResult(
      Requirements required,
      Requirements missing,
      boolean eligible,
      List<RequirementConfigCheck> configChecks) {}

  private SkillRequirementsEvaluator() {}

  @SuppressWarnings("unchecked")
  public static EvaluationResult evaluate(
      boolean always,
      Map<String, Object> openclawMetadata,
      String localPlatform,
      Predicate<String> hasLocalBin,
      SkillRemoteEligibility remote,
      Predicate<String> isEnvSatisfied,
      Predicate<String> isConfigPathSatisfied) {
    Map<String, Object> requires =
        openclawMetadata != null && openclawMetadata.get("requires") instanceof Map<?, ?> rm
            ? (Map<String, Object>) rm
            : Map.of();

    Requirements required =
        new Requirements(
            stringList(requires.get("bins")),
            stringList(requires.get("anyBins")),
            stringList(requires.get("env")),
            stringList(requires.get("config")),
            stringList(openclawMetadata != null ? openclawMetadata.get("os") : null));

    Predicate<String> hasRemoteBin = remote != null ? remote.hasBin() : b -> false;
    Predicate<List<String>> hasRemoteAny =
        remote != null ? remote.hasAnyBin() : l -> false;
    List<String> remotePlats = remote != null ? remote.platforms() : List.of();

    List<String> missingBins = new ArrayList<>();
    for (String bin : required.bins()) {
      if (!hasLocalBin.test(bin) && !hasRemoteBin.test(bin)) {
        missingBins.add(bin);
      }
    }

    List<String> missingAnyBins = List.of();
    if (!required.anyBins().isEmpty()) {
      boolean anyLocal = required.anyBins().stream().anyMatch(hasLocalBin);
      boolean anyRemote = hasRemoteAny.test(required.anyBins());
      if (!anyLocal && !anyRemote) {
        missingAnyBins = new ArrayList<>(required.anyBins());
      }
    }

    List<String> missingOs = List.of();
    if (!required.os().isEmpty()) {
      boolean okLocal = required.os().contains(localPlatform);
      boolean okRemote =
          remotePlats.stream().anyMatch(p -> required.os().contains(p));
      if (!okLocal && !okRemote) {
        missingOs = new ArrayList<>(required.os());
      }
    }

    List<String> missingEnv = new ArrayList<>();
    for (String env : required.env()) {
      if (!isEnvSatisfied.test(env)) {
        missingEnv.add(env);
      }
    }

    List<RequirementConfigCheck> configChecks = new ArrayList<>();
    List<String> missingConfig = new ArrayList<>();
    for (String path : required.config()) {
      boolean sat = isConfigPathSatisfied.test(path);
      configChecks.add(new RequirementConfigCheck(path, sat));
      if (!sat) {
        missingConfig.add(path);
      }
    }

    Requirements missing;
    if (always) {
      missing =
          new Requirements(List.of(), List.of(), List.of(), List.of(), List.of());
    } else {
      missing =
          new Requirements(missingBins, missingAnyBins, missingEnv, missingConfig, missingOs);
    }

    boolean eligible =
        always
            || (missing.bins().isEmpty()
                && missing.anyBins().isEmpty()
                && missing.env().isEmpty()
                && missing.config().isEmpty()
                && missing.os().isEmpty());

    return new EvaluationResult(required, missing, eligible, List.copyOf(configChecks));
  }

  @SuppressWarnings("unchecked")
  public static List<String> stringList(Object v) {
    if (!(v instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      if (o == null) {
        continue;
      }
      String s = String.valueOf(o).trim();
      if (!s.isEmpty()) {
        out.add(s);
      }
    }
    return List.copyOf(out);
  }

  /** Shape {@code requirements} / {@code missing} as nested maps for JSON. */
  public static Map<String, Object> requirementsToMap(Requirements r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("bins", new ArrayList<>(r.bins()));
    m.put("anyBins", new ArrayList<>(r.anyBins()));
    m.put("env", new ArrayList<>(r.env()));
    m.put("config", new ArrayList<>(r.config()));
    m.put("os", new ArrayList<>(r.os()));
    return m;
  }

  public static List<Map<String, Object>> configChecksToMaps(List<RequirementConfigCheck> checks) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (RequirementConfigCheck c : checks) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("path", c.path());
      row.put("satisfied", c.satisfied());
      out.add(row);
    }
    return out;
  }
}
