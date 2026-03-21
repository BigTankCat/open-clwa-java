package ai.openclaw.plugin.api;

/**
 * Gateway plugin entry point. Implementations are discovered via {@link java.util.ServiceLoader}
 * (META-INF/services/ai.openclaw.plugin.api.OpenClawPlugin).
 */
public interface OpenClawPlugin {

  /** Stable plugin id (ASCII, no spaces recommended). */
  String id();

  /** Called once at gateway startup after core beans are available. */
  void onLoad(PluginContext ctx) throws Exception;
}
