package io.nexus.app;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@SpringBootApplication(scanBasePackages = "io.nexus")
public class NexusApplication {
  public static void main(String[] args) {
    SpringApplication.run(NexusApplication.class, args);
  }

  /**
   * The event bus holds a connection while delivering in further transactions, so the pool needs headroom;
   * a lock wait that can never resolve (see EventBus threading rule) fails after 5 s instead of hanging.
   * Same values for the postgres profile in application.yml.
   */
  static void applyPoolRules(HikariDataSource ds) {
    ds.setMaximumPoolSize(20);
    ds.setConnectionInitSql("set lock_timeout = '5s'");
  }

  /** Real PostgreSQL without an installation; used for local dev and tests. Data is discarded on shutdown. */
  @Configuration
  @Profile("embedded")
  static class EmbeddedDatabase {
    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
      return EmbeddedPostgres.builder().start();
    }

    /** Pooled: the event bus opens a transaction per consumer and event, a fresh connection each time is too slow. */
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(EmbeddedPostgres pg) {
      var ds = new HikariDataSource();
      ds.setJdbcUrl(pg.getJdbcUrl("postgres", "postgres"));
      ds.setUsername("postgres");
      applyPoolRules(ds);
      return ds;
    }
  }
}
