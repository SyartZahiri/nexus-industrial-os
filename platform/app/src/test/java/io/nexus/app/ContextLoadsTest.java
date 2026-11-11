package io.nexus.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class ContextLoadsTest {
  @Autowired JdbcClient jdbc;

  @Test
  void startsAgainstRealPostgres() {
    String version = jdbc.sql("select version()").query(String.class).single();
    assertThat(version).startsWith("PostgreSQL 18");
  }
}
