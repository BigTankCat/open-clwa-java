/**
 * Bridges WebSocket / gateway capabilities that are implemented on the OpenClaw <strong>Node</strong>
 * runtime into the Java gateway by enqueueing {@code node.invoke} commands (see {@code
 * NodeInvokeService} and connected peers handling {@code node.pending.pull}).
 *
 * <p>Authoritative TypeScript handlers live in the main repo under {@code src/gateway/server-methods/};
 * this package holds the JVM-side contracts and delegation glue. See {@code docs/node-capabilities.md}
 * and {@code reference/node-capabilities/README.md} under {@code openclaw-java}.
 */
package ai.openclaw.gateway.nodebridge;
