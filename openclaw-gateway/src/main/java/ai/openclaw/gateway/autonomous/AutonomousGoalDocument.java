package ai.openclaw.gateway.autonomous;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Persisted autonomous-goal document ({@code <id>.json} under {@code java-gateway/autonomous-goals/}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AutonomousGoalDocument {

  public String id;
  public String title;
  public String objective;
  public String acceptanceCriteria;
  /** {@link AutonomousGoalPhase} name */
  public String phase;
  /** Optional structured plan (steps, roles, tool policy hints). */
  public Map<String, Object> plan;
  /** Last evaluation / audit summary (machine-readable). */
  public Map<String, Object> lastEvaluation;
  /** Optional session key used for correlated chat rounds. */
  public String linkedSessionKey;
  public long createdAtMs;
  public long updatedAtMs;
}
