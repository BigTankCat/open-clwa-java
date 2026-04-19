package ai.openclaw.gateway.nodebridge;

import ai.openclaw.gateway.node.NodeInvokeService;
import ai.openclaw.gateway.node.NodeInvokeService.NodeInvokeResolution;
import ai.openclaw.protocol.ErrorCodes;
import ai.openclaw.protocol.ErrorShape;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Implements {@code browser.request} by delegating to a connected Node via {@link
 * NodeCapabilityCommands#BROWSER_PROXY}, when {@code OPENCLAW_BRIDGE_BROWSER_NODE_ID} (or {@code
 * openclaw.bridge.browser-node-id}) is set to that node's id.
 *
 * <p>Does not replicate Node file persistence for proxy-returned files ({@code persistProxyFiles} /
 * path rewriting); clients receive the raw {@code result} object from the node payload when present.
 */
@Service
public final class BrowserProxyNodeBridge {

  private final NodeInvokeService nodeInvoke;
  private final ObjectMapper mapper;
  private final String browserNodeId;
  private final long defaultTimeoutMs;

  public BrowserProxyNodeBridge(
      NodeInvokeService nodeInvoke, ObjectMapper mapper, Environment environment) {
    this.nodeInvoke = nodeInvoke;
    this.mapper = mapper;
    this.browserNodeId = firstNonBlank(environment.getProperty("OPENCLAW_BRIDGE_BROWSER_NODE_ID"))
        .or(() -> firstNonBlank(environment.getProperty("openclaw.bridge.browser-node-id")))
        .orElse("");
    long t =
        environment.getProperty("openclaw.bridge.browser-invoke-timeout-ms", Long.class, 120_000L);
    this.defaultTimeoutMs = t > 0 ? t : 120_000L;
  }

  private static java.util.Optional<String> firstNonBlank(String s) {
    if (s == null) {
      return java.util.Optional.empty();
    }
    String t = s.trim();
    return t.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(t);
  }

  public boolean isEnabled() {
    return !browserNodeId.isEmpty();
  }

  public String configuredBrowserNodeId() {
    return browserNodeId.isEmpty() ? null : browserNodeId;
  }

  public record BrowserOk(Object result) implements BrowserRequestOutcome {}

  public record BrowserErr(ErrorShape error) implements BrowserRequestOutcome {}

  public sealed interface BrowserRequestOutcome permits BrowserOk, BrowserErr {}

  public BrowserRequestOutcome handleRequest(Map<String, Object> params, String sessionKey) {
    if (!isEnabled()) {
      return new BrowserErr(
          ErrorShape.of(
              ErrorCodes.UNAVAILABLE,
              "browser.request: set OPENCLAW_BRIDGE_BROWSER_NODE_ID (or openclaw.bridge.browser-node-id) "
                  + "to the connected OpenClaw Node id that exposes browser.proxy"));
    }

    var parsed = BrowserProxyParams.parse(params);
    if (parsed instanceof BrowserProxyParams.ParseError e) {
      return new BrowserErr(e.error());
    }
    if (!(parsed instanceof BrowserProxyParams.Parsed ok)) {
      return new BrowserErr(
          ErrorShape.of(ErrorCodes.UNAVAILABLE, "browser.request: invalid params"));
    }

    Map<String, Object> proxyParams = BrowserProxyParams.toProxyCommandParams(ok);
    Long reqTimeout = ok.timeoutMs();
    long waitMs = reqTimeout != null && reqTimeout > 0 ? reqTimeout : defaultTimeoutMs;

    String id = UUID.randomUUID().toString();
    String paramsJson;
    try {
      paramsJson = mapper.writeValueAsString(proxyParams);
    } catch (Exception ex) {
      return new BrowserErr(
          ErrorShape.of(ErrorCodes.INVALID_REQUEST, "browser.request: cannot serialize params"));
    }

    var waiter =
        nodeInvoke.registerWaiterAndEnqueue(
            browserNodeId, id, NodeCapabilityCommands.BROWSER_PROXY, paramsJson, sessionKey);
    try {
      NodeInvokeResolution res = waiter.get(waitMs, TimeUnit.MILLISECONDS);
      if (!res.ok()) {
        return new BrowserErr(
            res.error() != null
                ? res.error()
                : ErrorShape.of(ErrorCodes.UNAVAILABLE, "browser.proxy failed"));
      }
      Object payloadObj = res.payload();
      if (payloadObj == null && res.payloadJSON() != null) {
        try {
          payloadObj =
              mapper.readValue(res.payloadJSON(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
          return new BrowserErr(
              ErrorShape.of(ErrorCodes.UNAVAILABLE, "browser.proxy: invalid payload JSON"));
        }
      }
      if (!(payloadObj instanceof Map<?, ?> m)) {
        return new BrowserErr(
            ErrorShape.of(ErrorCodes.UNAVAILABLE, "browser.proxy: missing result object"));
      }
      Object inner = m.get("result");
      if (inner == null) {
        return new BrowserErr(
            ErrorShape.of(ErrorCodes.UNAVAILABLE, "browser proxy failed"));
      }
      return new BrowserOk(inner);
    } catch (TimeoutException e) {
      nodeInvoke.discardWaiter(id);
      return new BrowserErr(
          ErrorShape.of(ErrorCodes.AGENT_TIMEOUT, "browser.request: timeout waiting for node"));
    } catch (ExecutionException e) {
      nodeInvoke.discardWaiter(id);
      Throwable c = e.getCause();
      String msg = c != null ? c.getMessage() : e.getMessage();
      return new BrowserErr(
          ErrorShape.of(ErrorCodes.UNAVAILABLE, "browser.request failed: " + msg));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      nodeInvoke.discardWaiter(id);
      return new BrowserErr(ErrorShape.of(ErrorCodes.UNAVAILABLE, "interrupted"));
    }
  }
}
