package ai.openclaw.gateway.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight port of Node {@code security/skill-scanner.ts} (subset of line + source rules) for
 * pre-install warnings.
 */
public final class SkillSecurityScanner {

  public record Finding(String ruleId, String severity, String file, int line, String message) {}

  public record Summary(int scannedFiles, int critical, int warn, int info, List<Finding> findings) {}

  private static final int DEFAULT_MAX_FILES = 500;
  private static final int DEFAULT_MAX_FILE_BYTES = 1024 * 1024;

  private static final Set<String> SCANNABLE_EXT =
      Set.of(".js", ".ts", ".mjs", ".cjs", ".mts", ".cts", ".jsx", ".tsx");

  private static final Pattern DANGEROUS_EXEC =
      Pattern.compile("\\b(exec|execSync|spawn|spawnSync|execFile|execFileSync)\\s*\\(");
  private static final Pattern CHILD_PROCESS = Pattern.compile("child_process");
  private static final Pattern DYNAMIC_EVAL =
      Pattern.compile("\\beval\\s*\\(|new\\s+Function\\s*\\(");
  private static final Pattern CRYPTO =
      Pattern.compile("stratum\\+tcp|stratum\\+ssl|coinhive|cryptonight|xmrig", Pattern.CASE_INSENSITIVE);
  private static final Pattern WEBSOCKET_PORT =
      Pattern.compile("new\\s+WebSocket\\s*\\(\\s*[\"']wss?://[^\"']*:(\\d+)");
  private static final Set<Integer> STANDARD_PORTS = Set.of(80, 443, 8080, 8443, 3000);

  private static final Pattern READ_FILE = Pattern.compile("readFileSync|readFile");
  private static final Pattern NETWORK_CTX = Pattern.compile("\\bfetch\\b|\\bpost\\b|http\\.request", Pattern.CASE_INSENSITIVE);
  private static final Pattern HEX_OBF = Pattern.compile("(\\\\x[0-9a-fA-F]{2}){6,}");
  private static final Pattern B64_OBF =
      Pattern.compile("(?:atob|Buffer\\.from)\\s*\\(\\s*[\"'][A-Za-z0-9+/=]{200,}[\"']");
  private static final Pattern PROCESS_ENV = Pattern.compile("process\\.env");

  private SkillSecurityScanner() {}

  public static Summary scanDirectory(Path root) {
    return scanDirectory(root, DEFAULT_MAX_FILES, DEFAULT_MAX_FILE_BYTES);
  }

  public static Summary scanDirectory(Path root, int maxFiles, int maxFileBytes) {
    List<Path> files = new ArrayList<>();
    if (root == null || !Files.isDirectory(root)) {
      return new Summary(0, 0, 0, 0, List.of());
    }
    try {
      walkLimited(root, files, maxFiles, new HashSet<>());
    } catch (IOException e) {
      return new Summary(0, 0, 0, 0, List.of());
    }
    List<Finding> findings = new ArrayList<>();
    int critical = 0;
    int warn = 0;
    for (Path f : files) {
      try {
        long sz = Files.size(f);
        if (sz > maxFileBytes) {
          continue;
        }
        String src = Files.readString(f, StandardCharsets.UTF_8);
        for (Finding fd : scanSource(src, f.toString())) {
          findings.add(fd);
          switch (fd.severity()) {
            case "critical" -> critical++;
            case "warn" -> warn++;
            default -> { }
          }
        }
      } catch (Exception ignored) {
        // skip file
      }
    }
    return new Summary(files.size(), critical, warn, 0, List.copyOf(findings));
  }

  static void walkLimited(Path dir, List<Path> out, int maxFiles, Set<Path> visited)
      throws IOException {
    dir = dir.toAbsolutePath().normalize();
    if (!visited.add(dir) || out.size() >= maxFiles) {
      return;
    }
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (var stream = Files.list(dir)) {
      List<Path> batch = stream.toList();
      for (Path p : batch) {
        if (out.size() >= maxFiles) {
          break;
        }
        String name = p.getFileName().toString();
        if (name.startsWith(".") || "node_modules".equals(name)) {
          continue;
        }
        if (Files.isDirectory(p)) {
          walkLimited(p, out, maxFiles, visited);
        } else if (isScannable(p) && Files.isRegularFile(p)) {
          out.add(p);
        }
      }
    }
  }

  static boolean isScannable(Path p) {
    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
    int dot = n.lastIndexOf('.');
    if (dot < 0) {
      return false;
    }
    return SCANNABLE_EXT.contains(n.substring(dot));
  }

  public static List<Finding> scanSource(String source, String filePath) {
    List<Finding> findings = new ArrayList<>();
    if (source == null) {
      return findings;
    }
    String[] lines = source.split("\r?\n", -1);
    Set<String> lineRuleHit = new HashSet<>();

    addLineRule(
        findings,
        lineRuleHit,
        "dangerous-exec",
        "critical",
        "Shell command execution detected (child_process)",
        DANGEROUS_EXEC,
        lines,
        filePath,
        CHILD_PROCESS,
        source);
    addLineRule(
        findings,
        lineRuleHit,
        "dynamic-code-execution",
        "critical",
        "Dynamic code execution detected",
        DYNAMIC_EVAL,
        lines,
        filePath,
        null,
        source);
    addLineRule(
        findings,
        lineRuleHit,
        "crypto-mining",
        "critical",
        "Possible crypto-mining reference detected",
        CRYPTO,
        lines,
        filePath,
        null,
        source);

    for (int i = 0; i < lines.length; i++) {
      Matcher m = WEBSOCKET_PORT.matcher(lines[i]);
      if (m.find()) {
        try {
          int port = Integer.parseInt(m.group(1));
          if (!STANDARD_PORTS.contains(port)) {
            findings.add(
                new Finding(
                    "suspicious-network",
                    "warn",
                    filePath,
                    i + 1,
                    "WebSocket connection to non-standard port"));
            break;
          }
        } catch (NumberFormatException ignored) {
          // skip
        }
      }
    }

    addSourceRule(
        findings,
        "potential-exfiltration",
        "warn",
        "File read combined with network send — possible data exfiltration",
        READ_FILE,
        NETWORK_CTX,
        source,
        lines,
        filePath);
    addSourceRule(
        findings,
        "obfuscated-code",
        "warn",
        "Hex-encoded string sequence detected (possible obfuscation)",
        HEX_OBF,
        null,
        source,
        lines,
        filePath);
    addSourceRule(
        findings,
        "obfuscated-code",
        "warn",
        "Large base64 payload with decode call detected (possible obfuscation)",
        B64_OBF,
        null,
        source,
        lines,
        filePath);
    addSourceRule(
        findings,
        "env-harvesting",
        "critical",
        "Environment variable access combined with network send — possible credential harvesting",
        PROCESS_ENV,
        NETWORK_CTX,
        source,
        lines,
        filePath);

    return findings;
  }

  private static void addLineRule(
      List<Finding> findings,
      Set<String> hitIds,
      String ruleId,
      String severity,
      String message,
      Pattern pattern,
      String[] lines,
      String filePath,
      Pattern requiresContext,
      String fullSource) {
    if (hitIds.contains(ruleId)) {
      return;
    }
    if (requiresContext != null && !requiresContext.matcher(fullSource).find()) {
      return;
    }
    for (int i = 0; i < lines.length; i++) {
      if (pattern.matcher(lines[i]).find()) {
        findings.add(new Finding(ruleId, severity, filePath, i + 1, message));
        hitIds.add(ruleId);
        return;
      }
    }
  }

  private static void addSourceRule(
      List<Finding> findings,
      String ruleId,
      String severity,
      String message,
      Pattern pattern,
      Pattern requiresContext,
      String source,
      String[] lines,
      String filePath) {
    if (!pattern.matcher(source).find()) {
      return;
    }
    if (requiresContext != null && !requiresContext.matcher(source).find()) {
      return;
    }
    int line = 1;
    String evidence = source.length() > 120 ? source.substring(0, 120) : source;
    for (int i = 0; i < lines.length; i++) {
      if (pattern.matcher(lines[i]).find()) {
        line = i + 1;
        evidence = lines[i].trim();
        break;
      }
    }
    findings.add(new Finding(ruleId, severity, filePath, line, message + " — " + truncate(evidence, 120)));
  }

  private static String truncate(String s, int max) {
    if (s.length() <= max) {
      return s;
    }
    return s.substring(0, max) + "…";
  }
}
