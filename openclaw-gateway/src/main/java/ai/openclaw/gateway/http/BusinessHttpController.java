package ai.openclaw.gateway.http;

import ai.openclaw.gateway.business.Project;
import ai.openclaw.gateway.business.Staff;
import ai.openclaw.gateway.business.StaffService;
import ai.openclaw.gateway.business.ProjectService;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** REST API for Staff and Project CRUD operations. */
@RestController
@RequestMapping("/api/business")
public class BusinessHttpController {

  private final StaffService staffService;
  private final ProjectService projectService;

  @Value("${OPENCLAW_GATEWAY_TOKEN:}")
  private String gatewayToken;

  public BusinessHttpController(StaffService staffService, ProjectService projectService) {
    this.staffService = staffService;
    this.projectService = projectService;
  }

  // ─── Staff CRUD ──────────────────────────────────────────────────────────────

  @GetMapping("/staff")
  public ResponseEntity<Map<String, Object>> listStaff(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      List<Staff> list = staffService.list();
      return ok(Map.of("staff", list));
    } catch (Exception e) {
      return error(e);
    }
  }

  @GetMapping("/staff/{id}")
  public ResponseEntity<Map<String, Object>> getStaff(
      @PathVariable int id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      Staff s = staffService.get(id);
      if (s == null) return notFound("staff", id);
      return ok(Map.of("staff", s));
    } catch (Exception e) {
      return error(e);
    }
  }

  @PostMapping("/staff")
  public ResponseEntity<Map<String, Object>> createStaff(
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      String name = (String) body.get("name");
      String role = (String) body.get("role");
      String prompt = (String) body.get("prompt");
      Staff s = staffService.create(name, role, prompt);
      return created(Map.of("staff", s));
    } catch (IllegalArgumentException e) {
      return badRequest(e.getMessage());
    } catch (Exception e) {
      return error(e);
    }
  }

  @PostMapping("/staff/{id}")
  public ResponseEntity<Map<String, Object>> updateStaff(
      @PathVariable int id,
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      String name = (String) body.get("name");
      String role = (String) body.get("role");
      String prompt = (String) body.get("prompt");
      Staff s = staffService.update(id, name, role, prompt);
      if (s == null) return notFound("staff", id);
      return ok(Map.of("staff", s));
    } catch (Exception e) {
      return error(e);
    }
  }

  @DeleteMapping("/staff/{id}")
  public ResponseEntity<Map<String, Object>> deleteStaff(
      @PathVariable int id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      boolean deleted = staffService.delete(id);
      if (!deleted) return notFound("staff", id);
      return ok(Map.of("ok", true));
    } catch (Exception e) {
      return error(e);
    }
  }

  // ─── Project CRUD ────────────────────────────────────────────────────────────

  @GetMapping("/project")
  public ResponseEntity<Map<String, Object>> listProjects(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      List<Project> list = projectService.list();
      return ok(Map.of("projects", list));
    } catch (Exception e) {
      return error(e);
    }
  }

  @GetMapping("/project/{id}")
  public ResponseEntity<Map<String, Object>> getProject(
      @PathVariable int id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      Project p = projectService.get(id);
      if (p == null) return notFound("project", id);
      return ok(Map.of("project", p));
    } catch (Exception e) {
      return error(e);
    }
  }

  @GetMapping("/project/{id}/staff")
  public ResponseEntity<Map<String, Object>> getProjectStaff(
      @PathVariable int id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      List<Staff> staff = projectService.getProjectStaff(id);
      return ok(Map.of("staff", staff));
    } catch (Exception e) {
      return error(e);
    }
  }

  @PostMapping("/project")
  public ResponseEntity<Map<String, Object>> createProject(
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      String name = (String) body.get("name");
      String goal = (String) body.get("goal");
      String workDir = (String) body.get("workDir");
      String status = (String) body.get("status");
      @SuppressWarnings("unchecked")
      List<Integer> staffIds = body.get("staffIds") instanceof List<?> l
          ? l.stream().filter(Number.class::isInstance).map(n -> ((Number) n).intValue()).toList()
          : null;
      Project p = projectService.create(name, goal, workDir, status, staffIds);
      return created(Map.of("project", p));
    } catch (IllegalArgumentException e) {
      return badRequest(e.getMessage());
    } catch (Exception e) {
      return error(e);
    }
  }

  @PostMapping("/project/{id}")
  public ResponseEntity<Map<String, Object>> updateProject(
      @PathVariable int id,
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      String name = (String) body.get("name");
      String goal = (String) body.get("goal");
      String workDir = (String) body.get("workDir");
      String status = (String) body.get("status");
      Integer progress = body.get("progress") instanceof Number n ? n.intValue() : null;
      String issues = (String) body.get("issues");
      @SuppressWarnings("unchecked")
      List<Integer> staffIds = body.get("staffIds") instanceof List<?> l
          ? l.stream().filter(Number.class::isInstance).map(n -> ((Number) n).intValue()).toList()
          : null;
      Project p = projectService.update(id, name, goal, workDir, status, progress, issues, staffIds);
      if (p == null) return notFound("project", id);
      return ok(Map.of("project", p));
    } catch (Exception e) {
      return error(e);
    }
  }

  @DeleteMapping("/project/{id}")
  public ResponseEntity<Map<String, Object>> deleteProject(
      @PathVariable int id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (!authorized(authorization)) return forbidden();
    try {
      boolean deleted = projectService.delete(id);
      if (!deleted) return notFound("project", id);
      return ok(Map.of("ok", true));
    } catch (Exception e) {
      return error(e);
    }
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────

  private boolean authorized(String auth) {
    if (gatewayToken == null || gatewayToken.isBlank()) return true;
    if (auth == null) return false;
    return auth.startsWith("Bearer ") && auth.substring(7).equals(gatewayToken);
  }

  private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
    return ResponseEntity.ok(body);
  }

  private ResponseEntity<Map<String, Object>> created(Map<String, Object> body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(body);
  }

  private ResponseEntity<Map<String, Object>> badRequest(String msg) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error(msg));
  }

  private ResponseEntity<Map<String, Object>> forbidden() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("forbidden"));
  }

  private ResponseEntity<Map<String, Object>> notFound(String entity, int id) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", entity + " not found: " + id));
  }

  private Map<String, Object> error(String msg) {
    return Map.of("error", msg);
  }

  private ResponseEntity<Map<String, Object>> error(Exception e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", e.getMessage()));
  }
}
