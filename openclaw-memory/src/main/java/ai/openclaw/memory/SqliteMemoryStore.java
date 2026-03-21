package ai.openclaw.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-agent SQLite file under {@code ${stateDir}/memory/{agentId}.sqlite}, aligned with Node layout
 * {@code ~/.openclaw/memory/{agentId}.sqlite} (first slice: no vectors yet).
 */
public final class SqliteMemoryStore {

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private static final int MAX_CONTENT_CHARS = 256_000;

  private final Path memoryRoot;
  private final ConcurrentHashMap<String, Object> agentLocks = new ConcurrentHashMap<>();

  public SqliteMemoryStore(Path stateDir) {
    this.memoryRoot = stateDir.resolve("memory");
  }

  public void put(String agentId, String path, String content) throws SQLException {
    String safeAgent = requireSafeAgentId(agentId);
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path required");
    }
    if (content == null) {
      throw new IllegalArgumentException("content required");
    }
    if (content.length() > MAX_CONTENT_CHARS) {
      throw new IllegalArgumentException("content too large (max " + MAX_CONTENT_CHARS + " chars)");
    }
    long now = System.currentTimeMillis();
    Object lock = agentLocks.computeIfAbsent(safeAgent, k -> new Object());
    synchronized (lock) {
      try (Connection c = openConnection(safeAgent)) {
        try (PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO memory_chunks(path, content, created_at_ms) VALUES(?,?,?)")) {
          ps.setString(1, path.trim());
          ps.setString(2, content);
          ps.setLong(3, now);
          ps.executeUpdate();
        }
      }
    }
  }

  public List<MemoryHit> search(String agentId, String query, Integer limit) throws SQLException {
    String safeAgent = requireSafeAgentId(agentId);
    if (query == null || query.isBlank()) {
      return List.of();
    }
    int lim = limit != null ? limit : DEFAULT_LIMIT;
    if (lim < 1) lim = DEFAULT_LIMIT;
    if (lim > MAX_LIMIT) lim = MAX_LIMIT;

    String like = "%" + escapeLike(query.trim()) + "%";
    Object lock = agentLocks.computeIfAbsent(safeAgent, k -> new Object());
    synchronized (lock) {
      try (Connection c = openConnection(safeAgent)) {
        try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT id, path, content, created_at_ms FROM memory_chunks "
                    + "WHERE content LIKE ? ESCAPE '\\' OR path LIKE ? ESCAPE '\\' "
                    + "ORDER BY id DESC LIMIT ?")) {
          ps.setString(1, like);
          ps.setString(2, like);
          ps.setInt(3, lim);
          try (ResultSet rs = ps.executeQuery()) {
            List<MemoryHit> out = new ArrayList<>();
            while (rs.next()) {
              out.add(
                  new MemoryHit(
                      rs.getLong("id"),
                      rs.getString("path"),
                      rs.getString("content"),
                      rs.getLong("created_at_ms")));
            }
            return List.copyOf(out);
          }
        }
      }
    }
  }

  /** Build a single system-style block for LLM injection (bounded). */
  public String formatHitsForPrompt(List<MemoryHit> hits, int maxChars) {
    if (hits == null || hits.isEmpty()) {
      return "";
    }
    int cap = maxChars > 0 ? maxChars : 8_000;
    StringBuilder sb = new StringBuilder();
    for (MemoryHit h : hits) {
      String block =
          "- path="
              + h.path()
              + "\n  "
              + truncate(h.content(), 2_000).replace("\n", "\n  ")
              + "\n";
      if (sb.length() + block.length() > cap) {
        break;
      }
      sb.append(block);
    }
    return sb.toString().trim();
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    if (s.length() <= max) return s;
    return s.substring(0, max) + "…";
  }

  private Connection openConnection(String safeAgentId) throws SQLException {
    try {
      Files.createDirectories(memoryRoot);
    } catch (Exception e) {
      throw new SQLException("cannot create memory dir: " + memoryRoot, e);
    }
    Path dbFile = memoryRoot.resolve(safeAgentId + ".sqlite");
    String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
    Connection c = DriverManager.getConnection(url);
    try (Statement st = c.createStatement()) {
      st.execute("PRAGMA journal_mode = WAL");
      st.execute("PRAGMA busy_timeout = 5000");
    }
    initSchema(c);
    return c;
  }

  private static void initSchema(Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(
          "CREATE TABLE IF NOT EXISTS memory_chunks ("
              + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
              + "path TEXT NOT NULL,"
              + "content TEXT NOT NULL,"
              + "created_at_ms INTEGER NOT NULL)");
      st.execute("CREATE INDEX IF NOT EXISTS idx_memory_chunks_path ON memory_chunks(path)");
    }
  }

  static String requireSafeAgentId(String agentId) {
    if (agentId == null || agentId.isBlank()) {
      return "default";
    }
    String t = agentId.trim();
    if (!t.matches("[a-zA-Z0-9._-]+")) {
      throw new IllegalArgumentException("invalid agentId (allowed: [a-zA-Z0-9._-])");
    }
    return t;
  }

  static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
