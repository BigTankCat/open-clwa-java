package ai.openclaw.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Port of Node's config $include resolution logic (simplified).
 *
 * <p>Behavior:
 * - Supports {@code "$include": "./file.json5"} or array of include paths.
 * - Supports nested includes.
 * - Detects circular includes.
 * - Has max include depth.
 * - Merges included object with sibling keys via deep merge:
 *   - arrays: concatenate
 *   - objects: recursive merge
 *   - primitives: source wins
 */
public final class ConfigIncludes {

  public static final String INCLUDE_KEY = "$include";
  public static final int MAX_INCLUDE_DEPTH = 10;
  public static final long MAX_INCLUDE_FILE_BYTES = 2L * 1024L * 1024L;

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ObjectMapper json5Mapper;

  // Prototype pollution guardrail parity with Node src/infra/prototype-keys.ts
  private static final Set<String> BLOCKED_OBJECT_KEYS =
      Set.of("__proto__", "prototype", "constructor");

  public ConfigIncludes(ObjectMapper json5Mapper) {
    this.json5Mapper = json5Mapper;
  }

  public Map<String, Object> resolveIncludes(Map<String, Object> root, Path configPath) {
    Objects.requireNonNull(configPath, "configPath");
    Path rootDir = configPath.toAbsolutePath().getParent();
    if (rootDir == null) rootDir = configPath.toAbsolutePath().normalize().getParent();
    if (rootDir == null) rootDir = configPath.toAbsolutePath().getRoot();
    return castRootObject(
        resolveNested(
            root,
            configPath.toAbsolutePath().normalize(),
            rootDir,
            new LinkedList<>(),
            0));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> castRootObject(Object obj) {
    if (!(obj instanceof Map)) {
      throw new ConfigIncludeException("Config root must be an object after $include resolution");
    }
    return (Map<String, Object>) obj;
  }

  private Object resolveNested(
      Object obj, Path currentFile, Path rootDir, LinkedList<Path> includeStack, int depth) {
    if (obj instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(resolveNested(item, currentFile, rootDir, includeStack, depth));
      }
      return out;
    }
    if (!(obj instanceof Map<?, ?> map)) {
      return obj;
    }

    Map<String, Object> m = castToStringObjectMap(map);
    if (!m.containsKey(INCLUDE_KEY)) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<String, Object> e : m.entrySet()) {
        out.put(e.getKey(), resolveNested(e.getValue(), currentFile, rootDir, includeStack, depth));
      }
      return out;
    }

    return resolveIncludeObject(m, currentFile, rootDir, includeStack, depth);
  }

  private Object resolveIncludeObject(
      Map<String, Object> obj,
      Path currentFile,
      Path rootDir,
      LinkedList<Path> includeStack,
      int depth) {
    Object includeValue = obj.get(INCLUDE_KEY);
    Map<String, Object> siblingKeys = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : obj.entrySet()) {
      if (!INCLUDE_KEY.equals(e.getKey())) {
        siblingKeys.put(
            e.getKey(), resolveNested(e.getValue(), currentFile, rootDir, includeStack, depth));
      }
    }

    Object included =
        resolveIncludeValue(includeValue, currentFile, rootDir, includeStack, depth + 1);

    if (siblingKeys.isEmpty()) {
      return included;
    }
    if (!(included instanceof Map<?, ?>)) {
      throw new ConfigIncludeException(
          "Sibling keys require included content to be an object");
    }
    Map<String, Object> includedMap = castToStringObjectMap((Map<?, ?>) included);
    return deepMerge(includedMap, siblingKeys);
  }

  private Object resolveIncludeValue(
      Object includeValue,
      Path currentFile,
      Path rootDir,
      LinkedList<Path> includeStack,
      int depth) {
    if (depth >= MAX_INCLUDE_DEPTH) {
      throw new ConfigIncludeException(
          "Maximum include depth (" + MAX_INCLUDE_DEPTH + ") exceeded at: " + currentFile);
    }

    if (includeValue instanceof String s) {
      return loadIncludeFile(s, currentFile, rootDir, chain, depth);
    }

    if (includeValue instanceof List<?> list) {
      Object merged = null;
      for (Object item : list) {
        if (!(item instanceof String str)) {
          throw new ConfigIncludeException(
              "Invalid $include array item: expected string");
        }
        Object part = loadIncludeFile(str, currentFile, rootDir, chain, depth);
        if (merged == null) {
          merged = part;
        } else {
          merged = deepMerge(merged, part);
        }
      }
      return merged == null ? Collections.emptyMap() : merged;
    }

    throw new ConfigIncludeException("Invalid $include value: expected string or array of strings");
  }

  private Object loadIncludeFile(
      String includePath,
      Path currentFile,
      Path rootDir,
      LinkedList<Path> chain,
      int depth) {
    Path configDir = currentFile.getParent();
    if (configDir == null) configDir = rootDir;

    Path resolved = Path.of(includePath);
    if (!resolved.isAbsolute()) {
      resolved = configDir.resolve(includePath);
    }
    resolved = resolved.normalize();

    // SECURITY: reject paths outside the top-level config directory.
    Path resolvedReal;
    try {
      resolvedReal = resolved.toRealPath();
      Path rootReal = rootDir.toRealPath();
      if (!resolvedReal.startsWith(rootReal)) {
        throw new ConfigIncludeException(
            "Include path escapes config directory: " + includePath);
      }
    } catch (IOException e) {
      throw new ConfigIncludeException("Failed to resolve include file: " + includePath, e);
    }

    if (includeStack.contains(resolvedReal)) {
      ArrayDeque<String> chainNames = new ArrayDeque<>();
      for (Path p : includeStack) chainNames.add(p.toString());
      throw new CircularIncludeException(new ArrayList<>(chainNames));
    }

    if (Files.notExists(resolvedReal)) {
      throw new ConfigIncludeException("Failed to read include file: " + includePath);
    }
    if (Files.size(resolvedReal) > MAX_INCLUDE_FILE_BYTES) {
      throw new ConfigIncludeException(
          "Include file exceeds max bytes: " + includePath + " (max " + MAX_INCLUDE_FILE_BYTES + ")");
    }

    includeStack.add(resolvedReal);
    try {
      String raw;
      try {
        raw = Files.readString(resolvedReal, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new ConfigIncludeException("Failed to read include file: " + includePath, e);
      }
      Map<String, Object> parsed = parseJson5Object(raw);
      // Resolve nested includes within the included file, while keeping stack context.
      return resolveNested(parsed, resolvedReal, rootDir, includeStack, depth + 1);
    } finally {
      includeStack.removeLast();
    }
  }

  private Map<String, Object> parseJson5Object(String raw) {
    try {
      Map<String, Object> map = json5Mapper.readValue(raw, MAP_TYPE);
      if (map == null) return Collections.emptyMap();
      return map;
    } catch (Exception e) {
      throw new ConfigIncludeException("Failed to parse include file: " + e.getMessage(), e);
    }
  }

  private static Map<String, Object> castToStringObjectMap(Map<?, ?> map) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : map.entrySet()) {
      if (!(e.getKey() instanceof String key)) continue;
      out.put(key, e.getValue());
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Object deepMerge(Object target, Object source) {
    if (target instanceof List<?> t && source instanceof List<?> s) {
      List<Object> out = new ArrayList<>(t.size() + s.size());
      out.addAll((List<Object>) t);
      out.addAll((List<Object>) s);
      return out;
    }
    if (target instanceof Map<?, ?> tm && source instanceof Map<?, ?> sm) {
      Map<String, Object> out = castToStringObjectMap(tm);
      Map<String, Object> sMap = castToStringObjectMap(sm);
      for (Map.Entry<String, Object> e : sMap.entrySet()) {
        String k = e.getKey();
        if (BLOCKED_OBJECT_KEYS.contains(k)) {
          continue;
        }
        Object sv = e.getValue();
        if (out.containsKey(k)) {
          out.put(k, deepMerge(out.get(k), sv));
        } else {
          out.put(k, sv);
        }
      }
      return out;
    }
    // primitives: source wins
    return source;
  }

  public static class ConfigIncludeException extends RuntimeException {
    public ConfigIncludeException(String message) {
      super(message);
    }

    public ConfigIncludeException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class CircularIncludeException extends ConfigIncludeException {
    public CircularIncludeException(List<String> chain) {
      super("Circular include detected: " + String.join(" -> ", chain));
    }
  }
}

