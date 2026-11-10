package io.nexus.app.event;

import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.sim.SimTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The current simulation run. Every start and every reset opens a new run, so event ids never collide. */
@Component
@DependsOnDatabaseInitialization // Flyway must have run first
public class SimRun {
  private final JdbcClient jdbc;
  private long id;
  private long seed;
  private EventIds ids;

  SimRun(JdbcClient jdbc, @Value("${nexus.sim.seed}") long seed) {
    this.jdbc = jdbc;
    restart(seed);
  }

  public synchronized void restart(long seed) {
    this.seed = seed;
    this.id = jdbc.sql("insert into sim_run (seed) values (:seed) returning id").param("seed", seed)
        .query(Long.class).single();
    this.ids = new EventIds(id, seed);
  }

  public synchronized long id() { return id; }
  public synchronized long seed() { return seed; }
  public synchronized EventIds ids() { return ids; }

  @Configuration
  static class Beans {
    @Bean
    SimTime simTime() {
      return new SimTime();
    }
  }
}
