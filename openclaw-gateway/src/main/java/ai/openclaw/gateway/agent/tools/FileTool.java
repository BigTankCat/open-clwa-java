package ai.openclaw.gateway.agent.tools;

import ai.openclaw.agent.runtime.ToolExecutionContext;
import ai.openclaw.agent.tools.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File operations tool: read / write / str_replace / list / delete.
 * Implements the Node gateway file tool interface.
 */
public final class FileTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "file";
  }

  @Override
  public String description() {
    return "Read, write, or edit files. Actions: read (read file content), write (create/overwrite file), "
        + "str_replace (replace old_str with new_str in a file), list (list directory contents), "
        + "delete (delete a file or empty directory).";
  }

  @Override
  public Map<String, Object> parametersSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("action", Map.of(
        "type", "string",
        "enum", List.of("read", "write", "str_replace", "list", "delete"),
        "description", "File operation to perform"));
    props.put("path", Map.of(
        "type", "string",
        "description", "Absolute or relative file/directory path"));
    props.put("content", Map.of(
        "type", "string",
        "description", "File content for write action (ignored for other actions)"));
    props.put("old_str", Map.of(
        "type", "string",
        "description", "Text to replace (for str_replace action)"));
    props.put("new_str", Map.of(
        "type", "string",
        "description", "Replacement text (for str_replace action)"));
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("type", "object");
    root.put("properties", props);
    root.put("required", List.of("action", "path"));
    return root;
  }

  @Override
  public String execute(String argumentsJson, ToolExecutionContext ctx) throws Exception {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return MAPPER.writeValueAsString(Map.of("error", "missing arguments"));
    }
    JsonNode args = MAPPER.readTree(argumentsJson);
    String action = args.has("action") ? args.get("action").asText() : null;
    String pathStr = args.has("path") ? args.get("path").asText() : null;
    if (action == null || pathStr == null) {
      return MAPPER.writeValueAsString(Map.of("error", "action and path are required"));
    }

    // Resolve relative paths against current working directory
    Path base = Path.of(".").toAbsolutePath().normalize();
    Path path = Path.of(pathStr).isAbsolute() ? Path.of(pathStr) : base.resolve(pathStr).normalize();

    return switch (action) {
      case "read" -> read(path);
      case "write" -> write(path, args.has("content") ? args.get("content").asText() : "");
      case "str_replace" -> strReplace(path, args);
      case "list" -> list(path);
      case "delete" -> delete(path);
      default -> MAPPER.writeValueAsString(Map.of("error", "unknown action: " + action));
    };
  }

  private String read(Path path) throws Exception {
    try {
      if (!Files.exists(path)) {
        return MAPPER.writeValueAsString(Map.of("error", "file not found: " + path));
      }
      if (Files.isDirectory(path)) {
        return MAPPER.writeValueAsString(Map.of("error", "cannot read directory as file: " + path));
      }
      long size = Files.size(path);
      if (size > 512 * 1024) {
        String truncated = Files.readString(path, StandardCharsets.UTF_8).substring(0, 512 * 1024);
        return MAPPER.writeValueAsString(Map.of(
            "warning", "file is large (" + size + " bytes), truncated to 512KB",
            "content", truncated));
      }
      String content = Files.readString(path, StandardCharsets.UTF_8);
      return MAPPER.writeValueAsString(Map.of("content", content));
    } catch (IOException e) {
      return MAPPER.writeValueAsString(Map.of("error", "read failed: " + e.getMessage()));
    }
  }

  private String write(Path path, String content) throws Exception {
    try {
      Path parent = path.getParent();
      if (parent != null) Files.createDirectories(parent);
      Files.writeString(path, content, StandardCharsets.UTF_8);
      return MAPPER.writeValueAsString(Map.of("ok", true, "path", path.toAbsolutePath()));
    } catch (IOException e) {
      return MAPPER.writeValueAsString(Map.of("error", "write failed: " + e.getMessage()));
    }
  }

  private String strReplace(Path path, JsonNode args) throws Exception {
    if (!args.has("old_str") || !args.has("new_str")) {
      return MAPPER.writeValueAsString(Map.of("error", "old_str and new_str required for str_replace"));
    }
    String oldStr = args.get("old_str").asText();
    String newStr = args.get("new_str").asText();
    try {
      if (!Files.exists(path)) {
        return MAPPER.writeValueAsString(Map.of("error", "file not found: " + path));
      }
      String content = Files.readString(path, StandardCharsets.UTF_8);
      if (!content.contains(oldStr)) {
        return MAPPER.writeValueAsString(Map.of(
            "error", "old_str not found in file. Make sure the exact string (including whitespace) exists."));
      }
      String updated = content.replace(oldStr, newStr);
      Files.writeString(path, updated, StandardCharsets.UTF_8);
      return MAPPER.writeValueAsString(Map.of("ok", true, "message", "replaced 1 occurrence"));
    } catch (IOException e) {
      return MAPPER.writeValueAsString(Map.of("error", "str_replace failed: " + e.getMessage()));
    }
  }

  private String list(Path path) throws Exception {
    try {
      if (!Files.exists(path)) {
        return MAPPER.writeValueAsString(Map.of("error", "path does not exist: " + path));
      }
      if (!Files.isDirectory(path)) {
        return MAPPER.writeValueAsString(Map.of("error", "not a directory: " + path));
      }
      try (Stream<Path> entries = Files.list(path)) {
        List<Map<String, Object>> items = entries.map(p -> {
          try {
            return Map.<String, Object>of(
                "name", p.getFileName().toString(),
                "type", Files.isDirectory(p) ? "dir" : "file",
                "size", Files.size(p));
          } catch (IOException e) {
            return Map.<String, Object>of(
                "name", p.getFileName().toString(),
                "type", "unknown",
                "error", e.getMessage());
          }
        }).toList();
        return MAPPER.writeValueAsString(Map.of("entries", items, "path", path.toAbsolutePath()));
      }
    } catch (IOException e) {
      return MAPPER.writeValueAsString(Map.of("error", "list failed: " + e.getMessage()));
    }
  }

  private String delete(Path path) throws Exception {
    try {
      if (!Files.exists(path)) {
        return MAPPER.writeValueAsString(Map.of("error", "path does not exist: " + path));
      }
      if (Files.isDirectory(path)) {
        try (Stream<Path> entries = Files.list(path)) {
          if (entries.findFirst().isPresent()) {
            return MAPPER.writeValueAsString(Map.of("error", "directory not empty: " + path));
          }
        }
        Files.delete(path);
      } else {
        Files.delete(path);
      }
      return MAPPER.writeValueAsString(Map.of("ok", true, "deleted", path.toAbsolutePath()));
    } catch (IOException e) {
      return MAPPER.writeValueAsString(Map.of("error", "delete failed: " + e.getMessage()));
    }
  }
}
