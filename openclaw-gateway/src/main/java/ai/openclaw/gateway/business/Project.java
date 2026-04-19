package ai.openclaw.gateway.business;

import java.util.List;

/** Project entity with associated staff IDs. */
public record Project(
    int id,
    String name,
    String goal,
    String workDir,
    String status,    // "active" | "completed" | "archived"
    int progress,      // 0-100
    String issues,
    List<Integer> staffIds,
    long createdAt,
    long updatedAt) {

  public static final String STATUS_ACTIVE = "active";
  public static final String STATUS_COMPLETED = "completed";
  public static final String STATUS_ARCHIVED = "archived";

  public boolean isValid() {
    return name != null && !name.isBlank();
  }

  public Project withStaffIds(List<Integer> staffIds) {
    return new Project(id, name, goal, workDir, status, progress, issues, staffIds, createdAt, updatedAt);
  }

  public Project withProgress(int progress) {
    return new Project(id, name, goal, workDir, status, Math.max(0, Math.min(100, progress)), issues, staffIds, createdAt, updatedAt);
  }

  public Project withStatus(String status) {
    return new Project(id, name, goal, workDir, status, progress, issues, staffIds, createdAt, updatedAt);
  }
}
