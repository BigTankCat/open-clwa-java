package ai.openclaw.gateway.nodebridge;

/**
 * Node command names used when delegating from the Java gateway to a connected OpenClaw Node.
 * Align with the main OpenClaw repo node runtime and {@code src/gateway/server-methods/browser.ts}.
 */
public final class NodeCapabilityCommands {

  /** Proxies browser control-plane HTTP from the gateway to the node (see Node {@code browser.proxy}). */
  public static final String BROWSER_PROXY = "browser.proxy";

  private NodeCapabilityCommands() {}
}
