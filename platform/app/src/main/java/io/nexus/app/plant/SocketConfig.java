package io.nexus.app.plant;

import io.nexus.app.event.JdbcEventLog;
import io.nexus.app.event.SimRun;
import io.nexus.contracts.event.EventBus;
import io.nexus.plant.Plant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * /ws for the user interface. Same origin, plus the origins in nexus.ws.allowed-origins (the embedded profile, for
 * development, allows the Vite dev servers on this machine).
 */
@Configuration
@EnableWebSocket
class SocketConfig implements WebSocketConfigurer {
  private final PlantSocket socket;
  private final String[] origins;

  SocketConfig(PlantSocket socket, @Value("${nexus.ws.allowed-origins:}") String[] origins) {
    this.socket = socket;
    this.origins = origins;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(socket, "/ws").setAllowedOrigins(origins);
  }

  @Configuration
  static class Beans {
    @Bean(destroyMethod = "stop")
    PlantSocket plantSocket(Plant plant, JdbcEventLog events, SimRun run, EventBus bus) {
      return new PlantSocket(plant, events, run, bus).start();
    }
  }
}
