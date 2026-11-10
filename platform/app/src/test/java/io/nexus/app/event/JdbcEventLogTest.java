package io.nexus.app.event;

import io.nexus.contracts.event.EventLog;
import io.nexus.contracts.event.EventLogContract;
import io.nexus.contracts.event.UnitOfWork;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** The same contract the in-memory EventLog passes, against PostgreSQL. */
@SpringBootTest
@ActiveProfiles("embedded")
class JdbcEventLogTest extends EventLogContract {
  @Autowired EventLog log;
  @Autowired UnitOfWork work;
  @Autowired JdbcClient jdbc;
  long run;
  long otherRun;

  @BeforeEach
  void clean() {
    jdbc.sql("delete from event_delivery_failure").update();
    jdbc.sql("delete from processed_event").update();
    jdbc.sql("delete from event").update();
    run = jdbc.sql("insert into sim_run (seed) values (1) returning id").query(Long.class).single();
    otherRun = jdbc.sql("insert into sim_run (seed) values (1) returning id").query(Long.class).single();
  }

  @Override protected EventLog log() { return log; }
  @Override protected UnitOfWork work() { return work; }
  @Override protected long runId() { return run; }
  @Override protected long otherRunId() { return otherRun; }
}
