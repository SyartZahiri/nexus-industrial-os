package io.nexus.app.routemind;

import io.nexus.app.event.SimRun;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.routemind.RouteMind;
import io.nexus.routemind.optimize.RouteOptimizer;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.store.RouteStore;
import io.nexus.smartfactory.SmartFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RouteMindConfig {
  @Bean
  RouteMind routeMind(RouteStore store, EventBus bus, UnitOfWork work, SimTime time, IdSource ids, FlowPilot flowPilot,
                      SmartFactory smartFactory, RouteOptimizer optimizer, SimRun run) {
    return new RouteMind(store, bus, work, time, ids, flowPilot.materials(), flowPilot.transports(),
        smartFactory.eta(), smartFactory.catalog(), optimizer, run.seed());
  }

  @Bean
  RouteOptimizer routeOptimizer(@Value("${nexus.routemind.solver:ortools}") String solver) {
    return switch (solver) {
      case "ortools" -> new OrToolsOptimizer();
      case "heuristic" -> new SavingsHeuristic();
      default -> throw new IllegalArgumentException("nexus.routemind.solver: ortools oder heuristic, nicht " + solver);
    };
  }
}
