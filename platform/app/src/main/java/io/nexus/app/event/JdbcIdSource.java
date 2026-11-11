package io.nexus.app.event;

import io.nexus.contracts.port.IdSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** IdSource on PostgreSQL. The row lock of the upsert serialises concurrent callers per prefix. */
@Component
class JdbcIdSource implements IdSource {
  private final JdbcClient jdbc;

  JdbcIdSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String next(String prefix) {
    long value = jdbc.sql("""
        insert into id_counter (prefix, value) values (:p, 1)
        on conflict (prefix) do update set value = id_counter.value + 1
        returning value
        """).param("p", prefix).query(Long.class).single();
    return IdSource.format(prefix, value);
  }

  @Override
  public void reset() {
    jdbc.sql("delete from id_counter").update();
  }
}
