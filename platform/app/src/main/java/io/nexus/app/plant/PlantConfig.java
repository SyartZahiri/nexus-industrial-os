package io.nexus.app.plant;

import io.nexus.app.event.SimRun;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.port.MachineControl;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.plant.Plant;
import io.nexus.plant.PlantRules;
import io.nexus.smartfactory.SmartFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The plant on the server: the JDBC-backed FlowPilot and SmartFactory with the simulated machines. Until OPC UA
 * (M1-D3) SmartFactory commands the machines directly, as in the browser.
 */
@Configuration
class PlantConfig {
  @Bean
  @DependsOnDatabaseInitialization
  Plant plant(EventBus bus, SimTime time, SimRun run, FlowPilot flowPilot, SmartFactory smartFactory) {
    var random = new SeededRandom(run.seed());
    return new Plant(bus, time, random, flowPilot, smartFactory, Plant.simulationFor(random, smartFactory),
        PlantRules.DEFAULT);
  }

  /** Follows the plant's current machines, also after a reset replaced them. */
  @Bean
  MachineControl machineControl(Plant plant) {
    return plant.machineControl();
  }
}
