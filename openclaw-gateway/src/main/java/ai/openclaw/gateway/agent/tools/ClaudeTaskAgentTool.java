package ai.openclaw.gateway.agent.tools;

import ai.openclaw.agent.runtime.ToolExecutionContext;
import ai.openclaw.agent.tools.AgentTool;
import ai.openclaw.config.ConfigPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs <strong>Claude Code</strong> CLI ({@code claude --print --permission-mode bypassPermissions}) in a
 * sandboxed working directory under {@code OPENCLAW_WORKSPACE_ROOT}. Intended for Java gateways that
 * delegate multi-step coding to Anthropic's CLI instead of embedding read/apply_patch tools.
 *
 * <p>Logs full stdout/stderr to {@code ${OPENCLAW_STATE_DIR}/java-gateway/claude-runs/<runId>.log} and
 * appends a summary line to {@code claude-runs-index.jsonl} for schedulers.
 *
 * <p>Environment:
 *
 * <ul>
 *   <li>{@code OPENCLAW_WORKSPACE_ROOT} (required): absolute root; {@code workdir} must resolve inside it.
 *   <li>{@code OPENCLAW_CLAUDE_CLI} (optional): path to {@code claude} binary (default {@code claude} on PATH).
 *   <li>{@code OPENCLAW_CLAUDE_TASK_TOOL_ENABLED}: set {@code false} to omit tool registration at startup.
 * </ul>
 */
public final class ClaudeTaskAgentTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int DEFAULT_TIMEOUT_SEC = 600;
  private static final int MAX_TIMEOUT_SEC = 7200;
  private static final int MAX_PROMPT_CHARS = 500_000;
  private static final int TAIL_CHARS = 12_000;

  private final Path stateDir;
  private final String claudeBinary;

  public ClaudeTaskAgentTool(ConfigPaths configPaths) {
    this.stateDir = configPaths.getStateDirPath().toAbsolutePath().normalize();
    String bin = System.getenv("OPENCLAW_CLAUDE_CLI");
    this.claudeBinary =
        (bin == null || bin.isBlank()) ? "claude" : bin.trim();
  }

  @Override
  public String name() {
    return "claude_task";
  }

  @Override
  public String description() {
    return "Run a coding task via the local Claude Code CLI (claude --print --permission-mode "
        + "bypassPermissions). Requires OPENCLAW_WORKSPACE_ROOT; workdir must be inside it. Returns "
        + "runId, logPath, exitCode for scheduling follow-up rounds. Install Claude Code CLI and ensure "
        + "`claude` is on PATH (or set OPENCLAW_CLAUDE_CLI).";
  }

  @Override
  public Map<String, Object> parametersSchema() {
    Map<String, Object> prompt =
        Map.of(
            "type",
            "string",
            "description",
            "Task instruction for Claude Code (what to implement or change)");
    Map<String, Object> workdir =
        Map.of(
            "type",
            "string",
            "description",
            "Working directory relative to OPENCLAW_WORKSPACE_ROOT, or absolute path under that root");
    Map<String, Object> timeout =
        Map.of(
            "type",
            "integer",
            "description",
            "Max seconds to wait for the CLI process (default 600, max 7200)");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("prompt", prompt);
    props.put("workdir", workdir);
    props.put("timeoutSec", timeout);
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("type", "object");
    root.put("properties", props);
    root.put("required", List.of("prompt", "workdir"));
    return root;
  }

  @Override
  public String execute(String argumentsJson, ToolExecutionContext ctx) throws Exception {
    String workspaceRootEnv = System.getenv("OPENCLAW_WORKSPACE_ROOT");
    if (workspaceRootEnv == null || workspaceRootEnv.isBlank()) {
      return MAPPER.writeValueAsString(
          Map.of(
              "ok",
              false,
              "error",
              "OPENCLAW_WORKSPACE_ROOT_not_set",
              "message",
              "Set OPENCLAW_WORKSPACE_ROOT to an absolute path; workdir must resolve inside it."));
    }

    Path workspaceRoot = Path.of(workspaceRootEnv.trim()).toAbsolutePath().normalize();
    if (!Files.isDirectory(workspaceRoot)) {
      return MAPPER.writeValueAsString(
          Map.of(
              "ok",
              false,
              "error",
              "workspace_not_a_directory",
              "path",
              workspaceRoot.toString()));
    }

    JsonNode n = MAPPER.readTree(argumentsJson);
    String prompt = n.path("prompt").asText("").trim();
    String workdirRaw = n.path("workdir").asText("").trim();
    if (prompt.isEmpty()) {
      return MAPPER.writeValueAsString(Map.of("ok", false, "error", "prompt_required"));
    }
    if (workdirRaw.isEmpty()) {
      return MAPPER.writeValueAsString(Map.of("ok", false, "error", "workdir_required"));
    }
    if (prompt.length() > MAX_PROMPT_CHARS) {
      return MAPPER.writeValueAsString(
          Map.of("ok", false, "error", "prompt_too_long", "maxChars", MAX_PROMPT_CHARS));
    }

    int timeoutSec = DEFAULT_TIMEOUT_SEC;
    if (n.has("timeoutSec") && n.get("timeoutSec").isNumber()) {
      int t = n.get("timeoutSec").asInt();
      if (t > 0) {
        timeoutSec = Math.min(t, MAX_TIMEOUT_SEC);
      }
    }

    Path workdir;
    try {
      workdir = resolveWorkdir(workspaceRoot, workdirRaw);
    } catch (IllegalArgumentException e) {
      return MAPPER.writeValueAsString(
          Map.of("ok", false, "error", "workdir_outside_workspace", "message", e.getMessage()));
    }

    if (!Files.isDirectory(workdir)) {
      return MAPPER.writeValueAsString(
          Map.of("ok", false, "error", "workdir_not_found", "path", workdir.toString()));
    }

    String runId = UUID.randomUUID().toString();
    Path runsDir = stateDir.resolve("java-gateway").resolve("claude-runs");
    Files.createDirectories(runsDir);
    Path logPath = runsDir.resolve(runId + ".log");

    Files.writeString(
        logPath,
        "=== claude_task runId="
            + runId
            + " workspaceRoot="
            + workspaceRoot
            + " workdir="
            + workdir
            + " ===\n",
        StandardCharsets.UTF_8);

    List<String> cmd = new ArrayList<>();
    cmd.add(claudeBinary);
    cmd.add("--permission-mode");
    cmd.add("bypassPermissions");
    cmd.add("--print");
    cmd.add(prompt);

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(workdir.toFile());
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));

    Process p;
    try {
      p = pb.start();
    } catch (IOException e) {
      return MAPPER.writeValueAsString(
          Map.of(
              "ok",
              false,
              "runId",
              runId,
              "logPath",
              logPath.toString(),
              "error",
              "claude_start_failed",
              "message",
              e.getMessage()));
    }

    boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
    if (!finished) {
      p.destroyForcibly();
      appendIndexLine(
          runId,
          workdir.toString(),
          null,
          logPath,
          true,
          ctx != null ? ctx.sessionKey() : null);
      return MAPPER.writeValueAsString(
          Map.of(
              "ok",
              false,
              "runId",
              runId,
              "logPath",
              logPath.toString(),
              "exitCode",
              null,
              "timedOut",
              true,
              "workdir",
              workdir.toString()));
    }

    int exit = p.exitValue();
    String tail = tailUtf8(logPath, TAIL_CHARS);
    appendIndexLine(
        runId, workdir.toString(), exit, logPath, false, ctx != null ? ctx.sessionKey() : null);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", exit == 0);
    out.put("runId", runId);
    out.put("logPath", logPath.toString());
    out.put("exitCode", exit);
    out.put("timedOut", false);
    out.put("workdir", workdir.toString());
    out.put("logTail", tail);
    return MAPPER.writeValueAsString(out);
  }

  static Path resolveWorkdir(Path workspaceRoot, String workdirRaw) {
    Path raw = Path.of(workdirRaw);
    Path resolved =
        raw.isAbsolute()
            ? raw.toAbsolutePath().normalize()
            : workspaceRoot.resolve(raw).normalize();
    Path rootReal = safeRealPath(workspaceRoot);
    Path workReal = safeRealPath(resolved);
    if (!workReal.startsWith(rootReal)) {
      throw new IllegalArgumentException(
          "workdir must be inside OPENCLAW_WORKSPACE_ROOT (after normalization)");
    }
    return resolved;
  }

  private static Path safeRealPath(Path p) {
    try {
      return p.toRealPath();
    } catch (IOException e) {
      return p.toAbsolutePath().normalize();
    }
  }

  private static String tailUtf8(Path file, int maxChars) {
    try {
      String s = Files.readString(file, StandardCharsets.UTF_8);
      if (s.length() <= maxChars) {
        return s;
      }
      return s.substring(s.length() - maxChars);
    } catch (IOException e) {
      return "";
    }
  }

  private void appendIndexLine(
      String runId,
      String workdir,
      Integer exitCode,
      Path logPath,
      boolean timedOut,
      String sessionKey)
      throws IOException {
    Path indexDir = stateDir.resolve("java-gateway");
    Files.createDirectories(indexDir);
    Path indexFile = indexDir.resolve("claude-runs-index.jsonl");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ts", System.currentTimeMillis());
    row.put("runId", runId);
    row.put("workdir", workdir);
    row.put("logPath", logPath.toString());
    row.put("exitCode", exitCode);
    row.put("timedOut", timedOut);
    if (sessionKey != null && !sessionKey.isBlank()) {
      row.put("sessionKey", sessionKey);
    }
    Files.writeString(
        indexFile,
        MAPPER.writeValueAsString(row) + "\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND);
  }
}
