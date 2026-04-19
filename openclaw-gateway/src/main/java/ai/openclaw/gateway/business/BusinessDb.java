package ai.openclaw.gateway.business;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite database for business entities (Staff, Project) under
 * {@code ${stateDir}/business/business.sqlite}.
 */
public final class BusinessDb implements AutoCloseable {

  private static final String DB_DIRNAME = "business";
  private static final String DB_FILENAME = "business.sqlite";

  private final Connection connection;

  public BusinessDb(Path stateDir) throws SQLException {
    Path dbDir = stateDir.resolve(DB_DIRNAME);
    try {
      Files.createDirectories(dbDir);
    } catch (IOException e) {
      throw new SQLException("Failed to create business DB directory: " + dbDir, e);
    }
    Path dbPath = dbDir.resolve(DB_FILENAME);
    this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    try (Statement s = connection.createStatement()) {
      s.execute("PRAGMA foreign_keys = ON");
    }
    initTables();
  }

  public Connection getConnection() {
    return connection;
  }

  private void initTables() throws SQLException {
    try (Statement s = connection.createStatement()) {
      s.execute("""
          CREATE TABLE IF NOT EXISTS staff (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            role TEXT NOT NULL CHECK(role IN ('pm','dev','qa')),
            prompt TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
          )
          """);
      s.execute("""
          CREATE TABLE IF NOT EXISTS projects (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            goal TEXT,
            work_dir TEXT,
            status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','completed','archived')),
            progress INTEGER NOT NULL DEFAULT 0 CHECK(progress >= 0 AND progress <= 100),
            issues TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
          )
          """);
      s.execute("""
          CREATE TABLE IF NOT EXISTS project_staff (
            project_id INTEGER NOT NULL,
            staff_id INTEGER NOT NULL,
            PRIMARY KEY (project_id, staff_id),
            FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
            FOREIGN KEY (staff_id) REFERENCES staff(id) ON DELETE CASCADE
          )
          """);
    }
  }

  @Override
  public void close() throws SQLException {
    connection.close();
  }
}
