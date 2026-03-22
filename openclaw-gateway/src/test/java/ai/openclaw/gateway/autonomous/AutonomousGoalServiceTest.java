package ai.openclaw.gateway.autonomous;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openclaw.config.ConfigPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousGoalServiceTest {

  @Test
  void createPatchListGetAndEvents(@TempDir Path tmp) throws Exception {
    Map<String, String> env = new HashMap<>(System.getenv());
    env.put("OPENCLAW_STATE_DIR", tmp.toString());
    AutonomousGoalService svc = new AutonomousGoalService(new ConfigPaths(env));

    Map<String, Object> created =
        svc.create(
            Map.of(
                "title", "Ship feature X",
                "objective", "Pass tests",
                "acceptanceCriteria", "CI green"));
    assertTrue(Boolean.TRUE.equals(created.get("ok")));
    String id = (String) created.get("id");
    assertNotNull(id);

    @SuppressWarnings("unchecked")
    Map<String, Object> goal = (Map<String, Object>) created.get("goal");
    assertEquals("INTAKE", goal.get("phase"));

    svc.patch(id, Map.of("phase", "EXECUTING", "plan", Map.of("steps", List.of("a", "b"))));
    svc.appendEvent(id, "custom.probe", Map.of("k", 1));

    Map<String, Object> got = svc.get(id, 50);
    assertNotNull(got);
    @SuppressWarnings("unchecked")
    Map<String, Object> g2 = (Map<String, Object>) got.get("goal");
    assertEquals("EXECUTING", g2.get("phase"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ev = (List<Map<String, Object>>) got.get("recentEvents");
    assertEquals(1, ev.size());
    assertEquals("custom.probe", ev.get(0).get("type"));

    List<Map<String, Object>> rows = svc.listSummaries();
    assertEquals(1, rows.size());
    assertEquals(id, rows.get(0).get("id"));

    Path goalsDir = tmp.resolve("java-gateway").resolve("autonomous-goals");
    assertTrue(Files.isRegularFile(goalsDir.resolve(id + ".json")));
    assertTrue(Files.isRegularFile(goalsDir.resolve(id + ".events.jsonl")));
  }

  @Test
  void getMissingReturnsNull(@TempDir Path tmp) throws Exception {
    Map<String, String> env = new HashMap<>(System.getenv());
    env.put("OPENCLAW_STATE_DIR", tmp.toString());
    AutonomousGoalService svc = new AutonomousGoalService(new ConfigPaths(env));
    assertNull(svc.get("00000000-0000-0000-0000-000000000000", 10));
  }
}
