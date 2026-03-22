package ai.openclaw.gateway.autonomous;

/**
 * High-level lifecycle for a persisted autonomous goal. Stored as string on disk for forward
 * compatibility; orchestration logic (prompts, tools) maps phases to behavior.
 */
public enum AutonomousGoalPhase {
  /** Goal captured; not yet planned. */
  INTAKE,
  /** Decomposing work / plan draft. */
  PLANNING,
  /** Active execution (LLM rounds, tools, CLI). */
  EXECUTING,
  /** Automated or model-assisted checks against acceptance criteria. */
  EVALUATING,
  /** Human or stricter model review gate. */
  REVIEWING,
  /** Apply fixes and re-run execution/evaluation. */
  ITERATING,
  DONE,
  FAILED;

  public static AutonomousGoalPhase parseOrDefault(String raw, AutonomousGoalPhase fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return AutonomousGoalPhase.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }
}
