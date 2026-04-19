package ai.openclaw.gateway.business;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** CRUD operations for Staff entities. */
@Component
public final class StaffService {

  private final BusinessDb db;

  public StaffService(BusinessDb db) {
    this.db = db;
  }

  public List<Staff> list() throws SQLException {
    List<Staff> result = new ArrayList<>();
    String sql = "SELECT id, name, role, prompt, created_at, updated_at FROM staff ORDER BY id";
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        result.add(readStaff(rs));
      }
    }
    return result;
  }

  public Staff get(int id) throws SQLException {
    String sql = "SELECT id, name, role, prompt, created_at, updated_at FROM staff WHERE id = ?";
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
      ps.setInt(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return readStaff(rs);
        }
      }
    }
    return null;
  }

  public Staff create(String name, String role, String prompt) throws SQLException {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name required");
    }
    if (role == null || (!role.equals("pm") && !role.equals("dev") && !role.equals("qa"))) {
      throw new IllegalArgumentException("role must be pm, dev, or qa");
    }
    long now = System.currentTimeMillis();
    String sql = "INSERT INTO staff(name, role, prompt, created_at, updated_at) VALUES(?,?,?,?,?)";
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, name.trim());
      ps.setString(2, role);
      ps.setString(3, prompt);
      ps.setLong(4, now);
      ps.setLong(5, now);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (keys.next()) {
          return new Staff(keys.getInt(1), name.trim(), role, prompt, now, now);
        }
        throw new SQLException("Failed to retrieve generated id");
      }
    }
  }

  public Staff update(int id, String name, String role, String prompt) throws SQLException {
    long now = System.currentTimeMillis();
    String sql = "UPDATE staff SET name=?, role=?, prompt=?, updated_at=? WHERE id=?";
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
      ps.setString(1, name != null ? name.trim() : null);
      ps.setString(2, role);
      ps.setString(3, prompt);
      ps.setLong(4, now);
      ps.setInt(5, id);
      int updated = ps.executeUpdate();
      if (updated == 0) {
        return null;
      }
      return get(id);
    }
  }

  public boolean delete(int id) throws SQLException {
    String sql = "DELETE FROM staff WHERE id=?";
    try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
      ps.setInt(1, id);
      return ps.executeUpdate() > 0;
    }
  }

  private Staff readStaff(ResultSet rs) throws SQLException {
    return new Staff(
        rs.getInt("id"),
        rs.getString("name"),
        rs.getString("role"),
        rs.getString("prompt"),
        rs.getLong("created_at"),
        rs.getLong("updated_at"));
  }
}
