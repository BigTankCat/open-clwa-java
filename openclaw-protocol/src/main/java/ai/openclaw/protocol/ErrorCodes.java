package ai.openclaw.protocol;

/** Gateway error codes; aligned with Node protocol/schema/error-codes.ts */
public final class ErrorCodes {
  public static final String NOT_LINKED = "NOT_LINKED";
  public static final String NOT_PAIRED = "NOT_PAIRED";
  public static final String AGENT_TIMEOUT = "AGENT_TIMEOUT";
  public static final String INVALID_REQUEST = "INVALID_REQUEST";
  public static final String UNAVAILABLE = "UNAVAILABLE";

  private ErrorCodes() {}
}
