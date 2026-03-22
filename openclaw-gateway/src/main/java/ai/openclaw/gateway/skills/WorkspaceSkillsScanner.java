package ai.openclaw.gateway.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Loads skills from workspace-style directories by scanning for {@code SKILL.md} files. */
public final class WorkspaceSkillsScanner {

  private static final int MAX_SKILL_FILE_BYTES = 256_000;
  private static final Yaml YAML = new Yaml();

  private WorkspaceSkillsScanner() {}

  public static List<SkillCatalogEntry> scanDirectory(Path root, String sourceTag) {
    List<SkillCatalogEntry> out = new ArrayList<>();
    if (root == null || !Files.isDirectory(root)) {
      return out;
    }
    try (var stream = Files.walk(root)) {
      stream
          .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().equalsIgnoreCase("SKILL.md"))
          .forEach(
              p -> {
                try {
                  SkillCatalogEntry e = parseSkillFile(p, sourceTag);
                  if (e != null && e.name() != null && !e.name().isBlank()) {
                    out.add(e);
                  }
                } catch (Exception ignored) {
                  // skip malformed
                }
              });
    } catch (IOException e) {
      return out;
    }
    return out;
  }

  static SkillCatalogEntry parseSkillFile(Path filePath, String sourceTag) throws IOException {
    long size = Files.size(filePath);
    if (size > MAX_SKILL_FILE_BYTES) {
      return null;
    }
    String raw = Files.readString(filePath, StandardCharsets.UTF_8);
    String fm = extractYamlFrontmatter(raw);
    if (fm == null || fm.isBlank()) {
      return null;
    }
    Object loaded = YAML.load(fm);
    if (!(loaded instanceof Map<?, ?> root)) {
      return null;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) root;
    String name = stringVal(map.get("name"));
    if (name == null) {
      return null;
    }
    String description = stringVal(map.get("description"));
    if (description == null) {
      description = "";
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> openclaw =
        map.get("openclaw") instanceof Map<?, ?> oc ? (Map<String, Object>) oc : Map.of();
    String skillKey = stringVal(openclaw.get("skillKey"));
    if (skillKey == null || skillKey.isBlank()) {
      skillKey = name.trim();
    }
    List<Map<String, Object>> installSpecs = new ArrayList<>();
    Object inst = openclaw.get("install");
    if (inst instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof Map<?, ?> im) {
          installSpecs.add(new LinkedHashMap<>((Map<String, Object>) im));
        }
      }
    }
    Path baseDir = filePath.getParent() != null ? filePath.getParent() : filePath.toAbsolutePath();
    Map<String, Object> docCopy = new LinkedHashMap<>(map);
    return new SkillCatalogEntry(
        name.trim(),
        description,
        sourceTag,
        filePath.toAbsolutePath().normalize(),
        baseDir.toAbsolutePath().normalize(),
        skillKey.trim(),
        docCopy,
        openclaw,
        List.copyOf(installSpecs));
  }

  static String extractYamlFrontmatter(String content) {
    if (content == null || !content.stripLeading().startsWith("---")) {
      return null;
    }
    int start = content.indexOf("---");
    if (start < 0) {
      return null;
    }
    int from = start + 3;
    if (from < content.length() && content.charAt(from) == '\r') {
      from++;
    }
    if (from < content.length() && content.charAt(from) == '\n') {
      from++;
    }
    int end = content.indexOf("\n---", from);
    if (end < 0) {
      end = content.indexOf("\r\n---", from);
    }
    if (end < 0) {
      return null;
    }
    return content.substring(from, end).trim();
  }

  static String stringVal(Object v) {
    if (v instanceof String s) {
      return s;
    }
    return null;
  }

  public static void mergeByName(Map<String, SkillCatalogEntry> acc, List<SkillCatalogEntry> next) {
    for (SkillCatalogEntry e : next) {
      acc.put(e.name().toLowerCase(Locale.ROOT), e);
    }
  }
}
