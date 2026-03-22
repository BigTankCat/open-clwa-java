package ai.openclaw.gateway.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;

/**
 * Resolves bundled skills directory; subset of Node {@code bundled-dir.ts} + {@code
 * OPENCLAW_BUNDLED_SKILLS_DIR}.
 */
public final class BundledSkillsLocator {

  private BundledSkillsLocator() {}

  public static Path resolve(Environment env) {
    if (env != null) {
      String override = env.getProperty("OPENCLAW_BUNDLED_SKILLS_DIR");
      if (override != null && !override.isBlank()) {
        Path p = Path.of(expandTilde(override.trim(), env)).toAbsolutePath().normalize();
        if (looksLikeSkillsDir(p)) {
          return p;
        }
      }
    }
    Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    for (int depth = 0; depth < 8; depth++) {
      Path skills = dir.resolve("skills");
      if (looksLikeSkillsDir(skills)) {
        return skills;
      }
      Path parent = dir.getParent();
      if (parent == null || parent.equals(dir)) {
        break;
      }
      dir = parent;
    }
    return null;
  }

  static String expandTilde(String path, Environment env) {
    if (path.startsWith("~/")) {
      String home =
          env != null
              ? env.getProperty("user.home", System.getProperty("user.home", "."))
              : System.getProperty("user.home", ".");
      return Path.of(home, path.substring(2)).toString();
    }
    if ("~".equals(path)) {
      return env != null
          ? env.getProperty("user.home", System.getProperty("user.home", "."))
          : System.getProperty("user.home", ".");
    }
    return path;
  }

  static boolean looksLikeSkillsDir(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) {
      return false;
    }
    try (var stream = Files.list(dir)) {
      return stream.anyMatch(
          p -> {
            try {
              if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".md")) {
                return true;
              }
              return Files.isDirectory(p)
                  && Files.isRegularFile(p.resolve("SKILL.md"));
            } catch (IOException e) {
              return false;
            }
          });
    } catch (IOException e) {
      return false;
    }
  }
}
