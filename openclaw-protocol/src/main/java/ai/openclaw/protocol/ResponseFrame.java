package ai.openclaw.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonTypeName("res")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ResponseFrame implements GatewayFrame {
  @JsonProperty("type")
  private String type = "res";
  private String id;
  private boolean ok;
  private Object payload;
  private ErrorShape error;

  public ResponseFrame() {}

  public ResponseFrame(String id, boolean ok, Object payload, ErrorShape error) {
    this.type = "res";
    this.id = id;
    this.ok = ok;
    this.payload = payload;
    this.error = error;
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

  public boolean isOk() {
    return ok;
  }

  public void setOk(boolean ok) {
    this.ok = ok;
  }

  public Object getPayload() {
    return payload;
  }

  public void setPayload(Object payload) {
    this.payload = payload;
  }

  public ErrorShape getError() {
    return error;
  }

  public void setError(ErrorShape error) {
    this.error = error;
  }
}
