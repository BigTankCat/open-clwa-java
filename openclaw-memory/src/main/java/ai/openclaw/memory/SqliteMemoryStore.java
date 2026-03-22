package ai.openclaw.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-agent SQLite file under {@code ${stateDir}/memory/{agentId}.sqlite}. Chunks are indexed with
 * LIKE search; optional {@link EmbeddingClient} enables hybrid vector re-ranking (same layout as
 * Node memory files, without sqlite-vec native extension).
 */
public final class SqliteMemoryStore {

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private static final int MAX_CONTENT_CHARS = 256_000;
  /** Target chunk size for indexing (characters). */
  private static final int CHUNK_TARGET = 800;
  /** Overlap between consecutive chunks (characters). */
  private static final int CHUNK_OVERLAP = 100;
  /** LIKE candidate pool before vector re-rank. */
  private static final int HYBRID_LIKE_POOL = 120;

  private final Path memoryRoot;
  private final EmbeddingClient embeddingClient;
  private final ConcurrentHashMap<String, Object> agentLocks = new ConcurrentHashMap<>();

  public SqliteMemoryStore(Path stateDir) {
    this(stateDir, null);
  }

  public SqliteMemoryStore(Path stateDir, EmbeddingClient embeddingClient) {
    this.memoryRoot = stateDir.resolve("memory");
    this.embeddingClient = embeddingClient;
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
    String logicalPath = path.trim();
    List<String> chunks = chunkText(content, CHUNK_TARGET, CHUNK_OVERLAP);
    Object lock = agentLocks.computeIfAbsent(safeAgent, k -> new Object());
    synchronized (lock) {
      try (Connection c = openConnection(safeAgent)) {
        try (PreparedStatement delVec =
            c.prepareStatement(
                "DELETE FROM memory_chunk_vectors WHERE chunk_id IN "
                    + "(SELECT id FROM memory_chunks WHERE path = ?)")) {
          delVec.setString(1, logicalPath);
          delVec.executeUpdate();
        }
        try (PreparedStatement del =
            c.prepareStatement("DELETE FROM memory_chunks WHERE path = ?")) {
          del.setString(1, logicalPath);
          del.executeUpdate();
        }
        try (PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO memory_chunks(path, content, created_at_ms, chunk_index) VALUES(?,?,?,?)")) {
          for (int i = 0; i < chunks.size(); i++) {
            ps.setString(1, logicalPath);
            ps.setString(2, chunks.get(i));
            ps.setLong(3, now);
            ps.setInt(4, i);
            ps.executeUpdate();
          }
        }
        if (useEmbeddings()) {
          indexVectorsForPath(c, logicalPath);
        }
      }
    }
  }

  private void indexVectorsForPath(Connection c, String logicalPath) throws SQLException {
    List<Long> ids = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    try (PreparedStatement q =
        c.prepareStatement(
            "SELECT id, content FROM memory_chunks WHERE path = ? ORDER BY chunk_index ASC")) {
      q.setString(1, logicalPath);
      try (ResultSet rs = q.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getLong("id"));
          texts.add(rs.getString("content"));
        }
      }
    }
    try (PreparedStatement ins =
        c.prepareStatement(
            "INSERT OR REPLACE INTO memory_chunk_vectors(chunk_id, dim, vec) VALUES(?,?,?)")) {
      for (int i = 0; i < ids.size(); i++) {
        try {
          float[] v = embeddingClient.embed(texts.get(i));
          if (v == null || v.length == 0) {
            continue;
          }
          float[] norm = HttpEmbeddingClient.l2Normalize(v);
          byte[] blob = floatsToLeBlob(norm);
          ins.setLong(1, ids.get(i));
          ins.setInt(2, norm.length);
          ins.setBytes(3, blob);
          ins.executeUpdate();
        } catch (Exception ignored) {
          // Skip chunk if embedding fails (network etc.)
        }
      }
    }
  }

  private boolean useEmbeddings() {
    return embeddingClient != null && embeddingClient.enabled();
  }

  private static byte[] floatsToLeBlob(float[] v) {
    ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (float f : v) {
      bb.putFloat(f);
    }
    return bb.array();
  }

  private static float[] blobToFloats(byte[] blob) {
    if (blob == null || blob.length < 4 || blob.length % 4 != 0) {
      return null;
    }
    ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
    float[] v = new float[blob.length / 4];
    for (int i = 0; i < v.length; i++) {
      v[i] = bb.getFloat();
    }
    return v;
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
        List<MemoryHit> likeHits = new ArrayList<>();
        try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT id, path, content, created_at_ms, chunk_index FROM memory_chunks "
                    + "WHERE content LIKE ? ESCAPE '\\' OR path LIKE ? ESCAPE '\\' "
                    + "ORDER BY id DESC LIMIT ?")) {
          ps.setString(1, like);
          ps.setString(2, like);
          ps.setInt(3, useEmbeddings() ? HYBRID_LIKE_POOL : lim);
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              likeHits.add(
                  new MemoryHit(
                      rs.getLong("id"),
                      rs.getString("path"),
                      rs.getString("content"),
                      rs.getLong("created_at_ms"),
                      rs.getInt("chunk_index")));
            }
          }
        }
        if (!useEmbeddings() || likeHits.isEmpty()) {
          return trimList(likeHits, lim);
        }
        float[] qv;
        try {
          qv = embeddingClient.embed(query.trim());
        } catch (Exception e) {
          return trimList(likeHits, lim);
        }
        if (qv == null || qv.length == 0) {
          return trimList(likeHits, lim);
        }
        qv = HttpEmbeddingClient.l2Normalize(qv);
        List<ScoredHit> scored = new ArrayList<>();
        try (PreparedStatement ps =
            c.prepareStatement("SELECT vec, dim FROM memory_chunk_vectors WHERE chunk_id = ?")) {
          for (MemoryHit h : likeHits) {
            ps.setLong(1, h.id());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                scored.add(new ScoredHit(h, 0));
                continue;
              }
              byte[] blob = rs.getBytes("vec");
              int dim = rs.getInt("dim");
              float[] v = blobToFloats(blob);
              if (v == null || v.length != dim || v.length != qv.length) {
                scored.add(new ScoredHit(h, 0));
              } else {
                scored.add(new ScoredHit(h, HttpEmbeddingClient.cosine(qv, v)));
              }
            }
          }
        }
        scored.sort(Comparator.comparingDouble((ScoredHit s) -> s.score).reversed());
        List<MemoryHit> out = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < lim; i++) {
          out.add(scored.get(i).hit);
        }
        return List.copyOf(out);
      }
    }
  }

  private static List<MemoryHit> trimList(List<MemoryHit> hits, int lim) {
    if (hits.size() <= lim) {
      return List.copyOf(hits);
    }
    return List.copyOf(hits.subList(0, lim));
  }

  private record ScoredHit(MemoryHit hit, double score) {}

  static List<String> chunkText(String text, int target, int overlap) {
    if (text == null || text.isEmpty()) {
      return List.of("");
    }
    if (target <= 0) {
      return List.of(text);
    }
    int o = Math.max(0, Math.min(overlap, target - 1));
    List<String> parts = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(text.length(), start + target);
      parts.add(text.substring(start, end));
      if (end >= text.length()) {
        break;
      }
      start = end - o;
      if (start < 0) {
        start = end;
      }
    }
    return parts;
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
              + " [#"
              + h.chunkIndex()
              + "]\n  "
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
    Connection conn = DriverManager.getConnection(url);
    try (Statement st = conn.createStatement()) {
      st.execute("PRAGMA journal_mode = WAL");
      st.execute("PRAGMA busy_timeout = 5000");
    }
    initSchema(conn);
    return conn;
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
      st.execute(
          "CREATE TABLE IF NOT EXISTS memory_chunk_vectors ("
              + "chunk_id INTEGER PRIMARY KEY,"
              + "dim INTEGER NOT NULL,"
              + "vec BLOB NOT NULL)");
    }
    ensureChunkIndexColumn(c);
  }

  private static void ensureChunkIndexColumn(Connection c) throws SQLException {
    boolean has = false;
    DatabaseMetaData md = c.getMetaData();
    try (ResultSet rs = md.getColumns(null, null, "memory_chunks", "chunk_index")) {
      has = rs.next();
    }
    if (!has) {
      try (Statement st = c.createStatement()) {
        st.execute("ALTER TABLE memory_chunks ADD COLUMN chunk_index INTEGER NOT NULL DEFAULT 0");
      }
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
