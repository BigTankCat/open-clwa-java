package ai.openclaw.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Union of request / response / event frames over WebSocket. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(name = "req", value = RequestFrame.class),
  @JsonSubTypes.Type(name = "res", value = ResponseFrame.class),
  @JsonSubTypes.Type(name = "event", value = EventFrame.class),
})
public interface GatewayFrame {}
