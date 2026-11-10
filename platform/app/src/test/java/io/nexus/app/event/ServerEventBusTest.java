package io.nexus.app.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** The EventBus from the core, wired to PostgreSQL and Spring transactions. */
@SpringBootTest
@ActiveProfiles("embedded")
class ServerEventBusTest {
  static final List<NexusEvent> seenByA = new ArrayList<>();
  static final List<NexusEvent> seenByB = new ArrayList<>();
  static boolean aFails;

  @TestConfiguration
  static class Consumers {
    @Bean
    EventConsumer consumerA(JdbcClient jdbc) {
      return new EventConsumer() {
        public String name() { return "test-a"; }
        public void on(NexusEvent e) {
          // a real write, so the test can prove it rolls back together with the marker
          jdbc.sql("insert into maintenance_window (machine_id, start_ms, end_ms) values ('CNC-01', 0, 1)").update();
          if (aFails) throw new IllegalStateException("boom");
          seenByA.add(e);
        }
      };
    }

    @Bean
    EventConsumer consumerB() {
      return new EventConsumer() {
        public String name() { return "test-b"; }
        public void on(NexusEvent e) { seenByB.add(e); }
      };
    }
  }

  @Autowired EventPublisher events;
  @Autowired EventBus bus;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    seenByA.clear();
    seenByB.clear();
    aFails = false;
    jdbc.sql("delete from event_delivery_failure").update();
    jdbc.sql("delete from processed_event").update();
    jdbc.sql("delete from event").update();
    jdbc.sql("delete from maintenance_window").update();
  }

  private int count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
  }

  private NexusEvent orderCreated() {
    return events.publish(EventTypes.ORDER_CREATED, "smartfactory", Severity.INFO, "PO-1",
        new Payloads.OrderCreated("PO-1", "GH200", 5, Priority.EILIG, 3_600_000));
  }

  @Test
  void storesAndDeliversAfterCommit() {
    tx.executeWithoutResult(s -> {
      orderCreated();
      assertThat(seenByA).as("not delivered before commit").isEmpty();
    });
    assertThat(seenByA).hasSize(1);
    assertThat(seenByB).hasSize(1);
    assertThat(jdbc.sql("select payload->>'priority' from event where correlation_id = 'PO-1'")
        .query(String.class).single()).isEqualTo("EILIG");
  }

  @Test
  void rolledBackTransactionLeavesNoEventAndCallsNoConsumer() {
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
      orderCreated();
      throw new IllegalStateException("business failure");
    })).hasMessage("business failure");
    assertThat(count("event")).isZero();
    assertThat(seenByA).isEmpty();
    assertThat(seenByB).isEmpty();
  }

  @Test
  void failingConsumerRollsBackItsOwnWritesAndIsRetried() {
    aFails = true;
    orderCreated();
    assertThat(seenByB).as("B still receives the event").hasSize(1);
    assertThat(count("maintenance_window")).as("A's write rolled back").isZero();
    assertThat(count("event_delivery_failure")).isEqualTo(1);

    aFails = false;
    bus.retryFailed();
    assertThat(seenByA).hasSize(1);
    assertThat(seenByB).as("B is not called twice").hasSize(1);
    assertThat(count("maintenance_window")).isEqualTo(1);
    assertThat(count("event_delivery_failure")).isZero();
  }

  @Test
  void redeliverInsideAnOpenTransactionWaitsForCommit() {
    NexusEvent e = orderCreated();
    jdbc.sql("delete from processed_event").update(); // make it deliverable again
    seenByA.clear();
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
      bus.redeliver(e.eventId());
      assertThat(seenByA).as("nothing delivered while the transaction is open").isEmpty();
      throw new IllegalStateException("rolled back");
    })).hasMessage("rolled back");
    assertThat(seenByA).as("and nothing after the rollback").isEmpty();
  }

  @Test
  void consumerReceivesTheSamePayloadOnFirstDeliveryAndAfterTheDatabaseRoundTrip() {
    NexusEvent published = events.publish(EventTypes.DECISION, "flowpilot", Severity.INFO, null,
        new Payloads.Decision("TO-1", "AGV-02", List.of("AGV-01", "AGV-02"), "kürzeste Anfahrt"));
    jdbc.sql("delete from processed_event").update();
    bus.redeliver(published.eventId());
    assertThat(seenByB).hasSize(2);
    assertThat(seenByB.get(1)).isEqualTo(seenByB.get(0)).isEqualTo(published);
  }
}
