package ai.openclaw.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * WebSocket JSON-RPC request frame sent from client to server.
 * <p>
 * This frame represents a single request message in the JSON-RPC protocol,
 * containing a unique identifier, method name to invoke, and parameters.
 * Each request expects a corresponding {@link ResponseFrame} with matching id.
 *
 * <p>Example JSON structure:
 * <pre>{@code
 * {
 *   "type": "req",
 *   "id": "550e8400-e29b-41d4-a716-446655440000",
 *   "method": "chat.send",
 *   "params": {
 *     "sessionKey": "daily",
 *     "message": "Hello"
 *   }
 * }
 * }</pre>
 *
 * @param type      Frame type, always "req" for requests
 * @param id        Unique request identifier (UUID), used to match responses
 * @param method    Method name to invoke, e.g., "chat.send", "config.get"
 * @param params    Method-specific parameters as key-value map
 *
 * @author OpenClaw Team
 * @since 2026.3.14
 * @see ResponseFrame
 * @see EventFrame
 * @see GatewayFrame
 */
@JsonTypeName("req")
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RequestFrame implements GatewayFrame {
  @JsonProperty("type")
  private String type = "req";
  private String id;
  private String method;
  private Map<String, Object> params;

  public RequestFrame() {}

  public RequestFrame(String id, String method, Map<String, Object> params) {
    this.type = "req";
    this.id = id;
    this.method = method;
    this.params = params;
  }

  public String getType() {
    return type;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public Map<String, Object> getParams() {
    return params;
  }

  public void setParams(Map<String, Object> params) {
    this.params = params;
  }
}
