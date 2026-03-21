package ai.openclaw.plugin.api;

import java.nio.file.Path;

/** Minimal context passed to plugins at load time. */
public record PluginContext(Path stateDir) {}
