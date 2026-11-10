package io.nexus.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("embedded")
class StockCapacityConcurrencyTest {
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  @AfterEach
  void restore() {
    jdbc.sql("update stock set on_hand = 0 where location_id = 'FG-A'").update();
  }

  /** Two receipts into different rows of the same location: each fits alone, together they exceed capacity. */
  @Test
  void concurrentReceiptsCannotJointlyExceedLocationCapacity() throws Exception {
    jdbc.sql("update stock set on_hand = 0 where location_id = 'FG-A'").update();
    int capacity = jdbc.sql("select capacity from storage_location where id = 'FG-A'").query(Integer.class).single();
    int each = capacity / 2 + 10;

    var bothUpdated = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> a = pool.submit(() -> receive("FG-GH200", each, bothUpdated));
      Future<Boolean> b = pool.submit(() -> receive("FG-AW50", each, bothUpdated));
      boolean okA = a.get(30, TimeUnit.SECONDS);
      boolean okB = b.get(30, TimeUnit.SECONDS);
      assertThat(okA && okB).as("both receipts committed").isFalse();
    } finally {
      pool.shutdownNow();
    }
    int total = jdbc.sql("select sum(on_hand) from stock where location_id = 'FG-A'").query(Integer.class).single();
    assertThat(total).isLessThanOrEqualTo(capacity);
  }

  private boolean receive(String materialId, int qty, CyclicBarrier bothStarted) {
    try {
      tx.executeWithoutResult(s -> {
        try {
          bothStarted.await(10, TimeUnit.SECONDS); // both transactions are open before either writes
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
        jdbc.sql("update stock set on_hand = :q where material_id = :m and location_id = 'FG-A'")
            .param("q", qty).param("m", materialId).update();
        try {
          Thread.sleep(300); // keep the transaction open so the other one checks while this is uncommitted
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }
}
