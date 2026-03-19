package ai.openclaw.gateway.sessions;

import ai.openclaw.config.ConfigPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.stereotype.Component;

@Component
public class InMemorySessionStore {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String META_EXT = ".meta.json";
  private static final String TRANSCRIPT_EXT = ".jsonl";

  private final Path transcriptsDir;

  // Serialize file IO to keep transcript appends consistent.
  private final ReentrantLock ioLock = new ReentrantLock();

  public static final class SessionEntry {
    public final String key;
    public final String sessionId;
    public final String agentId;
    public final String parentSessionKey;
    public volatile String label;
    public volatile String model;
    public final long createdAt;
    public volatile long updatedAt;

    // For the first slice we only keep message strings.
    public final List<String> messages;

    SessionEntry(
        String key,
        String sessionId,
        String agentId,
        String parentSessionKey,
        String label,
        String model,
        long createdAt,
        long updatedAt,
        List<String> messages) {
      this.key = key;
      this.sessionId = sessionId;
      this.agentId = agentId;
      this.parentSessionKey = parentSessionKey;
      this.label = label;
      this.model = model;
      this.createdAt = createdAt;
      this.updatedAt = updatedAt;
      this.messages = Collections.synchronizedList(new ArrayList<>());
      if (messages != null) {
        this.messages.addAll(messages);
      }
    }
  }

  private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();

  public InMemorySessionStore(ConfigPaths configPaths) {
    this.transcriptsDir =
        configPaths.getStateDirPath().resolve("sessions").resolve("transcripts");
    try {
      loadFromDisk();
    } catch (Exception e) {
      throw new RuntimeException("Failed to load session store from " + transcriptsDir, e);
    }
  }

  public SessionEntry create(
      String key,
      String agentId,
      String parentSessionKey,
      String label,
      String model) {
    SessionEntry existing = sessions.get(key);
    if (existing != null) {
      return existing;
    }
    long now = System.currentTimeMillis();
    SessionEntry entry =
        new SessionEntry(
            key,
            UUID.randomUUID().toString(),
            agentId,
            parentSessionKey,
            label,
            model,
            now,
            now,
            List.of());
    sessions.put(key, entry);
    persistMeta(entry);
    return entry;
  }

  public SessionEntry get(String key) {
    return sessions.get(key);
  }

  public void addMessage(String key, String message) {
    SessionEntry entry = sessions.get(key);
    if (entry == null) return;
    long now = System.currentTimeMillis();
    entry.messages.add(message);
    entry.updatedAt = now;
    appendTranscriptLine(entry, message, now);
    persistMeta(entry);
  }

  public List<String> listMessages(String key, int limit) {
    SessionEntry entry = sessions.get(key);
    if (entry == null) return List.of();
    if (limit <= 0) return List.of();
    synchronized (entry.messages) {
      int size = entry.messages.size();
      int start = Math.max(0, size - limit);
      return new ArrayList<>(entry.messages.subList(start, size));
    }
  }

  public List<Map<String, Object>> listSessions(int limit, String labelFilter, String search) {
    long now = System.currentTimeMillis();
    List<SessionEntry> entries = new ArrayList<>(sessions.values());
    // Simple filtering for the first slice.
    List<SessionEntry> filtered = new ArrayList<>();
    for (SessionEntry e : entries) {
      if (labelFilter != null && !labelFilter.isBlank()) {
        if (e.label == null || !labelFilter.equals(e.label)) continue;
      }
      if (search != null && !search.isBlank()) {
        String q = search.toLowerCase();
        String hay = (e.label == null ? "" : e.label)
            + " "
            + (e.key == null ? "" : e.key)
            + " "
            + (e.sessionId == null ? "" : e.sessionId);
        if (!hay.toLowerCase().contains(q)) continue;
      }
      filtered.add(e);
    }
    filtered.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
    if (limit > 0 && filtered.size() > limit) {
      filtered = filtered.subList(0, limit);
    }

    List<Map<String, Object>> rows = new ArrayList<>(filtered.size());
    for (SessionEntry e : filtered) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("key", e.key);
      row.put("sessionId", e.sessionId);
      row.put("agentId", e.agentId);
      row.put("parentSessionKey", e.parentSessionKey);
      row.put("label", e.label);
      row.put("model", e.model);
      row.put("updatedAt", e.updatedAt);
      row.put("runtimeMs", null);
      row.put("status", "ok");
      row.put("ts", now);
      rows.add(row);
    }
    return rows;
  }

  public boolean delete(String key) {
    SessionEntry removed = sessions.remove(key);
    if (removed == null) return false;
    // Best-effort delete.
    try {
      Files.deleteIfExists(transcriptPathForKey(key));
      Files.deleteIfExists(metaPathForKey(key));
    } catch (Exception ignored) {
      // ignore
    }
    return true;
  }

  private void loadFromDisk() throws Exception {
    if (!Files.isDirectory(transcriptsDir)) {
      return;
    }

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(transcriptsDir)) {
      for (Path meta : stream) {
        String name = meta.getFileName().toString();
        if (!name.endsWith(META_EXT)) continue;
        Map<String, Object> metaMap = MAPPER.readValue(meta.toFile(), Map.class);
        String key = (String) metaMap.get("key");
        if (key == null || key.isBlank()) continue;

        String sessionId = (String) metaMap.getOrDefault("sessionId", "");
        String agentId = (String) metaMap.getOrDefault("agentId", "");
        String parentSessionKey = (String) metaMap.getOrDefault("parentSessionKey", null);
        String label = (String) metaMap.getOrDefault("label", null);
        String model = (String) metaMap.getOrDefault("model", null);

        long createdAt = toLong(metaMap.get("createdAt"), System.currentTimeMillis());
        long updatedAt = toLong(metaMap.get("updatedAt"), createdAt);

        List<String> messages = readTranscriptMessages(key);

        SessionEntry entry =
            new SessionEntry(
                key,
                sessionId,
                agentId,
                parentSessionKey,
                label,
                model,
                createdAt,
                updatedAt,
                messages);
        sessions.put(key, entry);
      }
    }
  }

  private List<String> readTranscriptMessages(String key) {
    Path file = transcriptPathForKey(key);
    if (!Files.isRegularFile(file)) return List.of();
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      List<String> messages = new ArrayList<>();
      for (String line : lines) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) continue;
        Map<String, Object> obj = MAPPER.readValue(trimmed, Map.class);
        Object msg = obj.get("message");
        if (msg instanceof String s) {
          messages.add(s);
        }
      }
      return messages;
    } catch (Exception e) {
      // If transcript is corrupted, fail softly by treating it as empty.
      return List.of();
    }
  }

  private Path metaPathForKey(String key) {
    return transcriptsDir.resolve(encodedKey(key) + META_EXT);
  }

  private Path transcriptPathForKey(String key) {
    return transcriptsDir.resolve(encodedKey(key) + TRANSCRIPT_EXT);
  }

  private String encodedKey(String key) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
  }

  private void persistMeta(SessionEntry entry) {
    ioLock.lock();
    try {
      Files.createDirectories(transcriptsDir);
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("key", entry.key);
      meta.put("sessionId", entry.sessionId);
      meta.put("agentId", entry.agentId);
      meta.put("parentSessionKey", entry.parentSessionKey);
      meta.put("label", entry.label);
      meta.put("model", entry.model);
      meta.put("createdAt", entry.createdAt);
      meta.put("updatedAt", entry.updatedAt);
      MAPPER.writeValue(metaPathForKey(entry.key).toFile(), meta);
    } catch (Exception e) {
      throw new RuntimeException("Failed to persist session meta for key=" + entry.key, e);
    } finally {
      ioLock.unlock();
    }
  }

  private void appendTranscriptLine(SessionEntry entry, String message, long now) {
    ioLock.lock();
    try {
      Files.createDirectories(transcriptsDir);
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("ts", now);
      line.put("message", message);
      String json = MAPPER.writeValueAsString(line);
      Files.writeString(
          transcriptPathForKey(entry.key),
          json + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.APPEND);
    } catch (Exception e) {
      throw new RuntimeException("Failed to append transcript for key=" + entry.key, e);
    } finally {
      ioLock.unlock();
    }
  }

  private static long toLong(Object v, long fallback) {
    if (v == null) return fallback;
    if (v instanceof Number n) {
      return n.longValue();
    }
    if (v instanceof String s) {
      try {
        return Long.parseLong(s);
      } catch (Exception ignored) {
        return fallback;
      }
    }
    return fallback;
  }
}

