package io.nexus.app.flowpilot;

import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.store.FleetStore;
import io.nexus.flowpilot.store.StockStore;
import io.nexus.flowpilot.store.TransportStore;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** FlowPilot on PostgreSQL. Other modules get MaterialService and TransportService, both serialised with the step. */
@Configuration
class FlowPilotConfig {
  @Bean
  @DependsOnDatabaseInitialization // loads AGVs and edge blocks at start, Flyway must have run
  FlowPilot flowPilot(StockStore stock, TransportStore transports, FleetStore fleet, EventPublisher events,
                      UnitOfWork work, SimTime time, IdSource ids) {
    return new FlowPilot(stock, transports, fleet, events, work, time, ids);
  }

  @Bean
  MaterialService materialService(FlowPilot flowPilot) {
    return flowPilot.materials();
  }

  @Bean
  TransportService transportService(FlowPilot flowPilot) {
    return flowPilot.transports();
  }
}
