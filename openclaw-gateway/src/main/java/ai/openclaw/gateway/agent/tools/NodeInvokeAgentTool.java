package ai.openclaw.gateway.agent.tools;

import ai.openclaw.agent.runtime.ToolExecutionContext;
import ai.openclaw.agent.tools.AgentTool;
import ai.openclaw.gateway.node.NodeInvokeService;
import ai.openclaw.gateway.node.NodeInvokeService.NodeInvokeResolution;
import ai.openclaw.protocol.ErrorShape;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Invokes a connected OpenClaw Node over the gateway queue ({@code node.pending.pull} / {@code
 * node.invoke.result}). Disabled unless {@code OPENCLAW_NODE_INVOKE_TOOL_ENABLED=true}.
 */
public final class NodeInvokeAgentTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long DEFAULT_TIMEOUT_MS = 120_000;

  private final NodeInvokeService nodeInvoke;

  public NodeInvokeAgentTool(NodeInvokeService nodeInvoke) {
    this.nodeInvoke = nodeInvoke;
  }

  @Override
  public String name() {
    return "node_invoke";
  }

  @Override
  public String description() {
    return "Enqueue a command for the linked OpenClaw Node runtime and wait for node.invoke.result. "
        + "Requires a connected node and a valid nodeId. Use for work only Node can do (channels, "
        + "filesystem, etc.).";
  }

  @Override
  public Map<String, Object> parametersSchema() {
    Map<String, Object> nodeId =
        Map.of("type", "string", "description", "Target node id (must match connected node)");
    Map<String, Object> command =
        Map.of("type", "string", "description", "Node command name (OpenClaw node protocol)");
    Map<String, Object> params =
        Map.of(
            "type", "object",
            "description",
            "Opaque JSON object passed to the node as params (optional)");
    Map<String, Object> idempotencyKey =
        Map.of(
            "type", "string",
            "description",
            "Stable id for this invoke; default random UUID if omitted");
    Map<String, Object> timeoutMs =
        Map.of(
            "type", "integer",
            "description",
            "Max wait for node.invoke.result in ms (default 120000)");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("nodeId", nodeId);
    props.put("command", command);
    props.put("params", params);
    props.put("idempotencyKey", idempotencyKey);
    props.put("timeoutMs", timeoutMs);
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("type", "object");
    root.put("properties", props);
    root.put("required", java.util.List.of("nodeId", "command"));
    return root;
  }

  @Override
  public String execute(String argumentsJson, ToolExecutionContext ctx) throws Exception {
    JsonNode n = MAPPER.readTree(argumentsJson);
    String nodeId = n.path("nodeId").asText("").trim();
    String command = n.path("command").asText("").trim();
    if (nodeId.isEmpty()) {
      return MAPPER.writeValueAsString(Map.of("error", "nodeId_required"));
    }
    if (command.isEmpty()) {
      return MAPPER.writeValueAsString(Map.of("error", "command_required"));
    }

    String id = n.path("idempotencyKey").asText("").trim();
    if (id.isEmpty()) {
      id = UUID.randomUUID().toString();
    }

    long timeoutMs = DEFAULT_TIMEOUT_MS;
    if (n.has("timeoutMs") && n.get("timeoutMs").isNumber()) {
      long t = n.get("timeoutMs").asLong();
      if (t > 0) {
        timeoutMs = Math.min(t, 600_000);
      }
    }

    JsonNode paramsNode = n.get("params");
    String paramsJSON = null;
    if (paramsNode != null && !paramsNode.isNull()) {
      if (paramsNode.isTextual()) {
        paramsJSON = paramsNode.asText();
      } else {
        paramsJSON = MAPPER.writeValueAsString(paramsNode);
      }
    }

    String sessionKey = ctx != null ? ctx.sessionKey() : null;
    var waiter =
        nodeInvoke.registerWaiterAndEnqueue(nodeId, id, command, paramsJSON, sessionKey);
    try {
      NodeInvokeResolution res = waiter.get(timeoutMs, TimeUnit.MILLISECONDS);
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("ok", res.ok());
      out.put("nodeId", nodeId);
      out.put("command", command);
      out.put("idempotencyKey", id);
      if (res.ok()) {
        out.put("payload", res.payload());
        if (res.payloadJSON() != null) {
          out.put("payloadJSON", res.payloadJSON());
        }
      } else if (res.error() != null) {
        ErrorShape e = res.error();
        out.put("error", Map.of("code", e.getCode(), "message", e.getMessage()));
      } else {
        out.put("error", Map.of("code", "unknown", "message", "invoke failed"));
      }
      return MAPPER.writeValueAsString(out);
    } catch (TimeoutException e) {
      nodeInvoke.discardWaiter(id);
      return MAPPER.writeValueAsString(
          Map.of("error", "timeout", "message", "node.invoke timed out", "idempotencyKey", id));
    } catch (ExecutionException e) {
      nodeInvoke.discardWaiter(id);
      return MAPPER.writeValueAsString(
          Map.of(
              "error",
              "execution_failed",
              "message",
              e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      nodeInvoke.discardWaiter(id);
      return MAPPER.writeValueAsString(Map.of("error", "interrupted"));
    }
  }
}
