package ai.openclaw.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Gateway error payload; aligned with Node ErrorShapeSchema.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ErrorShape {
  private final String code;
  private final String message;
  private final Object details;
  private final Boolean retryable;
  private final Long retryAfterMs;

  public ErrorShape(String code, String message, Object details, Boolean retryable, Long retryAfterMs) {
    this.code = Objects.requireNonNull(code);
    this.message = Objects.requireNonNull(message);
    this.details = details;
    this.retryable = retryable;
    this.retryAfterMs = retryAfterMs;
  }

  public static ErrorShape of(String code, String message) {
    return new ErrorShape(code, message, null, null, null);
  }

  public static ErrorShape of(String code, String message, boolean retryable, Long retryAfterMs) {
    return new ErrorShape(code, message, null, retryable, retryAfterMs);
  }

  public String getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }

  public Object getDetails() {
    return details;
  }

  public Boolean getRetryable() {
    return retryable;
  }

  public Long getRetryAfterMs() {
    return retryAfterMs;
  }
}
