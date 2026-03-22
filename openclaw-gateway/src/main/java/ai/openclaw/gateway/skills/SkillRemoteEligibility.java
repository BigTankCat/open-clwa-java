package ai.openclaw.gateway.skills;

import java.util.List;
import java.util.function.Predicate;

/**
 * Remote node bins/platforms for requirement evaluation. Java gateway has no live node registry
 * yet; default instance behaves like Node with no paired nodes (all-remote checks false).
 */
public record SkillRemoteEligibility(
    Predicate<String> hasBin,
    Predicate<List<String>> hasAnyBin,
    List<String> platforms) {

  public static SkillRemoteEligibility none() {
    return new SkillRemoteEligibility(b -> false, l -> false, List.of());
  }
}
