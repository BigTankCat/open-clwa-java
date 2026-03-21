package ai.openclaw.agent.runtime;

import java.util.Map;

/** Narrow trace hook so {@link AgentTurnRunner} stays independent of the gateway. */
@FunctionalInterface
public interface AgentTraceSink {

  void trace(String eventType, Map<String, Object> payload);
}
