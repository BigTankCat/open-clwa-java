package ai.openclaw.gateway.skills;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillRequirementsEvaluatorTest {

  @Test
  void eligibleWhenAlways() {
    Map<String, Object> meta =
        Map.of(
            "requires",
            Map.of("bins", List.of("nonexistent-bin-xyz"), "env", List.of("MISSING_ENV_VAR")));
    SkillRequirementsEvaluator.EvaluationResult r =
        SkillRequirementsEvaluator.evaluate(
            true,
            meta,
            "linux",
            ProcessPathProbe::hasBinary,
            SkillRemoteEligibility.none(),
            n -> false,
            p -> false);
    assertTrue(r.eligible());
    assertTrue(r.missing().bins().isEmpty());
  }

  @Test
  void missingBinWhenNotAlways() {
    Map<String, Object> meta = Map.of("requires", Map.of("bins", List.of("nonexistent-bin-xyz")));
    SkillRequirementsEvaluator.EvaluationResult r =
        SkillRequirementsEvaluator.evaluate(
            false,
            meta,
            "linux",
            ProcessPathProbe::hasBinary,
            SkillRemoteEligibility.none(),
            n -> true,
            p -> true);
    assertFalse(r.eligible());
    assertFalse(r.missing().bins().isEmpty());
  }
}
