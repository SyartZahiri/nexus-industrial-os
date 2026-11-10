package io.nexus.app.event;

import io.nexus.contracts.event.EventIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The current simulation run. Every start and every reset opens a new run, so event ids never collide. */
@Component
@DependsOnDatabaseInitialization // Flyway must have run first
public class SimRun {
  private final JdbcClient jdbc;
  private long seed;
  private EventIds ids;

  SimRun(JdbcClient jdbc, @Value("${nexus.sim.seed}") long seed) {
    this.jdbc = jdbc;
    restart(seed);
  }

  public synchronized void restart(long seed) {
    this.seed = seed;
    long id = jdbc.sql("insert into sim_run (seed) values (:seed) returning id").param("seed", seed)
        .query(Long.class).single();
    this.ids = new EventIds(id);
  }

  public synchronized long id() {
    return ids.runId();
  }

  public synchronized long seed() {
    return seed;
  }

  /** Run id and event counter in one object, so a publisher never combines an old run with a new counter. */
  public synchronized EventIds ids() {
    return ids;
  }
}
