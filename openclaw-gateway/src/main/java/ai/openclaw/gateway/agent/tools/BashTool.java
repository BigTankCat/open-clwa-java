package ai.openclaw.gateway.agent.tools;

import ai.openclaw.agent.runtime.ToolExecutionContext;
import ai.openclaw.agent.tools.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell command execution tool.
 * Supports command, timeout (seconds), workdir, and background mode.
 */
public final class BashTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;
  private static final int MAX_TIMEOUT_SECONDS = 300;

  @Override
  public String name() {
    return "bash";
  }

  @Override
  public String description() {
    return "Execute a shell command and return its stdout/stderr output. " +
        "Supports timeout (seconds), workdir (working directory), and background mode (returns immediately with PID).";
  }

  @Override
  public Map<String, Object> parametersSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("command", Map.of(
        "type", "string",
        "description", "Shell command to execute (use && for chaining, || for error handling)"));
    props.put("timeout", Map.of(
        "type", "integer",
        "description", "Timeout in seconds (default: 30, max: 300)"));
    props.put("workdir", Map.of(
        "type", "string",
        "description", "Working directory for the command (defaults to agent workspace or current dir)"));
    props.put("background", Map.of(
        "type", "boolean",
        "description", "If true, run in background and return immediately with PID"));
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("type", "object");
    root.put("properties", props);
    root.put("required", List.of("command"));
    return root;
  }

  @Override
  public String execute(String argumentsJson, ToolExecutionContext ctx) throws Exception {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return MAPPER.writeValueAsString(Map.of("error", "missing arguments"));
    }
    JsonNode args = MAPPER.readTree(argumentsJson);
    String command = args.has("command") ? args.get("command").asText() : null;
    if (command == null || command.isBlank()) {
      return MAPPER.writeValueAsString(Map.of("error", "command is required"));
    }

    int timeout = DEFAULT_TIMEOUT_SECONDS;
    if (args.has("timeout") && args.get("timeout").isNumber()) {
      timeout = args.get("timeout").asInt();
      timeout = Math.max(1, Math.min(timeout, MAX_TIMEOUT_SECONDS));
    }

    String workdir = null;
    if (args.has("workdir") && !args.get("workdir").isNull()) {
      workdir = args.get("workdir").asText();
    }

    boolean background = args.has("background") && args.get("background").asBoolean(false);

    return background
        ? runBackground(command, workdir, timeout)
        : runForeground(command, workdir, timeout);
  }

  private String runForeground(String command, String workdir, int timeoutSecs) throws Exception {
    try {
      ProcessBuilder pb = new ProcessBuilder("/bin/zsh", "-c", command);
      pb.redirectErrorStream(false);
      if (workdir != null) {
        pb.directory(Path.of(workdir).toFile());
      }
      pb.environment().put("PATH", System.getenv("PATH"));
      long start = System.currentTimeMillis();
      Process process = pb.start();

      String output = readStream(process.getInputStream());
      String errorOutput = readStream(process.getErrorStream());
      boolean finished = process.waitFor(timeoutSecs, TimeUnit.SECONDS);
      long elapsedMs = System.currentTimeMillis() - start;

      if (!finished) {
        process.destroyForcibly();
        return MAPPER.writeValueAsString(Map.of(
            "error", "command timed out after " + timeoutSecs + " seconds",
            "elapsedMs", elapsedMs,
            "timedOut", true));
      }

      int exitCode = process.exitValue();
      return MAPPER.writeValueAsString(Map.of(
          "stdout", output,
          "stderr", errorOutput,
          "exitCode", exitCode,
          "elapsedMs", elapsedMs,
          "timedOut", false));
    } catch (Exception e) {
      return MAPPER.writeValueAsString(Map.of("error", "bash execution failed: " + e.getMessage()));
    }
  }

  private String runBackground(String command, String workdir, int timeoutSecs) throws Exception {
    try {
      ProcessBuilder pb = new ProcessBuilder("/bin/zsh", "-c", command);
      if (workdir != null) {
        pb.directory(Path.of(workdir).toFile());
      }
      pb.environment().put("PATH", System.getenv("PATH"));
      Process process = pb.start();
      long pid = process.toHandle().pid();
      return MAPPER.writeValueAsString(Map.of(
          "pid", pid,
          "message", "Background process started (timeout: " + timeoutSecs + "s)",
          "note", "Use bash with command: wait " + pid + " && echo done or kill " + pid));
    } catch (Exception e) {
      return MAPPER.writeValueAsString(Map.of("error", "background start failed: " + e.getMessage()));
    }
  }

  private String readStream(java.io.InputStream stream) throws java.io.IOException {
    try (BufferedReader reader = new BufferedReader(new StringReader(
        new String(stream.readAllBytes(), StandardCharsets.UTF_8)))) {
      StringBuilder sb = new StringBuilder();
      String line;
      boolean first = true;
      while ((line = reader.readLine()) != null) {
        if (!first) sb.append("\n");
        first = false;
        sb.append(line);
      }
      return sb.toString();
    }
  }
}
