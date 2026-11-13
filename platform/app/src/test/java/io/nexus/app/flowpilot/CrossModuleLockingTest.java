package io.nexus.app.flowpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.port.MaterialService.ReservationRequest;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.smartfactory.SmartFactory;
import io.nexus.smartfactory.orders.NewOrder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * FlowPilot and SmartFactory together on the server. FlowPilot's events are delivered to SmartFactory, and
 * SmartFactory calls FlowPilot. If the two facades lock on different monitors, a thread inside FlowPilot that
 * delivers and a thread inside SmartFactory that reserves wait for each other forever.
 */
@SpringBootTest
@ActiveProfiles("embedded")
class CrossModuleLockingTest {
  @Autowired FlowPilot flowPilot;
  @Autowired SmartFactory smartFactory;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  @AfterEach
  void freshTables() {
    jdbc.sql("delete from operation_material").update();
    jdbc.sql("delete from operation").update();
    jdbc.sql("delete from production_order").update();
    FlowPilotTestData.reset(jdbc);
  }

  @Test
  void flowPilotAndSmartFactoryCalledInParallelNeverDeadlock() {
    assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
      ExecutorService pool = Executors.newFixedThreadPool(2);
      var start = new CountDownLatch(1);
      try {
        Future<?> reserving = pool.submit(() -> {
          start.await();
          for (int i = 0; i < 40; i++) {
            String request = "LOCK-" + i;
            flowPilot.materials().reserve(new ReservationRequest(request, "LOCK-ORDER-" + i,
                List.of(new Line("RM-FP12", 1))));
            flowPilot.materials().release(request);   // stock.changed reaches SmartFactory on this thread
          }
          return null;
        });
        Future<?> ordering = pool.submit(() -> {
          start.await();
          for (int i = 0; i < 40; i++) {
            smartFactory.createOrder(new NewOrder("AW50", 1, Priority.NORMAL, 3_600_000));
          }
          return null;
        });
        start.countDown();
        reserving.get(80, TimeUnit.SECONDS);
        ordering.get(80, TimeUnit.SECONDS);
      } finally {
        pool.shutdownNow();
      }
    });
    assertThat(smartFactory.orders()).hasSize(40);
  }
}
