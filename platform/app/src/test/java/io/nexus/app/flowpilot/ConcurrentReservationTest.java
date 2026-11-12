package io.nexus.app.flowpilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.port.MaterialService.ReservationRequest;
import io.nexus.contracts.port.MaterialService.ReservationResult;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.material.MaterialServiceImpl;
import io.nexus.flowpilot.store.StockStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** 50 parallel reservations on PostgreSQL never overbook (Spec 17). */
@SpringBootTest
@ActiveProfiles("embedded")
class ConcurrentReservationTest {
  @Autowired StockStore stock;
  @Autowired EventPublisher events;
  @Autowired IdSource ids;
  @Autowired UnitOfWork work;
  @Autowired SimTime time;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void startFromMasterData() {
    FlowPilotTestData.reset(jdbc);
  }

  @Test
  void fiftyThreadsReserveThirtyUnitsWithoutOverbooking() throws Exception {
    var materials = new MaterialServiceImpl(stock, events, ids, work, time);
    var start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(50);
    List<Future<ReservationResult>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 50; i++) {
        String requestId = "REQ-PAR-" + i;
        futures.add(pool.submit(() -> {
          start.await();
          return materials.reserve(new ReservationRequest(requestId, "PO-PAR-" + requestId,
              List.of(new Line("RM-FP12", 1))));
        }));
      }
      start.countDown();
      int success = 0, shortage = 0;
      for (Future<ReservationResult> f : futures) {
        ReservationResult r = f.get(60, TimeUnit.SECONDS); // any exception fails the test here
        if (r instanceof ReservationResult.Success) success++;
        else if (r instanceof ReservationResult.Shortage) shortage++;
      }
      assertThat(success).isEqualTo(30);
      assertThat(shortage).isEqualTo(20);
    } finally {
      pool.shutdownNow();
    }
    int reserved = jdbc.sql("select reserved from stock where material_id = 'RM-FP12' and location_id = 'RAW-A'")
        .query(Integer.class).single();
    assertThat(reserved).isEqualTo(30);
    assertThat(jdbc.sql("select count(*) from reservation where material_id = 'RM-FP12'").query(Integer.class).single())
        .isEqualTo(30);
  }
}
