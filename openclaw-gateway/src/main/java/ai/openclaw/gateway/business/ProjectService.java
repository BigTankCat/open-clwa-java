package ai.openclaw.gateway.business;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** CRUD operations for Project entities with staff associations. */
@Component
public final class ProjectService {

  private final BusinessDb db;

  public ProjectService(BusinessDb db) {
    this.db = db;
  }

  public List<Project> list() throws SQLException {
    List<Project> result = new ArrayList<>();
    String sql = """
        SELECT p.id, p.name, p.goal, p.work_dir, p.status, p.progress, p.issues, p.created_at, p.updated_at
        FROM projects p ORDER BY p.id
        """;
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        Project proj = readProject(rs);
        List<Integer> staffIds = getStaffIds(proj.id());
        result.add(proj.withStaffIds(staffIds));
      }
    }
    return result;
  }

  public Project get(int id) throws SQLException {
    String sql = """
        SELECT id, name, goal, work_dir, status, progress, issues, created_at, updated_at
        FROM projects WHERE id=?
        """;
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
      ps.setInt(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          Project proj = readProject(rs);
          return proj.withStaffIds(getStaffIds(id));
        }
      }
    }
    return null;
  }

  public Project create(String name, String goal, String workDir, String status, List<Integer> staffIds)
      throws SQLException {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name required");
    }
    long now = System.currentTimeMillis();
    String sql = """
        INSERT INTO projects(name, goal, work_dir, status, progress, issues, created_at, updated_at)
        VALUES(?,?,?,?,0,?,?,?)
        """;
    int generatedId;
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, name.trim());
      ps.setString(2, goal);
      ps.setString(3, workDir);
      ps.setString(4, status != null ? status : "active");
      ps.setString(5, null);
      ps.setLong(6, now);
      ps.setLong(7, now);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (!keys.next()) throw new SQLException("No generated key");
        generatedId = keys.getInt(1);
      }
    }
    if (staffIds != null && !staffIds.isEmpty()) {
      setStaffIds(generatedId, staffIds);
    }
    return get(generatedId);
  }

  public Project update(int id, String name, String goal, String workDir, String status, Integer progress,
      String issues, List<Integer> staffIds) throws SQLException {
    Project existing = get(id);
    if (existing == null) return null;
    long now = System.currentTimeMillis();
    String sql = """
        UPDATE projects SET name=?, goal=?, work_dir=?, status=?, progress=?, issues=?, updated_at=?
        WHERE id=?
        """;
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
      ps.setString(1, name != null ? name.trim() : existing.name());
      ps.setString(2, goal != null ? goal : existing.goal());
      ps.setString(3, workDir != null ? workDir : existing.workDir());
      ps.setString(4, status != null ? status : existing.status());
      ps.setInt(5, progress != null ? progress : existing.progress());
      ps.setString(6, issues != null ? issues : existing.issues());
      ps.setLong(7, now);
      ps.setInt(8, id);
      ps.executeUpdate();
    }
    if (staffIds != null) {
      setStaffIds(id, staffIds);
    }
    return get(id);
  }

  public boolean delete(int id) throws SQLException {
    String sql = "DELETE FROM projects WHERE id=?";
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
      ps.setInt(1, id);
      return ps.executeUpdate() > 0;
    }
  }

  public List<Staff> getProjectStaff(int projectId) throws SQLException {
    String sql = """
        SELECT s.id, s.name, s.role, s.prompt, s.created_at, s.updated_at
        FROM staff s
        JOIN project_staff ps ON s.id = ps.staff_id
        WHERE ps.project_id = ?
        """;
    List<Staff> result = new ArrayList<>();
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
      ps.setInt(1, projectId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(new Staff(
              rs.getInt("id"), rs.getString("name"), rs.getString("role"),
              rs.getString("prompt"), rs.getLong("created_at"), rs.getLong("updated_at")));
        }
      }
    }
    return result;
  }

  public void setStaffIds(int projectId, List<Integer> staffIds) throws SQLException {
    Connection c = db.getConnection();
    try (PreparedStatement del = c.prepareStatement("DELETE FROM project_staff WHERE project_id=?")) {
      del.setInt(1, projectId);
      del.executeUpdate();
    }
    if (staffIds == null || staffIds.isEmpty()) return;
    String insertSql = "INSERT INTO project_staff(project_id, staff_id) VALUES(?,?)";
    try (PreparedStatement ins = c.prepareStatement(insertSql)) {
      for (Integer staffId : staffIds) {
        ins.setInt(1, projectId);
        ins.setInt(2, staffId);
        ins.addBatch();
      }
      ins.executeBatch();
    }
  }

  private List<Integer> getStaffIds(int projectId) throws SQLException {
    List<Integer> ids = new ArrayList<>();
    String sql = "SELECT staff_id FROM project_staff WHERE project_id=?";
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
      ps.setInt(1, projectId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getInt(1));
        }
      }
    }
    return ids;
  }

  private Project readProject(ResultSet rs) throws SQLException {
    return new Project(
        rs.getInt("id"),
        rs.getString("name"),
        rs.getString("goal"),
        rs.getString("work_dir"),
        rs.getString("status"),
        rs.getInt("progress"),
        rs.getString("issues"),
        List.of(),
        rs.getLong("created_at"),
        rs.getLong("updated_at"));
  }
}
