package ai.openclaw.gateway.skills;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Best-effort {@code PATH} lookup; aligned with Node {@code hasBinary}. */
public final class ProcessPathProbe {

  private static final boolean IS_WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

  private ProcessPathProbe() {}

  public static boolean hasBinary(String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    String trimmed = name.trim();
    if (trimmed.contains("/") || trimmed.contains("\\")) {
      return Files.isExecutable(Path.of(trimmed));
    }
    if (IS_WINDOWS) {
      return probeWindows(trimmed);
    }
    return probeUnix(trimmed);
  }

  private static boolean probeUnix(String name) {
    try {
      ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "command -v \"" + escapeShell(name) + "\" >/dev/null 2>&1");
      pb.redirectErrorStream(true);
      Process p = pb.start();
      boolean done = p.waitFor(2, TimeUnit.SECONDS);
      if (!done) {
        p.destroyForcibly();
        return false;
      }
      return p.exitValue() == 0;
    } catch (Exception e) {
      return pathWalk(name);
    }
  }

  private static String escapeShell(String s) {
    return s.replace("\"", "\\\"");
  }

  private static boolean probeWindows(String name) {
    try {
      ProcessBuilder pb = new ProcessBuilder("where", name);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      boolean done = p.waitFor(3, TimeUnit.SECONDS);
      if (!done) {
        p.destroyForcibly();
        return false;
      }
      return p.exitValue() == 0;
    } catch (Exception e) {
      return pathWalk(name);
    }
  }

  private static boolean pathWalk(String name) {
    String pathEnv = System.getenv("PATH");
    if (pathEnv == null || pathEnv.isBlank()) {
      return false;
    }
    String sep = File.pathSeparator;
    for (String dir : pathEnv.split(sep)) {
      if (dir.isBlank()) {
        continue;
      }
      Path base = Path.of(dir.trim());
      if (IS_WINDOWS) {
        for (String ext : new String[] {"", ".exe", ".cmd", ".bat", ".com"}) {
          Path candidate = base.resolve(name + ext);
          if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
            return true;
          }
        }
      } else {
        Path candidate = base.resolve(name);
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
          return true;
        }
      }
    }
    return false;
  }
}
