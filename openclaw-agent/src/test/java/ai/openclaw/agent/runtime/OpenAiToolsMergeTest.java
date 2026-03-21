package ai.openclaw.agent.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenAiToolsMergeTest {

  @Test
  void userOverridesRegistryOnSameName() {
    Map<String, Object> regEcho = tool("echo", "registry");
    Map<String, Object> userEcho = tool("echo", "user");
    OpenAiToolsMerge.MergeResult r =
        OpenAiToolsMerge.mergeWithReport(List.of(regEcho), List.of(userEcho));
    Assertions.assertEquals(List.of("echo"), r.overriddenNames());
    Assertions.assertEquals(1, r.tools().size());
    @SuppressWarnings("unchecked")
    Map<String, Object> fn = (Map<String, Object>) r.tools().getFirst().get("function");
    Assertions.assertEquals("user", fn.get("description"));
  }

  private static Map<String, Object> tool(String name, String description) {
    Map<String, Object> fn = new LinkedHashMap<>();
    fn.put("name", name);
    fn.put("description", description);
    fn.put("parameters", Map.of("type", "object"));
    return Map.of("type", "function", "function", fn);
  }
}
