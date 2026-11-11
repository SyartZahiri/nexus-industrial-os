package io.nexus.app.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.IdSourceContract;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class JdbcIdSourceTest extends IdSourceContract {
  @Autowired IdSource ids;
  @Autowired UnitOfWork work;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    ids.reset();
  }

  @Override protected IdSource ids() { return ids; }
  @Override protected UnitOfWork work() { return work; }

  @Override
  protected void presetCounter(String prefix, long value) {
    jdbc.sql("insert into id_counter (prefix, value) values (:p, :v)").param("p", prefix).param("v", value).update();
  }

  @Test
  void concurrentCallersNeverGetTheSameId() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<List<String>>> futures = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        futures.add(pool.submit(() -> {
          List<String> mine = new ArrayList<>();
          for (int i = 0; i < 50; i++) mine.add(ids.next("TO"));
          return mine;
        }));
      }
      var all = new HashSet<String>();
      for (Future<List<String>> f : futures) all.addAll(f.get());
      assertThat(all).hasSize(400);
    } finally {
      pool.shutdownNow();
    }
  }
}
