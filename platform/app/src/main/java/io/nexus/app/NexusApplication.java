package io.nexus.app;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
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

  /** Real PostgreSQL without an installation; used for local dev and tests. Data is discarded on shutdown. */
  @Configuration
  @Profile("embedded")
  static class EmbeddedDatabase {
    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
      return EmbeddedPostgres.builder().start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres pg) {
      return pg.getPostgresDatabase();
    }
  }
}
