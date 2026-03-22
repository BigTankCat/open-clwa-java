package ai.openclaw.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls an OpenAI-compatible {@code POST /v1/embeddings} (or compatible) endpoint.
 *
 * <p>Set {@code OPENCLAW_EMBEDDINGS_URL} to enable. Optional {@code OPENCLAW_EMBEDDINGS_API_KEY}
 * (Bearer) and {@code OPENCLAW_EMBEDDINGS_MODEL} (default {@code text-embedding-3-small}).
 */
public final class HttpEmbeddingClient implements EmbeddingClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final String url;
  private final String apiKey;
  private final String model;

  public HttpEmbeddingClient() {
    this(
        trimToNull(System.getenv("OPENCLAW_EMBEDDINGS_URL")),
        trimToNull(System.getenv("OPENCLAW_EMBEDDINGS_API_KEY")),
        trimToNull(System.getenv("OPENCLAW_EMBEDDINGS_MODEL")));
  }

  public HttpEmbeddingClient(String url, String apiKey, String model) {
    this.url = url;
    this.apiKey = apiKey;
    this.model = model != null ? model : "text-embedding-3-small";
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  @Override
  public boolean enabled() {
    return url != null && !url.isBlank();
  }

  @Override
  public float[] embed(String text) throws Exception {
    if (!enabled() || text == null || text.isBlank()) {
      return null;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("input", text);
    HttpRequest.Builder b =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
    if (apiKey != null && !apiKey.isBlank()) {
      b.header("Authorization", "Bearer " + apiKey);
    }
    HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (res.statusCode() < 200 || res.statusCode() >= 300) {
      return null;
    }
    JsonNode root = MAPPER.readTree(res.body());
    JsonNode data = root.path("data");
    if (!data.isArray() || data.size() == 0) {
      return null;
    }
    JsonNode emb = data.get(0).path("embedding");
    if (!emb.isArray()) {
      return null;
    }
    float[] v = new float[emb.size()];
    for (int i = 0; i < emb.size(); i++) {
      v[i] = (float) emb.get(i).asDouble();
    }
    return l2Normalize(v);
  }

  static float[] l2Normalize(float[] v) {
    double sum = 0;
    for (float f : v) {
      sum += (double) f * f;
    }
    if (sum <= 1e-12) {
      return v;
    }
    float inv = (float) (1.0 / Math.sqrt(sum));
    float[] out = new float[v.length];
    for (int i = 0; i < v.length; i++) {
      out[i] = v[i] * inv;
    }
    return out;
  }

  static double cosine(float[] a, float[] b) {
    if (a == null || b == null || a.length != b.length) {
      return 0;
    }
    double s = 0;
    for (int i = 0; i < a.length; i++) {
      s += a[i] * b[i];
    }
    return s;
  }
}
