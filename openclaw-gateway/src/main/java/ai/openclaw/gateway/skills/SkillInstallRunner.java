package ai.openclaw.gateway.skills;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs installers declared in skill OpenClaw metadata ({@code install[]} entries). */
public final class SkillInstallRunner {

  public record Result(
      boolean ok,
      String message,
      Integer code,
      String stdout,
      String stderr,
      List<String> warnings) {}

  public enum NodeManager {
    NPM,
    PNPM,
    YARN,
    BUN
  }

  private SkillInstallRunner() {}

  public static Result install(
      SkillCatalogEntry skill, String installId, long timeoutMs, NodeManager nodeManager)
      throws IOException, InterruptedException {
    List<String> scanWarnings = collectInstallScanWarnings(skill);
    Map<String, Object> spec = findInstallSpec(skill, installId);
    if (spec == null) {
      return new Result(false, "install spec not found for id: " + installId, null, "", "", scanWarnings);
    }
    String kind = string(spec.get("kind"), "").toLowerCase(Locale.ROOT);
    List<String> argv = buildArgv(kind, spec, nodeManager);
    if (argv == null) {
      if ("download".equals(kind)) {
        return attachWarnings(runDownload(spec, skill.baseDir(), timeoutMs), scanWarnings);
      }
      return new Result(
          false, "unsupported or incomplete install kind: " + kind, null, "", "", scanWarnings);
    }
    return attachWarnings(runProcess(argv, timeoutMs, skill.baseDir()), scanWarnings);
  }

  static List<String> collectInstallScanWarnings(SkillCatalogEntry skill) {
    List<String> warnings = new ArrayList<>();
    try {
      SkillSecurityScanner.Summary summary = SkillSecurityScanner.scanDirectory(skill.baseDir());
      if (summary.critical() > 0) {
        List<String> details =
            summary.findings().stream()
                .filter(f -> "critical".equals(f.severity()))
                .map(
                    f ->
                        f.message()
                            + " ("
                            + f.file()
                            + ":"
                            + f.line()
                            + ")")
                .toList();
        warnings.add(
            "WARNING: Skill \""
                + skill.name()
                + "\" contains dangerous code patterns: "
                + String.join("; ", details));
      } else if (summary.warn() > 0) {
        warnings.add(
            "Skill \""
                + skill.name()
                + "\" has "
                + summary.warn()
                + " suspicious code pattern(s). Run \"openclaw security audit --deep\" for details.");
      }
    } catch (Exception e) {
      warnings.add(
          "Skill \""
              + skill.name()
              + "\" code safety scan failed ("
              + e.getMessage()
              + "). Installation continues; run \"openclaw security audit --deep\" after install.");
    }
    return warnings;
  }

  static Result attachWarnings(Result base, List<String> scanWarnings) {
    if (scanWarnings.isEmpty()) {
      return base;
    }
    List<String> merged = new ArrayList<>(scanWarnings);
    merged.addAll(base.warnings());
    return new Result(
        base.ok(), base.message(), base.code(), base.stdout(), base.stderr(), List.copyOf(merged));
  }

  static Map<String, Object> findInstallSpec(SkillCatalogEntry skill, String installId) {
    List<Map<String, Object>> specs = skill.installSpecs();
    for (int i = 0; i < specs.size(); i++) {
      Map<String, Object> s = specs.get(i);
      if (installId.equals(installSpecId(s, i))) {
        return s;
      }
    }
    return null;
  }

  static String installSpecId(Map<String, Object> spec, int index) {
    Object id = spec.get("id");
    if (id instanceof String s && !s.isBlank()) {
      return s.trim();
    }
    String kind = string(spec.get("kind"), "unknown");
    return kind + "-" + index;
  }

  static List<String> buildArgv(String kind, Map<String, Object> spec, NodeManager nodeManager) {
    return switch (kind) {
      case "brew" -> {
        String formula = string(spec.get("formula"), "");
        if (formula.isBlank()) {
          yield null;
        }
        yield List.of("brew", "install", formula);
      }
      case "node" -> {
        String pkg = string(spec.get("package"), "");
        if (pkg.isBlank()) {
          yield null;
        }
        yield switch (nodeManager) {
          case PNPM -> List.of("pnpm", "add", "-g", "--ignore-scripts", pkg);
          case YARN -> List.of("yarn", "global", "add", "--ignore-scripts", pkg);
          case BUN -> List.of("bun", "add", "-g", "--ignore-scripts", pkg);
          case NPM -> List.of("npm", "install", "-g", "--ignore-scripts", pkg);
        };
      }
      case "go" -> {
        String mod = string(spec.get("module"), "");
        if (mod.isBlank()) {
          yield null;
        }
        yield List.of("go", "install", mod);
      }
      case "uv" -> {
        String pkg = string(spec.get("package"), "");
        if (pkg.isBlank()) {
          yield null;
        }
        yield List.of("uv", "tool", "install", pkg);
      }
      case "download" -> null;
      default -> null;
    };
  }

  static Result runDownload(Map<String, Object> spec, Path baseDir, long timeoutMs)
      throws IOException, InterruptedException {
    String url = string(spec.get("url"), "");
    if (url.isBlank()) {
      return new Result(false, "download install missing url", null, "", "", List.of());
    }
    URI uri;
    try {
      uri = URI.create(url);
    } catch (Exception e) {
      return new Result(false, "invalid download url", null, "", "", List.of());
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      return new Result(false, "download url must use https", null, "", "", List.of());
    }
    String path = uri.getPath();
    String name = path == null || path.isBlank() ? "download.bin" : Path.of(path).getFileName().toString();
    if (name.isBlank() || name.equals("/")) {
      name = "download.bin";
    }
    Path target = baseDir.resolve(name).normalize().toAbsolutePath();
    if (!target.startsWith(baseDir.normalize().toAbsolutePath())) {
      return new Result(false, "unsafe download target path", null, "", "", List.of());
    }
    Files.createDirectories(baseDir);
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(Math.min(timeoutMs, 600_000)))
            .GET()
            .build();
    HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    if (res.statusCode() / 100 != 2) {
      return new Result(
          false, "download failed: HTTP " + res.statusCode(), res.statusCode(), "", "", List.of());
    }
    try (InputStream in = res.body()) {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return new Result(true, "downloaded " + name, 0, "", "", List.of());
  }

  static Result runProcess(List<String> argv, long timeoutMs, Path cwd)
      throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(argv);
    pb.directory(cwd.toFile());
    pb.redirectErrorStream(false);
    Process p = pb.start();
    boolean finished = p.waitFor(Math.max(1_000L, timeoutMs), TimeUnit.MILLISECONDS);
    if (!finished) {
      p.destroyForcibly();
      return new Result(false, "install timed out", null, "", "timeout", List.of());
    }
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    int code = p.exitValue();
    boolean ok = code == 0;
    return new Result(ok, ok ? "ok" : "installer exited " + code, code, out, err, List.of());
  }

  static String string(Object v, String d) {
    if (v instanceof String s) {
      return s.trim();
    }
    return d;
  }

  public static NodeManager parseNodeManager(Map<String, Object> cfg) {
    if (cfg == null) {
      return NodeManager.NPM;
    }
    Object skills = cfg.get("skills");
    if (!(skills instanceof Map<?, ?> sm)) {
      return NodeManager.NPM;
    }
    Object inst = sm.get("install");
    if (!(inst instanceof Map<?, ?> im)) {
      return NodeManager.NPM;
    }
    Object raw = im.get("nodeManager");
    if (!(raw instanceof String s)) {
      return NodeManager.NPM;
    }
    return switch (s.trim().toLowerCase(Locale.ROOT)) {
      case "pnpm" -> NodeManager.PNPM;
      case "yarn" -> NodeManager.YARN;
      case "bun" -> NodeManager.BUN;
      default -> NodeManager.NPM;
    };
  }

  public static List<Path> extraSkillDirs(Map<String, Object> cfg) {
    List<Path> dirs = new ArrayList<>();
    if (cfg == null) {
      return dirs;
    }
    Object skills = cfg.get("skills");
    if (!(skills instanceof Map<?, ?> sm)) {
      return dirs;
    }
    Object load = sm.get("load");
    if (!(load instanceof Map<?, ?> lm)) {
      return dirs;
    }
    Object ed = lm.get("extraDirs");
    if (!(ed instanceof List<?> list)) {
      return dirs;
    }
    for (Object o : list) {
      if (o instanceof String s && !s.isBlank()) {
        dirs.add(Path.of(s.trim()).toAbsolutePath().normalize());
      }
    }
    return dirs;
  }
}
