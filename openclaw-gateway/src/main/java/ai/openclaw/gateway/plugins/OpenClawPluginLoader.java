package ai.openclaw.gateway.plugins;

import ai.openclaw.config.ConfigPaths;
import ai.openclaw.plugin.api.OpenClawPlugin;
import ai.openclaw.plugin.api.PluginContext;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.springframework.stereotype.Component;

/** Loads optional classpath plugins via {@link ServiceLoader}. */
@Component
public class OpenClawPluginLoader {

  private final ConfigPaths configPaths;
  private final List<Map<String, Object>> loaded = new ArrayList<>();

  public OpenClawPluginLoader(ConfigPaths configPaths) {
    this.configPaths = configPaths;
  }

  @PostConstruct
  public void loadPlugins() {
    ServiceLoader<OpenClawPlugin> loader = ServiceLoader.load(OpenClawPlugin.class);
    PluginContext ctx = new PluginContext(configPaths.getStateDirPath());
    for (OpenClawPlugin p : loader) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", p.id());
      row.put("class", p.getClass().getName());
      try {
        p.onLoad(ctx);
        row.put("ok", true);
      } catch (Exception e) {
        row.put("ok", false);
        row.put("error", e.getMessage());
      }
      loaded.add(row);
    }
  }

  public List<Map<String, Object>> listLoaded() {
    return List.copyOf(loaded);
  }
}
