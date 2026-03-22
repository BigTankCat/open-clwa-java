package ai.openclaw.gateway.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeTaskAgentToolTest {

  @Test
  void resolveRelativeWorkdirInsideWorkspace(@TempDir Path tmp) throws Exception {
    Path ws = tmp.resolve("ws");
    Path proj = ws.resolve("proj");
    Files.createDirectories(proj);
    Path got = ClaudeTaskAgentTool.resolveWorkdir(ws, "proj");
    assertTrue(Files.isSameFile(got, proj));
  }

  @Test
  void rejectWorkdirOutsideWorkspace(@TempDir Path tmp) throws Exception {
    Path ws = tmp.resolve("ws");
    Files.createDirectories(ws);
    Path outside = tmp.resolve("other");
    Files.createDirectories(outside);
    assertThrows(
        IllegalArgumentException.class, () -> ClaudeTaskAgentTool.resolveWorkdir(ws, "../other"));
  }

  @Test
  void resolveAbsoluteWorkdirUnderWorkspace(@TempDir Path tmp) throws Exception {
    Path ws = tmp.resolve("ws");
    Path proj = ws.resolve("a").resolve("b");
    Files.createDirectories(proj);
    Path got = ClaudeTaskAgentTool.resolveWorkdir(ws, proj.toString());
    assertEquals(proj.toRealPath(), got.toRealPath());
  }
}
