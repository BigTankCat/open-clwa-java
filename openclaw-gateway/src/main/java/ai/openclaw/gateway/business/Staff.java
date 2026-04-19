package ai.openclaw.gateway.business;

/** Staff entity: PM / Dev / QA roles with role-specific prompts. */
public record Staff(
    int id,
    String name,
    String role,     // "pm" | "dev" | "qa"
    String prompt,
    long createdAt,
    long updatedAt) {

  public static final String ROLE_PM = "pm";
  public static final String ROLE_DEV = "dev";
  public static final String ROLE_QA = "qa";

  /** Default system prompt for each role. */
  public static String defaultPrompt(String role) {
    return switch (role) {
      case ROLE_PM -> """
          You are a project manager responsible for requirements analysis, task breakdown, and progress tracking.
          You coordinate team members, set priorities, and ensure project goals are met.
          When assigned a task, analyze it carefully, break it down into actionable items, and delegate appropriately.
          """;
      case ROLE_DEV -> """
          You are a software engineer responsible for technical implementation and code quality.
          Available tools: file (read/write files), bash (execute shell commands).
          Write clean, maintainable code. When given a task, implement it fully and verify your work.
          """;
      case ROLE_QA -> """
          You are a QA engineer responsible for test strategy, test case design, and quality assurance.
          Available tools: file (read files), bash (execute commands for testing).
          Design thorough test cases covering happy path and edge cases. Verify fixes thoroughly.
          """;
      default -> "";
    };
  }

  /** Returns the effective prompt: stored custom prompt or role default. */
  public String effectivePrompt() {
    return (prompt == null || prompt.isBlank()) ? defaultPrompt(role) : prompt;
  }

  public boolean isValid() {
    return name != null && !name.isBlank()
        && role != null
        && (role.equals(ROLE_PM) || role.equals(ROLE_DEV) || role.equals(ROLE_QA));
  }
}
