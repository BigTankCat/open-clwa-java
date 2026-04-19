package ai.openclaw.gateway.ws;

import ai.openclaw.gateway.business.Project;
import ai.openclaw.gateway.business.ProjectService;
import ai.openclaw.gateway.business.Staff;
import ai.openclaw.gateway.business.StaffService;
import ai.openclaw.gateway.sessions.InMemorySessionStore;
import java.util.List;

/** Builds execution context for daily / project-* / staff-* sessions. */
public final class SessionContextBuilder {

  private final ProjectService projectService;
  private final StaffService staffService;

  public SessionContextBuilder(ProjectService projectService, StaffService staffService) {
    this.projectService = projectService;
    this.staffService = staffService;
  }

  /** Detects session type: "daily" | "project" | "staff" */
  public String detectSessionType(String sessionKey) {
    if (sessionKey == null) return "daily";
    if (sessionKey.startsWith("project-")) return "project";
    if (sessionKey.startsWith("staff-")) return "staff";
    return "daily";
  }

  /** Parses numeric ID from "project-{id}" or "staff-{id}", null for others. */
  public Integer parseNumericId(String sessionKey) {
    if (sessionKey == null) return null;
    int dash = sessionKey.indexOf('-');
    if (dash < 0) return null;
    try {
      return Integer.parseInt(sessionKey.substring(dash + 1));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Builds context text for project and staff sessions.
   * Injects project goal/workDir/staff for project-*, role prompt for staff-*.
   */
  public String buildExecutionContext(String sessionKey, InMemorySessionStore.SessionEntry entry) {
    String type = detectSessionType(sessionKey);
    StringBuilder sb = new StringBuilder();

    if ("project".equals(type)) {
      appendProjectContext(sb, sessionKey);
    } else if ("staff".equals(type)) {
      appendStaffContext(sb, sessionKey);
    }
    return sb.toString();
  }

  private void appendProjectContext(StringBuilder sb, String sessionKey) {
    Integer projectId = parseNumericId(sessionKey);
    if (projectId == null || projectService == null) return;
    try {
      Project proj = projectService.get(projectId);
      if (proj == null) return;
      sb.append("## 当前项目信息\n");
      sb.append("- 项目名称: ").append(nullSafe(proj.name())).append("\n");
      sb.append("- 项目目标: ").append(nullSafe(proj.goal())).append("\n");
      sb.append("- 工作目录: ").append(nullSafe(proj.workDir())).append("\n");
      sb.append("- 项目状态: ").append(proj.status()).append("\n");
      if (proj.progress() > 0) sb.append("- 进度: ").append(proj.progress()).append("%\n");
      if (proj.issues() != null && !proj.issues().isBlank()) {
        sb.append("- 当前问题: ").append(proj.issues()).append("\n");
      }
      List<Staff> staffList = projectService.getProjectStaff(projectId);
      if (!staffList.isEmpty()) {
        sb.append("- 团队成员 (").append(staffList.size()).append("人):\n");
        for (Staff s : staffList) {
          sb.append("  * [").append(s.role().toUpperCase()).append("] ").append(s.name());
          if (s.prompt() != null && !s.prompt().isBlank()) {
            sb.append(" — ").append(s.prompt().split("\n")[0]);
          }
          sb.append("\n");
        }
      }
    } catch (Exception ignored) {}
  }

  private void appendStaffContext(StringBuilder sb, String sessionKey) {
    Integer staffId = parseNumericId(sessionKey);
    if (staffId == null || staffService == null) {
      sb.append(buildRolePrompt("dev", null));
      return;
    }
    try {
      Staff staff = staffService.get(staffId);
      if (staff != null) {
        sb.append(buildRolePrompt(
            staff.role() != null ? staff.role().toLowerCase() : "dev",
            staff.prompt()));
      } else {
        sb.append(buildRolePrompt("dev", null));
      }
    } catch (Exception e) {
      sb.append(buildRolePrompt("dev", null));
    }
  }

  /** Returns role-specific system prompt for PM / Dev / QA. */
  public String buildRolePrompt(String role, String customPrompt) {
    StringBuilder sb = new StringBuilder();
    switch (role) {
      case "pm" -> sb.append("""
          你是项目经理 (PM)，负责需求分析、任务拆解、进度跟踪和团队协调。
          你的工作包括：
          - 理解业务需求，转化为可执行的任务
          - 合理拆解任务，安排优先级
          - 跟踪项目进度，识别风险和问题
          - 与开发、测试保持沟通，推动问题解决
          回答要专业、简洁，突出关键信息和行动项。
          """);
      case "qa" -> sb.append("""
          你是测试工程师 (QA)，负责测试策略、用例设计、质量把关。
          你的工作包括：
          - 设计合理的测试用例，覆盖核心路径
          - 执行测试，发现并记录缺陷
          - 验证修复，确保问题真正解决
          - 评估质量风险，给出发布建议
          回答要具体、可操作，关注质量细节。
          """);
      default -> {
        sb.append("""
            你是开发工程师 (Dev)，负责技术实现、代码质量和工程效率。
            你的工作包括：
            - 理解需求，选择合适的技术方案
            - 编写高质量、可维护的代码
            - 编写单元测试和集成测试
            - 优化性能，解决技术难题
            回答要技术导向，注重实操性。

            ## 可用工具
            - file: 读写文件、执行 str_replace（精确替换）
            - bash: 执行 shell 命令（make, git, npm, mvn 等）
            """);
      }
    }
    if (customPrompt != null && !customPrompt.isBlank()) {
      sb.append("\n## 角色个性化提示词\n").append(customPrompt.trim()).append("\n");
    }
    return sb.toString();
  }

  private String nullSafe(String s) {
    return s != null ? s : "未设置";
  }
}
