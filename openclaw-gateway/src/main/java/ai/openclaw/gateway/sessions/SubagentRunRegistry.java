package ai.openclaw.gateway.sessions;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Tracks subagent runs (child session controlled by a parent session). Aligns with Node {@code
 * subagent-registry} for HTTP session kill authorization.
 *
 * <p>Populate via {@link #register} when the Java gateway gains subagent spawn; until then the map
 * stays empty and non-admin kills fall through with {@code killed=false} unless local/bearer admin
 * applies.
 */
@Component
public final class SubagentRunRegistry {

  public record Run(String childSessionKey, String controllerSessionKey) {}

  private final ConcurrentHashMap<String, Run> byChild = new ConcurrentHashMap<>();

  public void register(String childSessionKey, String controllerSessionKey) {
    if (childSessionKey == null || controllerSessionKey == null) {
      return;
    }
    String c = childSessionKey.trim();
    String p = controllerSessionKey.trim();
    if (c.isEmpty() || p.isEmpty()) {
      return;
    }
    byChild.put(c, new Run(c, p));
  }

  public Run getByChildSessionKey(String childSessionKey) {
    if (childSessionKey == null) {
      return null;
    }
    return byChild.get(childSessionKey.trim());
  }

  public void removeByChildSessionKey(String childSessionKey) {
    if (childSessionKey == null) {
      return;
    }
    byChild.remove(childSessionKey.trim());
  }
}
