package ai.openclaw.gateway.ws;

import ai.openclaw.gateway.business.ProjectService;
import ai.openclaw.gateway.business.Staff;
import ai.openclaw.gateway.business.StaffService;
import ai.openclaw.gateway.sessions.InMemorySessionStore;
import ai.openclaw.gateway.ws.ChatLlmExecutor.ChatSendLlmOptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses @name mentions in project-session messages and dispatches tasks
 * to matching staff by creating/activating staff-* sessions.
 */
public final class MentionDispatcher {

  private final ProjectService projectService;
  private final StaffService staffService;
  private final InMemorySessionStore sessionStore;
  private final SessionContextBuilder ctxBuilder;
  private final java.util.function.BiConsumer<String, Runnable> executor;

  public MentionDispatcher(
      ProjectService projectService,
      StaffService staffService,
      InMemorySessionStore sessionStore,
      SessionContextBuilder ctxBuilder,
      java.util.function.BiConsumer<String, Runnable> executor) {
    this.projectService = projectService;
    this.staffService = staffService;
    this.sessionStore = sessionStore;
    this.ctxBuilder = ctxBuilder;
    this.executor = executor;
  }

  /**
   * Scans message for @name patterns and forwards to matching staff sessions.
   * Called after a message is published to a project-* session.
   *
   * @return number of staff members successfully dispatched
   */
  public int dispatch(String projectSessionKey, String message, int projectMessageSeq) {
    Integer projectId = ctxBuilder.parseNumericId(projectSessionKey);
    if (projectId == null || projectService == null || staffService == null) return 0;

    // Parse @name patterns (stops at whitespace or @)
    Pattern pattern = Pattern.compile("@([^\\s@]+)");
    Matcher matcher = pattern.matcher(message);
    List<String> mentioned = new ArrayList<>();
    while (matcher.find()) {
      mentioned.add(matcher.group(1).trim());
    }
    if (mentioned.isEmpty()) return 0;

    try {
      List<Staff> projectStaff = projectService.getProjectStaff(projectId);
      if (projectStaff.isEmpty()) return 0;

      // Role alias map for fuzzy matching
      Map<String, List<String>> roleAliases = Map.of(
          "pm", List.of("pm", "manager", "项目经理", "项目", "经理"),
          "dev", List.of("dev", "developer", "开发", "engineer", "工程师"),
          "qa", List.of("qa", "tester", "测试", "qc", "quality"));

      int dispatched = 0;
      Set<Integer> matchedStaffIds = new HashSet<>();

      for (String mentionName : mentioned) {
        String lower = mentionName.toLowerCase();
        for (Staff staff : projectStaff) {
          if (!matchedStaffIds.add(staff.id())) continue; // already matched this mention round
          if (!matchesStaff(staff, lower, roleAliases)) continue;

          dispatched += dispatchToStaff(
              projectSessionKey, staff, projectId, message, projectMessageSeq);
        }
      }
      return dispatched;
    } catch (Exception e) {
      emitError(projectSessionKey, e);
      return 0;
    }
  }

  private boolean matchesStaff(Staff staff, String mentionLower,
      Map<String, List<String>> roleAliases) {
    // Exact or contains match on name
    if (staff.name().toLowerCase().contains(mentionLower)
        || mentionLower.contains(staff.name().toLowerCase())) {
      return true;
    }
    // Fuzzy role alias match
    List<String> aliases = roleAliases.getOrDefault(
        staff.role().toLowerCase(), List.of());
    for (String alias : aliases) {
      if (alias.equals(mentionLower)
          || mentionLower.contains(alias)
          || alias.contains(mentionLower)) {
        return true;
      }
    }
    return false;
  }

  private int dispatchToStaff(String projectSessionKey, Staff staff,
      int projectId, String message, int projectMessageSeq) {
    String staffSessionKey = "staff-" + staff.id();
    try {
      // Create or get existing staff session
      InMemorySessionStore.SessionEntry staffEntry = sessionStore.get(staffSessionKey);
      if (staffEntry == null) {
        String agentId = "staff-" + staff.id() + "-" + staff.role();
        staffEntry = sessionStore.create(
            staffSessionKey, agentId, projectSessionKey, staff.name(), null);
      }

      // Forward the @mention message with project context
      String forwarded = "[项目群聊 @" + staff.name() + "]\n" + message;
      sessionStore.addMessage(staffSessionKey, forwarded);

      // Emit mention event on the project session
      Map<String, Object> payload = Map.of(
          "ts", System.currentTimeMillis(),
          "projectId", projectId,
          "staffId", staff.id(),
          "staffName", staff.name(),
          "mention", "@" + staff.name(),
          "projectMessageSeq", projectMessageSeq);
      sessionStore.addEvent(projectSessionKey, "mention.detected", payload);

      // Schedule LLM processing for the staff session asynchronously
      if (executor != null) {
        String finalForwarded = forwarded;
        executor.accept(staffSessionKey, () -> {
          try {
            // Find the actual executor - we pass a lightweight trigger
            sessionStore.addEvent(staffSessionKey, "mention.triggered",
                Map.of("ts", System.currentTimeMillis(),
                    "projectId", projectId,
                    "forwarded", finalForwarded));
          } catch (Exception ignored) {}
        });
      }

      return 1;
    } catch (Exception e) {
      emitError(projectSessionKey, e);
      return 0;
    }
  }

  private void emitError(String projectSessionKey, Exception e) {
    try {
      sessionStore.addEvent(projectSessionKey, "mention.error",
          Map.of("ts", System.currentTimeMillis(), "error", e.getMessage()));
    } catch (Exception ignored) {}
  }

  /** Lightweight options record shared with SessionContextBuilder */
  public record ChatSendLlmOptions(
      int reflectionRounds,
      String reflectionPrompt,
      String autonomousGoalId) {
    public static final ChatSendLlmOptions DEFAULT =
        new ChatSendLlmOptions(0, null, null);
  }
}
