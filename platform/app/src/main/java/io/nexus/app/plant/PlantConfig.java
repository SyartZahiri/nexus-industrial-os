package io.nexus.app.plant;

import io.nexus.app.event.SimRun;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
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

  /** The clock of the plant; starts paused. A step that throws pauses it and is reported as an error event. */
  @Bean(destroyMethod = "stop")
  SimulationLoop simulationLoop(Plant plant, EventBus bus) {
    return new SimulationLoop(plant::advance, failure -> report(bus, failure)).start();
  }

  private static void report(EventBus bus, Throwable failure) {
    LOG.error("Simulation angehalten", failure);
    String type = failure.getClass().getName();
    try {
      synchronized (bus) {
        bus.publish(EventTypes.DECISION, "plant", Severity.ERROR, null, new Payloads.Decision("Simulation",
            "angehalten", java.util.List.of(), "Schritt fehlgeschlagen: " + type.substring(type.lastIndexOf('.') + 1)));
      }
    } catch (RuntimeException reportFailed) {
      LOG.error("Meldung nicht gespeichert", reportFailed);
    }
  }

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PlantConfig.class);

  /** Follows the plant's current machines, also after a reset replaced them. */
  @Bean
  MachineControl machineControl(Plant plant) {
    return plant.machineControl();
  }
}
