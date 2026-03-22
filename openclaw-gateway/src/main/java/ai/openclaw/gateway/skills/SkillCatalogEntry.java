package ai.openclaw.gateway.skills;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One discovered skill ({@code SKILL.md}) with YAML frontmatter root + {@code openclaw} block (Node
 * {@code metadata}).
 */
public record SkillCatalogEntry(
    String name,
    String description,
    String source,
    Path filePath,
    Path baseDir,
    String skillKey,
    Map<String, Object> docRoot,
    Map<String, Object> openclaw,
    List<Map<String, Object>> installSpecs) {}
