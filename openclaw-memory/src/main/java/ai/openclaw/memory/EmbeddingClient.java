package ai.openclaw.memory;

/** Optional text embeddings for vector memory search (OpenAI-compatible HTTP API). */
public interface EmbeddingClient {

  /** When false, {@link SqliteMemoryStore} skips vector indexing and hybrid search. */
  default boolean enabled() {
    return true;
  }

  /** Returns L2-normalized embedding, or null if the client is disabled / request failed. */
  float[] embed(String text) throws Exception;
}
