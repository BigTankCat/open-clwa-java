package ai.openclaw.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Union of request / response / event frames over WebSocket. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Value(name = "req", value = RequestFrame.class),
  @JsonSubTypes.Value(name = "res", value = ResponseFrame.class),
  @JsonSubTypes.Value(name = "event", value = EventFrame.class),
})
public interface GatewayFrame {}
