package io.nexus.app.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.sim.SimTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("embedded")
class JdbcEventPublisherTest {
  static final List<String> seenByA = new ArrayList<>();
  static final List<String> seenByB = new ArrayList<>();
  static boolean aFails;

  @TestConfiguration
  static class Consumers {
    @Bean
    EventConsumer consumerA(JdbcClient jdbc) {
      return new EventConsumer() {
        public String name() { return "test-a"; }
        public void on(NexusEvent e) {
          // a real side effect, so the test can prove it rolls back together with the marker
          jdbc.sql("insert into maintenance_window (machine_id, start_ms, end_ms) values ('CNC-01', 0, 1)").update();
          if (aFails) throw new IllegalStateException("boom");
          seenByA.add(e.type());
        }
      };
    }

    @Bean
    EventConsumer consumerB() {
      return new EventConsumer() {
        public String name() { return "test-b"; }
        public void on(NexusEvent e) { seenByB.add(e.type()); }
      };
    }
  }

  @Autowired EventPublisher events;
  @Autowired JdbcEventPublisher publisher;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;
  @Autowired SimTime time;

  @BeforeEach
  void clean() {
    seenByA.clear();
    seenByB.clear();
    aFails = false;
    jdbc.sql("delete from processed_event").update();
    jdbc.sql("delete from event").update();
    jdbc.sql("delete from maintenance_window").update();
  }

  private int count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
  }

  @Test
  void storesAndDeliversAfterCommit() {
    tx.executeWithoutResult(s -> {
      events.publish("order.created", "smartfactory", Severity.INFO, "PO-1", Map.of("quantity", 5));
      assertThat(seenByA).as("not delivered before commit").isEmpty();
    });
    assertThat(seenByA).containsExactly("order.created");
    assertThat(seenByB).containsExactly("order.created");
    assertThat(jdbc.sql("select payload->>'quantity' from event where correlation_id = 'PO-1'")
        .query(String.class).single()).isEqualTo("5");
  }

  @Test
  void rolledBackTransactionLeavesNoEventAndCallsNoConsumer() {
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
      events.publish("order.created", "smartfactory", Severity.INFO, "PO-1", Map.of());
      throw new IllegalStateException("business failure");
    })).hasMessage("business failure");
    assertThat(count("event")).isZero();
    assertThat(seenByA).isEmpty();
    assertThat(seenByB).isEmpty();
  }

  @Test
  void redeliveryHasNoSecondEffect() {
    NexusEvent e = events.publish("order.created", "smartfactory", Severity.INFO, "PO-1", Map.of());
    publisher.redeliver(e.eventId());
    publisher.redeliver(e.eventId());
    assertThat(seenByA).hasSize(1);
    assertThat(seenByB).hasSize(1);
    assertThat(count("maintenance_window")).isEqualTo(1);
  }

  @Test
  void failingConsumerDoesNotBlockOthersAndCanBeRetried() {
    aFails = true;
    NexusEvent e = events.publish("order.created", "smartfactory", Severity.INFO, "PO-1", Map.of());
    assertThat(seenByB).as("B still receives the event").hasSize(1);
    assertThat(count("maintenance_window")).as("A's side effect rolled back").isZero();

    aFails = false;
    publisher.redeliver(e.eventId());
    assertThat(seenByA).hasSize(1);
    assertThat(seenByB).as("B is not called twice").hasSize(1);
    assertThat(count("maintenance_window")).isEqualTo(1);
  }

  @Test
  void eventsKeepPublishOrderAndCarrySimulationTime() {
    long before = time.nowMs();
    events.publish("a", "test", Severity.INFO, null, Map.of());
    time.advance(100);
    events.publish("b", "test", Severity.INFO, null, Map.of());
    assertThat(jdbc.sql("select type from event order by seq").query(String.class).list()).containsExactly("a", "b");
    assertThat(jdbc.sql("select sim_time_ms from event order by seq").query(Long.class).list())
        .containsExactly(before, before + 100);
  }

  @Test
  void payloadWithNullCorrelationAndNestedValuesRoundTrips() {
    NexusEvent e = events.publish("decision", "flowpilot", Severity.INFO, null,
        Map.of("chosen", "AGV-02", "candidates", List.of("AGV-01", "AGV-02"), "reason", "kürzeste Anfahrt"));
    publisher.redeliver(e.eventId()); // forces a read back from the database
    assertThat(jdbc.sql("select payload->'candidates'->>1 from event").query(String.class).single()).isEqualTo("AGV-02");
    assertThat(jdbc.sql("select payload->>'reason' from event").query(String.class).single()).isEqualTo("kürzeste Anfahrt");
  }
}
