package ai.openclaw.memory;

/** One row returned from {@link SqliteMemoryStore#search}. */
public record MemoryHit(long id, String path, String content, long createdAtMs) {}
