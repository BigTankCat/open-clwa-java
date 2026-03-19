package ai.openclaw.gateway.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class GatewayWebSocketConfig implements WebSocketConfigurer {

  private final GatewayWebSocketHandler gatewayHandler;

  public GatewayWebSocketConfig(GatewayWebSocketHandler gatewayHandler) {
    this.gatewayHandler = gatewayHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(gatewayHandler, "/ws").setAllowedOrigins("*");
  }
}
