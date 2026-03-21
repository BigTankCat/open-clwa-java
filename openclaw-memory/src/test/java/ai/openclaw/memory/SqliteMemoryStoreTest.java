package ai.openclaw.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SqliteMemoryStoreTest {

  @Test
  void putAndSearch() throws Exception {
    Path dir = Files.createTempDirectory("openclaw-mem-test");
    SqliteMemoryStore store = new SqliteMemoryStore(dir);
    store.put("a1", "notes/hello.md", "OpenClaw Java memory test");
    List<MemoryHit> hits = store.search("a1", "memory", 10);
    Assertions.assertEquals(1, hits.size());
    Assertions.assertTrue(hits.getFirst().content().contains("Java"));
  }
}
