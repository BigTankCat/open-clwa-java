package ai.openclaw.gateway.agents;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves per-agent workspace directories; aligned with Node {@code agent-scope.ts}. */
public final class AgentWorkspacePaths {

  private AgentWorkspacePaths() {}

  public static String normalizeAgentId(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.trim().toLowerCase();
  }

  @SuppressWarnings("unchecked")
  public static List<String> listAgentIds(Map<String, Object> cfg) {
    List<String> ids = new ArrayList<>();
    if (cfg == null) {
      return ids;
    }
    Object agentsRoot = cfg.get("agents");
    if (!(agentsRoot instanceof Map<?, ?> ar)) {
      return ids;
    }
    Object listObj = ar.get("list");
    if (!(listObj instanceof List<?> list)) {
      return ids;
    }
    for (Object entry : list) {
      if (!(entry instanceof Map<?, ?> em)) {
        continue;
      }
      Object id = em.get("id");
      if (id instanceof String s && !s.isBlank()) {
        ids.add(normalizeAgentId(s));
      }
    }
    if (ids.isEmpty()) {
      ids.add("default");
    }
    return ids;
  }

  public static String resolveDefaultAgentId(Map<String, Object> cfg) {
    List<String> all = new ArrayList<>();
    String firstDefault = null;
    if (cfg != null) {
      Object agentsRoot = cfg.get("agents");
      if (agentsRoot instanceof Map<?, ?> ar) {
        Object listObj = ar.get("list");
        if (listObj instanceof List<?> list) {
          for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> em)) {
              continue;
            }
            Object id = em.get("id");
            if (!(id instanceof String s) || s.isBlank()) {
              continue;
            }
            String nid = normalizeAgentId(s);
            all.add(nid);
            if (Boolean.TRUE.equals(em.get("default")) && firstDefault == null) {
              firstDefault = nid;
            }
          }
        }
      }
    }
    if (firstDefault != null) {
      return firstDefault;
    }
    if (!all.isEmpty()) {
      return all.get(0);
    }
    return "default";
  }

  public static Path resolveAgentWorkspaceDir(
      Map<String, Object> cfg, String agentId, Path stateDir, String userHome, String openclawProfile) {
    String id = normalizeAgentId(agentId);
    if (id.isEmpty()) {
      id = "default";
    }
    if (cfg != null) {
      Object agentsRoot = cfg.get("agents");
      if (agentsRoot instanceof Map<?, ?> ar) {
        Object listObj = ar.get("list");
        if (listObj instanceof List<?> list) {
          for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> em)) {
              continue;
            }
            Object idRaw = em.get("id");
            if (!(idRaw instanceof String sid) || !id.equals(normalizeAgentId(sid))) {
              continue;
            }
            Object ws = em.get("workspace");
            if (ws instanceof String wss && !wss.isBlank()) {
              return Paths.get(expandUser(wss.trim(), userHome)).normalize().toAbsolutePath();
            }
          }
        }
        Object defs = ar.get("defaults");
        String defId = resolveDefaultAgentId(cfg);
        if (id.equals(defId) && defs instanceof Map<?, ?> dm) {
          Object ws = dm.get("workspace");
          if (ws instanceof String wss && !wss.isBlank()) {
            return Paths.get(expandUser(wss.trim(), userHome)).normalize().toAbsolutePath();
          }
        }
      }
    }
    if (id.equals(resolveDefaultAgentId(cfg))) {
      return resolveDefaultAgentWorkspace(stateDir, userHome, openclawProfile);
    }
    return stateDir.resolve("workspace-" + id).normalize().toAbsolutePath();
  }

  public static Path resolveDefaultAgentWorkspace(Path stateDir, String userHome, String profile) {
    String p = profile == null ? "" : profile.trim();
    if (!p.isEmpty() && !p.equalsIgnoreCase("default")) {
      return Paths.get(expandUser("~/.openclaw/workspace-" + p, userHome))
          .normalize()
          .toAbsolutePath();
    }
    return Paths.get(expandUser("~/.openclaw/workspace", userHome)).normalize().toAbsolutePath();
  }

  public static String expandUser(String path, String userHome) {
    if (path == null) {
      return "";
    }
    String home = userHome != null ? userHome : System.getProperty("user.home", ".");
    if (path.equals("~")) {
      return home;
    }
    if (path.startsWith("~/")) {
      return Paths.get(home, path.substring(2)).toString();
    }
    return Paths.get(path).toAbsolutePath().normalize().toString();
  }

  @SuppressWarnings("unchecked")
  public static List<Path> listDistinctWorkspaceDirs(
      Map<String, Object> cfg, Path stateDir, String userHome, String openclawProfile) {
    Set<Path> set = new LinkedHashSet<>();
    for (String agentId : listAgentIds(cfg)) {
      set.add(
          resolveAgentWorkspaceDir(cfg, agentId, stateDir, userHome, openclawProfile)
              .normalize()
              .toAbsolutePath());
    }
    return List.copyOf(set);
  }
}
