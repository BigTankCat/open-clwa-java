package ai.openclaw.gateway.skills;

import ai.openclaw.config.ConfigLoader;
import ai.openclaw.config.ConfigPaths;
import ai.openclaw.config.ConfigWriter;
import ai.openclaw.gateway.agents.AgentWorkspacePaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class GatewaySkillsService {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ConfigLoader configLoader;
  private final ConfigWriter configWriter;
  private final ConfigPaths paths;
  private final Environment environment;

  public GatewaySkillsService(
      ConfigLoader configLoader, ConfigWriter configWriter, ConfigPaths paths, Environment environment) {
    this.configLoader = configLoader;
    this.configWriter = configWriter;
    this.paths = paths;
    this.environment = environment;
  }

  public Map<String, Object> skillsStatus(String agentIdRaw) throws IllegalArgumentException {
    Map<String, Object> cfg = configLoader.load().getConfig();
    String defaultId = AgentWorkspacePaths.resolveDefaultAgentId(cfg);
    String agentId = defaultId;
    if (agentIdRaw != null && !agentIdRaw.isBlank()) {
      agentId = AgentWorkspacePaths.normalizeAgentId(agentIdRaw);
      List<String> known = AgentWorkspacePaths.listAgentIds(cfg);
      if (!known.contains(agentId)) {
        throw new IllegalArgumentException("unknown agent id \"" + agentIdRaw.trim() + "\"");
      }
    }
    Path workspace =
        AgentWorkspacePaths.resolveAgentWorkspaceDir(
            cfg,
            agentId,
            paths.getStateDirPath(),
            userHome(),
            openclawProfile());
    Map<String, SkillCatalogEntry> merged = mergeSkills(workspace, cfg);
    Path managed = paths.getStateDirPath().resolve("skills").normalize().toAbsolutePath();
    SkillRemoteEligibility remote = SkillRemoteEligibility.none();

    List<Map<String, Object>> skills = new ArrayList<>();
    for (SkillCatalogEntry e : merged.values()) {
      if (!SkillRuntimeFilter.shouldInclude(e, cfg, remote)) {
        continue;
      }
      skills.add(skillStatusRow(e, cfg, remote));
    }
    skills.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("workspaceDir", workspace.toString());
    report.put("managedSkillsDir", managed.toString());
    report.put("skills", skills);
    return report;
  }

  public Map<String, Object> skillsBins() {
    Map<String, Object> cfg = configLoader.load().getConfig();
    SkillRemoteEligibility remote = SkillRemoteEligibility.none();
    TreeSet<String> bins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (Path ws :
        AgentWorkspacePaths.listDistinctWorkspaceDirs(
            cfg, paths.getStateDirPath(), userHome(), openclawProfile())) {
      for (SkillCatalogEntry e : mergeSkills(ws, cfg).values()) {
        if (!SkillRuntimeFilter.shouldInclude(e, cfg, remote)) {
          continue;
        }
        bins.addAll(collectBinsForEntry(e));
      }
    }
    return Map.of("bins", new ArrayList<>(bins));
  }

  public Map<String, Object> skillsInstall(String name, String installId, Long timeoutMs)
      throws Exception {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name required");
    }
    if (installId == null || installId.isBlank()) {
      throw new IllegalArgumentException("installId required");
    }
    Map<String, Object> cfg = configLoader.load().getConfig();
    String defaultId = AgentWorkspacePaths.resolveDefaultAgentId(cfg);
    Path workspace =
        AgentWorkspacePaths.resolveAgentWorkspaceDir(
            cfg, defaultId, paths.getStateDirPath(), userHome(), openclawProfile());
    Map<String, SkillCatalogEntry> merged = mergeSkills(workspace, cfg);
    SkillRemoteEligibility remote = SkillRemoteEligibility.none();
    SkillCatalogEntry skill = merged.get(name.trim().toLowerCase(Locale.ROOT));
    if (skill == null || !SkillRuntimeFilter.shouldInclude(skill, cfg, remote)) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("ok", false);
      err.put("message", "skill not found: " + name.trim());
      err.put("stdout", "");
      err.put("stderr", "");
      err.put("code", null);
      err.put("warnings", List.of());
      return err;
    }
    long to = timeoutMs == null ? 600_000L : Math.max(1_000L, timeoutMs);
    SkillInstallRunner.NodeManager nm = SkillInstallRunner.parseNodeManager(cfg);
    SkillInstallRunner.Result r = SkillInstallRunner.install(skill, installId.trim(), to, nm);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", r.ok());
    out.put("message", r.message());
    out.put("stdout", r.stdout() != null ? r.stdout() : "");
    out.put("stderr", r.stderr() != null ? r.stderr() : "");
    out.put("code", r.code());
    out.put("warnings", r.warnings() != null ? r.warnings() : List.of());
    return out;
  }

  public Map<String, Object> skillsUpdate(
      String skillKey, Boolean enabled, String apiKey, Map<String, String> envPatch)
      throws IllegalArgumentException {
    if (skillKey == null || skillKey.isBlank()) {
      throw new IllegalArgumentException("skillKey required");
    }
    Map<String, Object> cfg = deepCopy(configLoader.load().getConfig());
    @SuppressWarnings("unchecked")
    Map<String, Object> skills =
        cfg.get("skills") instanceof Map<?, ?> sm
            ? new LinkedHashMap<>((Map<String, Object>) sm)
            : new LinkedHashMap<>();
    cfg.put("skills", skills);
    @SuppressWarnings("unchecked")
    Map<String, Object> entries =
        skills.get("entries") instanceof Map<?, ?> em
            ? new LinkedHashMap<>((Map<String, Object>) em)
            : new LinkedHashMap<>();
    skills.put("entries", entries);

    String key = skillKey.trim();
    @SuppressWarnings("unchecked")
    Map<String, Object> current =
        entries.get(key) instanceof Map<?, ?> cur
            ? new LinkedHashMap<>((Map<String, Object>) cur)
            : new LinkedHashMap<>();
    if (enabled != null) {
      current.put("enabled", enabled);
    }
    if (apiKey != null) {
      String t = apiKey.trim();
      if (t.isEmpty()) {
        current.remove("apiKey");
      } else {
        current.put("apiKey", t);
      }
    }
    if (envPatch != null && !envPatch.isEmpty()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> env =
          current.computeIfAbsent("env", (k) -> new LinkedHashMap<String, Object>())
              instanceof Map<?, ?> ev
                  ? new LinkedHashMap<>((Map<String, Object>) ev)
                  : new LinkedHashMap<>();
      for (Map.Entry<String, String> e : envPatch.entrySet()) {
        String k = e.getKey() == null ? "" : e.getKey().trim();
        if (k.isEmpty()) {
          continue;
        }
        String v = e.getValue() == null ? "" : e.getValue().trim();
        if (v.isEmpty()) {
          env.remove(k);
        } else {
          env.put(k, v);
        }
      }
      current.put("env", env);
    }
    entries.put(key, current);
    configWriter.write(cfg);
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("ok", true);
    res.put("skillKey", key);
    res.put("config", current);
    return res;
  }

  private Map<String, SkillCatalogEntry> mergeSkills(Path workspaceDir, Map<String, Object> cfg) {
    Map<String, SkillCatalogEntry> map = new LinkedHashMap<>();
    for (Path extra : SkillInstallRunner.extraSkillDirs(cfg)) {
      WorkspaceSkillsScanner.mergeByName(
          map, WorkspaceSkillsScanner.scanDirectory(extra, "openclaw-extra"));
    }
    Path bundled = BundledSkillsLocator.resolve(environment);
    if (bundled != null) {
      WorkspaceSkillsScanner.mergeByName(
          map, WorkspaceSkillsScanner.scanDirectory(bundled, "openclaw-bundled"));
    }
    Path managed = paths.getStateDirPath().resolve("skills");
    WorkspaceSkillsScanner.mergeByName(
        map, WorkspaceSkillsScanner.scanDirectory(managed, "openclaw-managed"));
    Path personal = Path.of(userHome()).resolve(".agents/skills");
    WorkspaceSkillsScanner.mergeByName(
        map, WorkspaceSkillsScanner.scanDirectory(personal, "agents-skills-personal"));
    Path projectAgents = workspaceDir.resolve(".agents/skills");
    WorkspaceSkillsScanner.mergeByName(
        map, WorkspaceSkillsScanner.scanDirectory(projectAgents, "agents-skills-project"));
    Path wsSkills = workspaceDir.resolve("skills");
    WorkspaceSkillsScanner.mergeByName(
        map, WorkspaceSkillsScanner.scanDirectory(wsSkills, "openclaw-workspace"));
    return map;
  }

  private static Map<String, Object> skillStatusRow(
      SkillCatalogEntry e, Map<String, Object> cfg, SkillRemoteEligibility remote) {
    Map<String, Object> skillCfg = SkillBundledSupport.skillConfigEntry(cfg, e.skillKey());
    boolean disabled = SkillBundledSupport.isExplicitlyDisabled(skillCfg);
    List<String> allowBundled = SkillBundledSupport.allowBundledList(cfg);
    boolean blockedByAllowlist = !SkillBundledSupport.isBundledAllowed(e, allowBundled);
    String primaryEnv = SkillRuntimeFilter.stringOrNull(e.openclaw().get("primaryEnv"));

    SkillRequirementsEvaluator.EvaluationResult eval =
        SkillRequirementsEvaluator.evaluate(
            Boolean.TRUE.equals(e.openclaw().get("always")),
            e.openclaw(),
            ConfigPathUtil.runtimePlatform(),
            ProcessPathProbe::hasBinary,
            remote,
            envName -> SkillRuntimeFilter.isEnvSatisfied(envName, skillCfg, primaryEnv),
            path -> ConfigPathUtil.isConfigPathTruthy(cfg, path));

    boolean eligible = !disabled && !blockedByAllowlist && eval.eligible();

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", e.name());
    row.put("description", e.description());
    row.put("source", e.source());
    row.put("bundled", SkillBundledSupport.isBundledSource(e.source()));
    row.put("filePath", e.filePath().toString());
    row.put("baseDir", e.baseDir().toString());
    row.put("skillKey", e.skillKey());
    String emoji =
        firstNonBlank(
            ConfigPathUtil.normalizeString(e.openclaw().get("emoji")),
            ConfigPathUtil.normalizeString(e.docRoot().get("emoji")));
    if (emoji != null) {
      row.put("emoji", emoji);
    }
    String homepage =
        firstNonBlank(
            ConfigPathUtil.normalizeString(e.openclaw().get("homepage")),
            ConfigPathUtil.normalizeString(e.docRoot().get("homepage")),
            ConfigPathUtil.normalizeString(e.docRoot().get("website")),
            ConfigPathUtil.normalizeString(e.docRoot().get("url")));
    if (homepage != null) {
      row.put("homepage", homepage);
    }
    if (primaryEnv != null) {
      row.put("primaryEnv", primaryEnv);
    }
    row.put("always", Boolean.TRUE.equals(e.openclaw().get("always")));
    row.put("disabled", disabled);
    row.put("blockedByAllowlist", blockedByAllowlist);
    row.put("eligible", eligible);
    row.put("requirements", SkillRequirementsEvaluator.requirementsToMap(eval.required()));
    row.put("missing", SkillRequirementsEvaluator.requirementsToMap(eval.missing()));
    row.put(
        "configChecks",
        SkillRequirementsEvaluator.configChecksToMaps(eval.configChecks()));
    row.put("install", installOptions(e, ConfigPathUtil.runtimePlatform(), cfg));
    return row;
  }

  private static String firstNonBlank(String... vals) {
    for (String v : vals) {
      if (v != null && !v.isBlank()) {
        return v.trim();
      }
    }
    return null;
  }

  private static List<Map<String, Object>> installOptions(
      SkillCatalogEntry e, String platform, Map<String, Object> cfg) {
    List<Map<String, Object>> opts = new ArrayList<>();
    List<String> requiredOs = SkillRequirementsEvaluator.stringList(e.openclaw().get("os"));
    if (!requiredOs.isEmpty() && !requiredOs.contains(platform)) {
      return opts;
    }
    List<Map<String, Object>> specs = e.installSpecs();
    SkillInstallRunner.NodeManager nm = SkillInstallRunner.parseNodeManager(cfg);
    for (int i = 0; i < specs.size(); i++) {
      Map<String, Object> spec = specs.get(i);
      if (!installSpecMatchesPlatform(spec, platform)) {
        continue;
      }
      String id = SkillInstallRunner.installSpecId(spec, i);
      String kind = String.valueOf(spec.getOrDefault("kind", ""));
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("id", id);
      o.put("kind", kind);
      o.put("label", installLabel(spec, kind, nm));
      o.put("bins", listStringBins(spec.get("bins")));
      opts.add(o);
    }
    return opts;
  }

  private static boolean installSpecMatchesPlatform(Map<String, Object> spec, String platform) {
    Object osObj = spec.get("os");
    if (!(osObj instanceof List<?> list) || list.isEmpty()) {
      return true;
    }
    for (Object o : list) {
      if (platform.equals(String.valueOf(o).trim())) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static List<String> listStringBins(Object binsObj) {
    List<String> out = new ArrayList<>();
    if (binsObj instanceof List<?> list) {
      for (Object o : list) {
        if (o != null) {
          String s = String.valueOf(o).trim();
          if (!s.isEmpty()) {
            out.add(s);
          }
        }
      }
    }
    return out;
  }

  private static String installLabel(
      Map<String, Object> spec, String kind, SkillInstallRunner.NodeManager nm) {
    Object label = spec.get("label");
    if (label instanceof String s && !s.isBlank()) {
      return s.trim();
    }
    return switch (kind.toLowerCase(Locale.ROOT)) {
      case "brew" -> "Install " + spec.getOrDefault("formula", "") + " (brew)";
      case "node" ->
          "Install "
              + spec.getOrDefault("package", "")
              + " ("
              + nm.name().toLowerCase(Locale.ROOT)
              + ")";
      case "go" -> "Install " + spec.getOrDefault("module", "") + " (go)";
      case "uv" -> "Install " + spec.getOrDefault("package", "") + " (uv)";
      case "download" -> "Download " + spec.getOrDefault("url", "");
      default -> "Run installer";
    };
  }

  @SuppressWarnings("unchecked")
  private static List<String> collectBinsForEntry(SkillCatalogEntry e) {
    TreeSet<String> bins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    Map<String, Object> oc = e.openclaw();
    Object req = oc.get("requires");
    if (req instanceof Map<?, ?> rm) {
      Object binsObj = rm.get("bins");
      if (binsObj instanceof List<?> bl) {
        for (Object o : bl) {
          if (o != null) {
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) {
              bins.add(s);
            }
          }
        }
      }
      Object anyBins = rm.get("anyBins");
      if (anyBins instanceof List<?> ab) {
        for (Object o : ab) {
          if (o != null) {
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) {
              bins.add(s);
            }
          }
        }
      }
    }
    for (Map<String, Object> spec : e.installSpecs()) {
      Object b = spec.get("bins");
      if (b instanceof List<?> bl) {
        for (Object o : bl) {
          if (o != null) {
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) {
              bins.add(s);
            }
          }
        }
      }
    }
    return new ArrayList<>(bins);
  }

  private String userHome() {
    return environment.getProperty("user.home", System.getProperty("user.home", "."));
  }

  private String openclawProfile() {
    String p = environment.getProperty("OPENCLAW_PROFILE");
    return p != null ? p : "";
  }

  private static Map<String, Object> deepCopy(Map<String, Object> in) {
    try {
      return JSON.readValue(JSON.writeValueAsBytes(in), new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("config copy failed", e);
    }
  }
}
