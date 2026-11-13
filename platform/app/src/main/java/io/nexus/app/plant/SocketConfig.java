package io.nexus.app.plant;

import io.nexus.app.event.JdbcEventLog;
import io.nexus.app.event.SimRun;
import io.nexus.plant.Plant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** /ws for the user interface. Same origin, plus the Vite dev servers on this machine. */
@Configuration
@EnableWebSocket
class SocketConfig implements WebSocketConfigurer {
  static final String[] DEV_ORIGINS = {"http://localhost:5173", "http://localhost:5179"};

  private final PlantSocket socket;

  SocketConfig(PlantSocket socket) {
    this.socket = socket;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(socket, "/ws").setAllowedOrigins(DEV_ORIGINS);
  }

  @Configuration
  static class Beans {
    @Bean(destroyMethod = "stop")
    PlantSocket plantSocket(Plant plant, JdbcEventLog events, SimRun run) {
      return new PlantSocket(plant, events, run).start();
    }
  }
}
