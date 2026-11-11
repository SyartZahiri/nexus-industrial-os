package io.nexus.contracts.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every EventLog/UnitOfWork pair must show. Runs against the in-memory implementation here
 * and against PostgreSQL in platform/app, so both runtimes behave the same.
 */
public abstract class EventLogContract {
  protected abstract EventLog log();

  protected abstract UnitOfWork work();

  /** A run id that exists in the store under test. */
  protected abstract long runId();

  /** A second, different run id that exists in the store under test. */
  protected abstract long otherRunId();

  private int n;

  protected NexusEvent stored(long runId) {
    var e = new NexusEvent(runId + "-c" + n++, 100, EventTypes.SCENARIO_STARTED, "test", Severity.INFO, "PO-1",
        new Payloads.Note("x"));
    log().append(runId, e);
    return e;
  }

  @Test
  void markProcessedIsTrueOnceThenFalse() {
    NexusEvent e = stored(runId());
    assertThat(log().markProcessed("a", e.eventId())).isTrue();
    assertThat(log().markProcessed("a", e.eventId())).isFalse();
    assertThat(log().markProcessed("b", e.eventId())).as("other consumer").isTrue();
  }

  @Test
  void markerSetInARolledBackUnitIsGone() {
    NexusEvent e = stored(runId());
    assertThatThrownBy(() -> work().inNewTransaction(() -> {
      assertThat(log().markProcessed("a", e.eventId())).isTrue();
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(log().markProcessed("a", e.eventId())).as("marker was rolled back").isTrue();
  }

  @Test
  void eventAppendedInARolledBackUnitIsGone() {
    String[] id = new String[1];
    assertThatThrownBy(() -> work().inNewTransaction(() -> {
      id[0] = stored(runId()).eventId();
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(log().find(id[0])).isEmpty();
  }

  @Test
  void afterCommitRunsAfterTheUnitAndNotOnRollback() {
    var order = new java.util.ArrayList<String>();
    work().inNewTransaction(() -> {
      work().afterCommit(() -> order.add("after"));
      order.add("inside");
    });
    assertThat(order).containsExactly("inside", "after");

    assertThatThrownBy(() -> work().inNewTransaction(() -> {
      work().afterCommit(() -> order.add("never"));
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(order).doesNotContain("never");

    work().afterCommit(() -> order.add("immediate")); // no unit active
    assertThat(order).endsWith("immediate");
  }

  @Test
  void failuresCountUpAndClear() {
    NexusEvent e = stored(runId());
    assertThat(log().recordFailure("a", e.eventId(), "x")).isEqualTo(1);
    assertThat(log().recordFailure("a", e.eventId(), "x")).isEqualTo(2);
    assertThat(log().recordFailure("a", e.eventId(), "y")).isEqualTo(3);
    assertThat(log().openFailures(runId(), 5)).extracting(EventLog.Failure::attempts).containsExactly(3);
    assertThat(log().openFailures(runId(), 3)).as("3 attempts is not fewer than 3").isEmpty();
    log().clearFailure("a", e.eventId());
    assertThat(log().openFailures(runId(), 5)).isEmpty();
  }

  @Test
  void parkedEventIsOpenWithZeroAttemptsAndBlocksTheConsumer() {
    NexusEvent e = stored(runId());
    assertThat(log().hasOpenFailure("a", runId(), 5)).isFalse();
    log().park("a", e.eventId());
    log().park("a", e.eventId()); // idempotent
    assertThat(log().openFailures(runId(), 5)).extracting(EventLog.Failure::attempts).containsExactly(0);
    assertThat(log().hasOpenFailure("a", runId(), 5)).isTrue();
    assertThat(log().hasOpenFailure("b", runId(), 5)).isFalse();
    assertThat(log().hasOpenFailure("a", otherRunId(), 5)).isFalse();
    assertThat(log().recordFailure("a", e.eventId(), "x")).as("a retry of a parked event is attempt 1").isEqualTo(1);
  }

  @Test
  void openFailuresAreScopedToTheRunAndOrderedByPublishOrder() {
    NexusEvent first = stored(runId());
    NexusEvent second = stored(runId());
    NexusEvent foreign = stored(otherRunId());
    log().recordFailure("a", second.eventId(), "x");
    log().recordFailure("a", first.eventId(), "x");
    log().recordFailure("a", foreign.eventId(), "x");
    assertThat(log().openFailures(runId(), 5)).extracting(f -> f.event().eventId())
        .containsExactly(first.eventId(), second.eventId());
  }

  @Test
  void findReturnsTheStoredEventWithEqualPayload() {
    var payload = new Payloads.TransportDelivered("TO-1", TransportKind.SUPPLY, "PO-1", "RM-GH200", 5,
        Place.machineIn("CNC-01"));
    var e = new NexusEvent(runId() + "-find", 4200, EventTypes.TRANSPORT_DELIVERED, "flowpilot", Severity.SUCCESS,
        "PO-1", payload);
    log().append(runId(), e);
    assertThat(log().find(e.eventId())).contains(e);
    assertThat(log().find("gibt-es-nicht")).isEmpty();
  }

  @Test
  void listPayloadsAndNullCorrelationSurvive() {
    var e = new NexusEvent(runId() + "-dec", 1, EventTypes.DECISION, "flowpilot", Severity.INFO, null,
        new Payloads.Decision("TO-1", "AGV-02", List.of("AGV-01", "AGV-02"), "kürzeste Anfahrt"));
    log().append(runId(), e);
    assertThat(log().find(e.eventId())).contains(e);
  }
}
