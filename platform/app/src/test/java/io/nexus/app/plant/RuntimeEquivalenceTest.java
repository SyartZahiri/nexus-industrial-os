package io.nexus.app.plant;

import io.nexus.app.event.JdbcEventLog;
import io.nexus.app.event.SimRun;
import io.nexus.plant.Plant;
import io.nexus.plant.Scenario1h;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spec 1: same seed and same interventions give the same run on every runtime. The scripted hour (four orders, a
 * machine fault, an AGV fault, an express order) on the server with PostgreSQL gives exactly the text the in-memory
 * plant gives; the browser build gives that same text too (platform/web, plant-run.mjs).
 */
@SpringBootTest
@ActiveProfiles("embedded")
class RuntimeEquivalenceTest {
  @Autowired Plant plant;
  @Autowired PlantReset reset;
  @Autowired JdbcEventLog log;
  @Autowired SimRun run;

  @Test
  void theScriptedHourIsTheSameOnTheServerAsInMemory() {
    plant.setRules(Scenario1h.rules());            // applied again by the reset, exactly as a new plant applies them
    reset.reset(4711);
    Scenario1h.play(plant);
    String server = Scenario1h.render(log.eventsOfRun(run.id()), plant.frame());

    String memory = Scenario1h.run(Plant.inMemory(4711, Scenario1h.rules()));
    ServerResetTest.assertSameLines(server, memory);
  }
}
