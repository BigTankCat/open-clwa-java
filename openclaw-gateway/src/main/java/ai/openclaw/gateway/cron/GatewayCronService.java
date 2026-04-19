package ai.openclaw.gateway.cron;

import ai.openclaw.config.ConfigPaths;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Cron scheduler with JSON persistence. Job records are stored as maps so the web UI can round-trip
 * arbitrary fields; scheduling uses the UNIX {@code schedule} string only.
 */
@Service
public final class GatewayCronService {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ZoneId ZONE = ZoneId.systemDefault();
  private static final CronParser UNIX_PARSER =
      new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

  private final Path storePath;
  private final List<Map<String, Object>> jobs = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<String, List<Map<String, Object>>> runsByJob =
      new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("openclaw-java-cron");
            return t;
          });

  public GatewayCronService(ConfigPaths configPaths) {
    this.storePath =
        configPaths.getStateDirPath().resolve("java-gateway").resolve("cron-store.json");
  }

  @PostConstruct
  void start() {
    load();
    scheduler.scheduleAtFixedRate(this::tick, 5, 10, TimeUnit.SECONDS);
  }

  @PreDestroy
  void stop() {
    scheduler.shutdown();
  }

  @SuppressWarnings("unchecked")
  private void load() {
    try {
      if (!Files.isRegularFile(storePath)) {
        return;
      }
      Map<String, Object> root = MAPPER.readValue(storePath.toFile(), new TypeReference<>() {});
      List<Map<String, Object>> jl = (List<Map<String, Object>>) root.get("jobs");
      if (jl != null) {
        jobs.clear();
        for (Map<String, Object> m : jl) {
          jobs.add(new LinkedHashMap<>(m));
        }
      }
      Map<String, List<Map<String, Object>>> rr =
          (Map<String, List<Map<String, Object>>>) root.get("runs");
      if (rr != null) {
        runsByJob.clear();
        for (Map.Entry<String, List<Map<String, Object>>> e : rr.entrySet()) {
          List<Map<String, Object>> cp = new CopyOnWriteArrayList<>();
          for (Map<String, Object> r : e.getValue()) {
            cp.add(new LinkedHashMap<>(r));
          }
          runsByJob.put(e.getKey(), cp);
        }
      }
    } catch (Exception ignored) {
      // empty
    }
  }

  private void save() {
    try {
      Files.createDirectories(storePath.getParent());
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("jobs", new ArrayList<>(jobs));
      Map<String, List<Map<String, Object>>> runs = new LinkedHashMap<>();
      for (Map.Entry<String, List<Map<String, Object>>> e : runsByJob.entrySet()) {
        runs.put(e.getKey(), new ArrayList<>(e.getValue()));
      }
      root.put("runs", runs);
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), root);
    } catch (Exception e) {
      throw new RuntimeException("cron persist failed: " + e.getMessage(), e);
    }
  }

  private void tick() {
    long now = System.currentTimeMillis();
    ZonedDateTime znow = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZONE);
    boolean changed = false;
    for (Map<String, Object> job : jobs) {
      if (Boolean.FALSE.equals(job.get("enabled"))) {
        continue;
      }
      String schedule = stringVal(job.get("schedule"));
      if (schedule == null || schedule.isBlank()) {
        continue;
      }
      long next = longVal(job.get("nextRunAtMs"), 0L);
      if (next > now) {
        continue;
      }
      String id = stringVal(job.get("id"));
      if (id == null) {
        continue;
      }
      fireRun(id, "schedule", null);
      job.put("nextRunAtMs", computeNextRun(schedule, znow));
      job.put("lastRunAtMs", now);
      changed = true;
    }
    if (changed) {
      save();
    }
  }

  static long computeNextRun(String schedule, ZonedDateTime from) {
    try {
      Cron cron = UNIX_PARSER.parse(schedule.trim());
      ExecutionTime et = ExecutionTime.forCron(cron);
      Optional<ZonedDateTime> next = et.nextExecution(from);
      return next.map(z -> z.toInstant().toEpochMilli()).orElse(from.toInstant().toEpochMilli() + 60_000);
    } catch (Exception e) {
      return from.toInstant().toEpochMilli() + 60_000;
    }
  }

  private void fireRun(String jobId, String mode, String manualBy) {
    long ts = System.currentTimeMillis();
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("id", UUID.randomUUID().toString());
    run.put("jobId", jobId);
    run.put("ts", ts);
    run.put("mode", mode);
    run.put("ok", true);
    if (manualBy != null) {
      run.put("manualBy", manualBy);
    }
    List<Map<String, Object>> list =
        runsByJob.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
    list.add(run);
    while (list.size() > 80) {
      list.remove(0);
    }
  }

  public Map<String, Object> listJobsRpc(Map<String, Object> params) {
    int limit = 200;
    if (params != null && params.get("limit") instanceof Number n) {
      limit = Math.max(1, Math.min(500, n.intValue()));
    }
    List<Map<String, Object>> slice = new ArrayList<>();
    for (int i = 0; i < jobs.size() && i < limit; i++) {
      slice.add(new LinkedHashMap<>(jobs.get(i)));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("jobs", slice);
    out.put("total", jobs.size());
    out.put("limit", limit);
    out.put("offset", 0);
    out.put("nextOffset", null);
    out.put("hasMore", false);
    return out;
  }

  public Map<String, Object> statusRpc() {
    return Map.of(
        "ok",
        true,
        "jobCount",
        jobs.size(),
        "ts",
        System.currentTimeMillis());
  }

  public Map<String, Object> addJob(Map<String, Object> body) {
    if (body == null) {
      throw new IllegalArgumentException("cron.add: body required");
    }
    Map<String, Object> job = new LinkedHashMap<>(body);
    String id = stringVal(job.get("id"));
    if (id == null || id.isBlank()) {
      id = UUID.randomUUID().toString();
      job.put("id", id);
    }
    String schedule = stringVal(job.get("schedule"));
    if (schedule == null || schedule.isBlank()) {
      throw new IllegalArgumentException("cron.add: schedule required");
    }
    if (!job.containsKey("enabled")) {
      job.put("enabled", true);
    }
    job.put(
        "nextRunAtMs",
        computeNextRun(schedule, ZonedDateTime.now(ZONE)));
    final String removeId = id;
    jobs.removeIf(j -> removeId.equals(stringVal(j.get("id"))));
    jobs.add(job);
    save();
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("ok", true);
    res.put("job", new LinkedHashMap<>(job));
    return res;
  }

  public Map<String, Object> updateJobRpc(Map<String, Object> params) {
    if (params == null) {
      throw new IllegalArgumentException("cron.update: params required");
    }
    String id = stringVal(params.get("id"));
    if (id == null) {
      throw new IllegalArgumentException("cron.update: id required");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> patch =
        params.get("patch") instanceof Map ? (Map<String, Object>) params.get("patch") : params;
    Map<String, Object> found = null;
    for (Map<String, Object> j : jobs) {
      if (id.equals(stringVal(j.get("id")))) {
        found = j;
        break;
      }
    }
    if (found == null) {
      throw new IllegalArgumentException("cron.update: job not found");
    }
    for (Map.Entry<String, Object> e : patch.entrySet()) {
      if ("id".equals(e.getKey())) {
        continue;
      }
      found.put(e.getKey(), e.getValue());
    }
    String schedule = stringVal(found.get("schedule"));
    if (schedule != null && !schedule.isBlank()) {
      found.put(
          "nextRunAtMs",
          computeNextRun(schedule, ZonedDateTime.now(ZONE)));
    }
    save();
    return Map.of("ok", true, "job", new LinkedHashMap<>(found));
  }

  public void removeJob(String id) {
    if (id == null) {
      return;
    }
    jobs.removeIf(j -> id.equals(stringVal(j.get("id"))));
    runsByJob.remove(id);
    save();
  }

  public Map<String, Object> runJobRpc(Map<String, Object> params) {
    String id = params != null ? stringVal(params.get("id")) : null;
    if (id == null) {
      throw new IllegalArgumentException("cron.run: id required");
    }
    String mode = stringVal(params.get("mode"));
    Map<String, Object> found = null;
    for (Map<String, Object> j : jobs) {
      if (id.equals(stringVal(j.get("id")))) {
        found = j;
        break;
      }
    }
    if (found == null) {
      throw new IllegalArgumentException("cron.run: job not found");
    }
    fireRun(id, mode != null ? mode : "manual", "gateway");
    String schedule = stringVal(found.get("schedule"));
    if (schedule != null && !schedule.isBlank()) {
      found.put(
          "nextRunAtMs",
          computeNextRun(schedule, ZonedDateTime.now(ZONE)));
    }
    found.put("lastRunAtMs", System.currentTimeMillis());
    save();
    return Map.of("ok", true, "jobId", id);
  }

  public Map<String, Object> listRunsRpc(Map<String, Object> params) {
    String jobId = params != null ? stringVal(params.get("jobId")) : null;
    int limit = 50;
    if (params != null && params.get("limit") instanceof Number n) {
      limit = Math.max(1, Math.min(200, n.intValue()));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    if (jobId != null && !jobId.isBlank()) {
      List<Map<String, Object>> list = runsByJob.getOrDefault(jobId, List.of());
      int n = Math.min(limit, list.size());
      out.put(
          "runs",
          new ArrayList<>(list.subList(Math.max(0, list.size() - n), list.size())));
      out.put("total", list.size());
    } else {
      List<Map<String, Object>> all = new ArrayList<>();
      for (List<Map<String, Object>> lst : runsByJob.values()) {
        all.addAll(lst);
      }
      all.sort((a, b) -> Long.compare(longVal(b.get("ts"), 0), longVal(a.get("ts"), 0)));
      int n = Math.min(limit, all.size());
      out.put("runs", new ArrayList<>(all.subList(0, n)));
      out.put("total", all.size());
    }
    out.put("ok", true);
    return out;
  }

  private static String stringVal(Object o) {
    return o instanceof String s ? s : null;
  }

  private static long longVal(Object o, long d) {
    if (o instanceof Number n) {
      return n.longValue();
    }
    return d;
  }
}
