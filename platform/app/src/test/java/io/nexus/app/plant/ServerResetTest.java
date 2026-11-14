package io.nexus.app.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.app.event.JdbcEventLog;
import io.nexus.app.event.SimRun;
import io.nexus.plant.Plant;
import io.nexus.plant.PlantRules;
import io.nexus.plant.Scenario1h;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** After a reset the server plant behaves exactly like a freshly started one: same events, same frame. */
@SpringBootTest
@ActiveProfiles("embedded")
class ServerResetTest {
  @Autowired PlantReset reset;
  @Autowired Plant plant;
  @Autowired JdbcEventLog log;
  @Autowired SimRun run;
  @Autowired JdbcClient jdbc;

  static void assertSameLines(String actual, String expected) {
    String[] a = actual.split("\n");
    String[] e = expected.split("\n");
    for (int i = 0; i < Math.min(a.length, e.length); i++) {
      assertThat(a[i]).as("line " + (i + 1)).isEqualTo(e[i]);
    }
    assertThat(a.length).as("number of lines").isEqualTo(e.length);
  }

  @Test
  void aResetPlantRunsExactlyLikeAFreshOne() {
    reset.reset(3);
    plant.setRules(PlantRules.DEFAULT);
    plant.advance(10 * 60_000);                    // some history the reset has to wipe
    long oldRun = run.id();

    reset.reset(7);
    plant.advance(30 * 60_000);
    String server = Scenario1h.render(log.eventsOfRun(run.id()), plant.frame());

    Plant fresh = Plant.inMemory(7, PlantRules.DEFAULT);
    fresh.advance(30 * 60_000);
    assertSameLines(server, Scenario1h.render(fresh.events(), fresh.frame()));

    assertThat(run.id()).isNotEqualTo(oldRun);
    assertThat(jdbc.sql("select count(*) from event where run_id = :r").param("r", oldRun).query(Integer.class).single())
        .as("events of the old run stay").isPositive();
  }
}
