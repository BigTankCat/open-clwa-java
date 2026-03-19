package ai.openclaw.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves state dir and config file path; aligned with Node src/config/paths.ts.
 * State dir default: ~/.openclaw (or OPENCLAW_STATE_DIR).
 * Config file default: ${stateDir}/openclaw.json (or OPENCLAW_CONFIG_PATH).
 */
public final class ConfigPaths {

  private static final String NEW_STATE_DIRNAME = ".openclaw";
  private static final String CONFIG_FILENAME = "openclaw.json";

  private final String stateDir;
  private final String configPath;

  public ConfigPaths(Map<String, String> env) {
    this.stateDir = resolveStateDir(env);
    this.configPath = resolveConfigPath(env, stateDir);
  }

  /** State directory for mutable data (sessions, credentials, config). */
  public String getStateDir() {
    return stateDir;
  }

  /** Absolute path to the main config file. */
  public String getConfigPath() {
    return configPath;
  }

  public Path getStateDirPath() {
    return Paths.get(stateDir);
  }

  public Path getConfigFilePath() {
    return Paths.get(configPath);
  }

  private static String resolveStateDir(Map<String, String> env) {
    String override =
        firstNonEmpty(
            getEnv(env, "OPENCLAW_STATE_DIR"),
            getEnv(env, "CLAWDBOT_STATE_DIR"));
    if (override != null && !override.isBlank()) {
      return resolveUserPath(override.trim(), env);
    }
    return newStateDir(homedir(env));
  }

  private static String resolveConfigPath(Map<String, String> env, String stateDir) {
    String override = getEnv(env, "OPENCLAW_CONFIG_PATH");
    if (override != null && !override.isBlank()) {
      return resolveUserPath(override.trim(), env);
    }
    return Paths.get(stateDir, CONFIG_FILENAME).toString();
  }

  private static String newStateDir(String homedir) {
    return Paths.get(homedir, NEW_STATE_DIRNAME).toString();
  }

  private static String homedir(Map<String, String> env) {
    String home = getEnv(env, "OPENCLAW_HOME");
    if (home != null && !home.isBlank()) {
      return home.trim();
    }
    String userHome = getEnv(env, "user.home");
    if (userHome != null && !userHome.isBlank()) {
      return userHome;
    }
    return System.getProperty("user.home", ".");
  }

  /** Resolve path that may be relative to home (e.g. ~/.openclaw). */
  private static String resolveUserPath(String path, Map<String, String> env) {
    if (path.startsWith("~/") || path.equals("~")) {
      String home = homedir(env);
      return path.equals("~") ? home : Paths.get(home, path.substring(2)).toString();
    }
    return Paths.get(path).toAbsolutePath().normalize().toString();
  }

  private static String getEnv(Map<String, String> env, String key) {
    if (env == null) {
      return null;
    }
    String v = env.get(key);
    if (v != null) {
      return v;
    }
    if ("user.home".equals(key)) {
      return System.getProperty("user.home");
    }
    return System.getenv(key);
  }

  private static String firstNonEmpty(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v.trim();
      }
    }
    return null;
  }

  /** Build from current process environment. */
  public static ConfigPaths fromEnvironment() {
    return new ConfigPaths(System.getenv());
  }
}
