package ai.openclaw.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

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
