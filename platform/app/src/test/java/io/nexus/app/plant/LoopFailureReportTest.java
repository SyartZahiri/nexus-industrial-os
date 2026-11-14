package io.nexus.app.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.app.event.JdbcEventLog;
import io.nexus.app.event.SimRun;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventTypes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The loop's failure report as the server wires it: an ERROR decision the event monitor shows. */
@SpringBootTest
@ActiveProfiles("embedded")
class LoopFailureReportTest {
  @Autowired EventBus bus;
  @Autowired JdbcEventLog log;
  @Autowired SimRun run;

  @Test
  void aStoppedLoopIsReportedAsAnErrorDecisionWithoutInternals() {
    PlantConfig.report(bus, new OutOfMemoryError("interne Details"));
    var last = log.eventsOfRun(run.id()).getLast();
    assertThat(last.type()).isEqualTo(EventTypes.DECISION);
    assertThat(last.severity()).isEqualTo(Severity.ERROR);
    assertThat(last.payload().toString()).contains("angehalten").contains("OutOfMemoryError")
        .doesNotContain("interne Details");
  }
}
