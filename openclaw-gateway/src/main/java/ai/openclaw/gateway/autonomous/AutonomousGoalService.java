package ai.openclaw.gateway.autonomous;

import ai.openclaw.config.ConfigPaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Persists multi-step "autonomous goals": one JSON document per goal plus append-only JSONL events.
 *
 * <p>Directory: {@code ${OPENCLAW_STATE_DIR}/java-gateway/autonomous-goals/}.
 */
@Component
public final class AutonomousGoalService {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};
  private static final int DEFAULT_EVENT_LIMIT = 100;
  private static final int MAX_EVENT_LIMIT = 500;

  private final Path goalsDir;
  private final Object globalLock = new Object();

  public AutonomousGoalService(ConfigPaths configPaths) {
    this.goalsDir =
        configPaths
            .getStateDirPath()
            .resolve("java-gateway")
            .resolve("autonomous-goals")
            .toAbsolutePath()
            .normalize();
  }

  private Path docPath(String id) {
    return goalsDir.resolve(safeId(id) + ".json");
  }

  private Path eventsPath(String id) {
    return goalsDir.resolve(safeId(id) + ".events.jsonl");
  }

  private static String safeId(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("goal id required");
    }
    String t = id.trim();
    if (t.contains("..") || t.contains("/") || t.contains("\\")) {
      throw new IllegalArgumentException("invalid goal id");
    }
    return t;
  }

  private void ensureDir() throws IOException {
    Files.createDirectories(goalsDir);
  }

  public Map<String, Object> create(Map<String, Object> params) throws IOException {
    if (params == null) {
      throw new IllegalArgumentException("autonomous.goals.create: params required");
    }
    String title = stringField(params, "title");
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("autonomous.goals.create: title required");
    }
    long now = System.currentTimeMillis();
    String id = UUID.randomUUID().toString();
    AutonomousGoalDocument doc = new AutonomousGoalDocument();
    doc.id = id;
    doc.title = title.trim();
    doc.objective = optionalString(params, "objective");
    doc.acceptanceCriteria = optionalString(params, "acceptanceCriteria");
    doc.phase = AutonomousGoalPhase.INTAKE.name();
    doc.plan = mapField(params, "plan");
    doc.lastEvaluation = mapField(params, "lastEvaluation");
    doc.linkedSessionKey = optionalString(params, "linkedSessionKey");
    doc.createdAtMs = now;
    doc.updatedAtMs = now;
    synchronized (globalLock) {
      ensureDir();
      saveDocument(doc);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", true);
    out.put("id", id);
    out.put("goal", toViewMap(doc));
    return out;
  }

  public Map<String, Object> get(String id, Integer eventLimit) throws IOException {
    safeId(id);
    int limit = eventLimit == null ? DEFAULT_EVENT_LIMIT : Math.min(MAX_EVENT_LIMIT, Math.max(1, eventLimit));
    synchronized (globalLock) {
      AutonomousGoalDocument doc = loadDocument(id);
      if (doc == null) {
        return null;
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("goal", toViewMap(doc));
      out.put("recentEvents", readRecentEvents(id, limit));
      return out;
    }
  }

  public List<Map<String, Object>> listSummaries() throws IOException {
    synchronized (globalLock) {
      if (!Files.isDirectory(goalsDir)) {
        return List.of();
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      try (Stream<Path> stream = Files.list(goalsDir)) {
        stream
            .filter(p -> p.getFileName().toString().endsWith(".json"))
            .forEach(
                p -> {
                  try {
                    AutonomousGoalDocument d = MAPPER.readValue(p.toFile(), AutonomousGoalDocument.class);
                    if (d != null && d.id != null) {
                      Map<String, Object> row = new LinkedHashMap<>();
                      row.put("id", d.id);
                      row.put("title", d.title);
                      row.put("phase", d.phase);
                      row.put("updatedAtMs", d.updatedAtMs);
                      rows.add(row);
                    }
                  } catch (IOException ignored) {
                    // skip corrupt file
                  }
                });
      }
      rows.sort(Comparator.comparingLong((Map<String, Object> m) -> ((Number) m.get("updatedAtMs")).longValue()).reversed());
      return rows;
    }
  }

  public Map<String, Object> patch(String id, Map<String, Object> params) throws IOException {
    safeId(id);
    if (params == null || params.isEmpty()) {
      throw new IllegalArgumentException("autonomous.goals.patch: fields required");
    }
    synchronized (globalLock) {
      AutonomousGoalDocument doc = loadDocument(id);
      if (doc == null) {
        return null;
      }
      long now = System.currentTimeMillis();
      if (params.containsKey("title")) {
        String t = optionalString(params, "title");
        doc.title = t != null ? t : doc.title;
      }
      if (params.containsKey("objective")) {
        doc.objective = optionalString(params, "objective");
      }
      if (params.containsKey("acceptanceCriteria")) {
        doc.acceptanceCriteria = optionalString(params, "acceptanceCriteria");
      }
      if (params.containsKey("phase")) {
        String ph = optionalString(params, "phase");
        if (ph != null && !ph.isBlank()) {
          doc.phase = AutonomousGoalPhase.parseOrDefault(ph, AutonomousGoalPhase.INTAKE).name();
        }
      }
      if (params.containsKey("plan")) {
        doc.plan = mapField(params, "plan");
      }
      if (params.containsKey("lastEvaluation")) {
        doc.lastEvaluation = mapField(params, "lastEvaluation");
      }
      if (params.containsKey("linkedSessionKey")) {
        doc.linkedSessionKey = optionalString(params, "linkedSessionKey");
      }
      doc.updatedAtMs = now;
      saveDocument(doc);
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("ok", true);
      out.put("goal", toViewMap(doc));
      return out;
    }
  }

  /**
   * Appends one JSONL event and bumps {@code updatedAtMs}. No-op if the goal file is missing.
   *
   * @return true if written
   */
  public boolean appendEvent(String id, String type, Map<String, Object> payload) throws IOException {
    if (type == null || type.isBlank()) {
      return false;
    }
    safeId(id);
    synchronized (globalLock) {
      AutonomousGoalDocument doc = loadDocument(id);
      if (doc == null) {
        return false;
      }
      ensureDir();
      long now = System.currentTimeMillis();
      doc.updatedAtMs = now;
      saveDocument(doc);
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("ts", now);
      line.put("type", type.trim());
      if (payload != null && !payload.isEmpty()) {
        line.put("payload", payload);
      }
      byte[] bytes = (MAPPER.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
      Files.write(eventsPath(id), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      return true;
    }
  }

  private AutonomousGoalDocument loadDocument(String id) throws IOException {
    Path p = docPath(id);
    if (!Files.isRegularFile(p)) {
      return null;
    }
    return MAPPER.readValue(p.toFile(), AutonomousGoalDocument.class);
  }

  private void saveDocument(AutonomousGoalDocument doc) throws IOException {
    ensureDir();
    Path p = docPath(doc.id);
    byte[] data = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(doc);
    Files.write(p, data);
  }

  private List<Map<String, Object>> readRecentEvents(String id, int limit) throws IOException {
    Path p = eventsPath(id);
    if (!Files.isRegularFile(p)) {
      return List.of();
    }
    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
    int from = Math.max(0, lines.size() - limit);
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = from; i < lines.size(); i++) {
      String ln = lines.get(i);
      if (ln == null || ln.isBlank()) {
        continue;
      }
      out.add(MAPPER.readValue(ln, MAP_REF));
    }
    return out;
  }

  private static Map<String, Object> toViewMap(AutonomousGoalDocument d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", d.id);
    m.put("title", d.title);
    m.put("objective", d.objective);
    m.put("acceptanceCriteria", d.acceptanceCriteria);
    m.put("phase", d.phase);
    m.put("plan", d.plan);
    m.put("lastEvaluation", d.lastEvaluation);
    m.put("linkedSessionKey", d.linkedSessionKey);
    m.put("createdAtMs", d.createdAtMs);
    m.put("updatedAtMs", d.updatedAtMs);
    return m;
  }

  private static String stringField(Map<String, Object> params, String key) {
    Object o = params.get(key);
    return o == null ? null : String.valueOf(o);
  }

  private static String optionalString(Map<String, Object> params, String key) {
    String s = stringField(params, key);
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapField(Map<String, Object> params, String key) {
    Object o = params.get(key);
    if (o == null) {
      return null;
    }
    if (o instanceof Map) {
      return (Map<String, Object>) o;
    }
    return MAPPER.convertValue(o, MAP_REF);
  }
}
