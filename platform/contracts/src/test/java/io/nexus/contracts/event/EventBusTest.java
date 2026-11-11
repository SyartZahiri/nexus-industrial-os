package io.nexus.contracts.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.sim.SimTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EventBusTest {
  final MemoryUnitOfWork work = new MemoryUnitOfWork();
  final MemoryEventLog log = new MemoryEventLog(work);
  final SimTime time = new SimTime();
  final EventIds ids = new EventIds(1);
  final EventBus bus = new EventBus(log, work, time, () -> ids);
  final List<String> seen = new ArrayList<>();

  private EventConsumer consumer(String name, Consumer<NexusEvent> body) {
    return new EventConsumer() {
      public String name() { return name; }
      public void on(NexusEvent e) {
        body.accept(e);
        seen.add(name + ":" + e.type());
      }
    };
  }

  private NexusEvent note(String type) {
    return bus.publish(type, "test", Severity.INFO, "PO-1", new Payloads.Note("x"));
  }

  @Test
  void deliversToAllConsumersInNameOrder() {
    bus.register(consumer("z", e -> {}));
    bus.register(consumer("a", e -> {}));
    note(EventTypes.SCENARIO_STARTED);
    assertThat(seen).containsExactly("a:scenario.started", "z:scenario.started");
  }

  @Test
  void eventPublishedByAConsumerIsDeliveredAfterTheCausingEventReachedEveryone() {
    bus.register(consumer("a", e -> {
      if (e.type().equals(EventTypes.SCENARIO_STARTED)) note(EventTypes.SCENARIO_FINISHED);
    }));
    bus.register(consumer("z", e -> {}));
    note(EventTypes.SCENARIO_STARTED);
    assertThat(seen).containsExactly(
        "a:scenario.started", "z:scenario.started", "a:scenario.finished", "z:scenario.finished");
  }

  @Test
  void eventPublishedInARolledBackUnitIsNeitherStoredNorDelivered() {
    bus.register(consumer("a", e -> {}));
    String[] id = new String[1];
    assertThatThrownBy(() -> work.inNewTransaction(() -> {
      id[0] = note(EventTypes.SCENARIO_STARTED).eventId();
      throw new IllegalStateException("business failure");
    })).hasMessage("business failure");
    assertThat(seen).isEmpty();
    assertThat(log.find(id[0])).isEmpty();
  }

  @Test
  void redeliveryHasNoSecondEffect() {
    bus.register(consumer("a", e -> {}));
    NexusEvent e = note(EventTypes.SCENARIO_STARTED);
    bus.redeliver(e.eventId());
    bus.redeliver(e.eventId());
    assertThat(seen).hasSize(1);
  }

  @Test
  void failingConsumerDoesNotBlockOthersAndIsRetried() {
    boolean[] fail = {true};
    bus.register(consumer("a", e -> {
      if (fail[0]) throw new IllegalStateException("boom");
    }));
    bus.register(consumer("z", e -> {}));
    note(EventTypes.SCENARIO_STARTED);
    assertThat(seen).containsExactly("z:scenario.started");
    assertThat(log.openFailures(1, EventBus.MAX_ATTEMPTS)).hasSize(1);

    fail[0] = false;
    bus.retryFailed();
    assertThat(seen).containsExactly("z:scenario.started", "a:scenario.started");
    assertThat(log.openFailures(1, EventBus.MAX_ATTEMPTS)).isEmpty();
    bus.retryFailed();
    assertThat(seen).as("no further delivery once processed").hasSize(2);
  }

  @Test
  void givesUpAfterFiveAttemptsAndReportsExactlyOnce() {
    int[] calls = {0};
    bus.register(consumer("a", e -> {
      if (e.type().equals(EventTypes.SCENARIO_STARTED)) {
        calls[0]++;
        throw new IllegalStateException("immer kaputt");
      }
    }));
    List<NexusEvent> reports = new ArrayList<>();
    bus.register(consumer("z", e -> {
      if (e.type().equals(EventTypes.SYSTEM_CONSUMER_FAILED)) reports.add(e);
    }));
    NexusEvent original = note(EventTypes.SCENARIO_STARTED);
    for (int i = 0; i < 6; i++) bus.retryFailed();

    assertThat(calls[0]).isEqualTo(EventBus.MAX_ATTEMPTS);
    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).severity()).isEqualTo(Severity.ERROR);
    assertThat(reports.get(0).correlationId()).isEqualTo("PO-1");
    assertThat(reports.get(0).payload()).isEqualTo(
        new Payloads.ConsumerFailed("a", original.eventId(), EventTypes.SCENARIO_STARTED, "IllegalStateException"));
  }

  @Test
  void aFailingConsumerOfTheFailureReportDoesNotLoop() {
    bus.register(consumer("a", e -> {
      throw new IllegalStateException("alles kaputt");
    }));
    note(EventTypes.SCENARIO_STARTED);
    for (int i = 0; i < 20; i++) bus.retryFailed();
    // one original event and exactly one report about it; the failing report itself is not reported again
    assertThat(log.all()).extracting(NexusEvent::type)
        .containsExactly(EventTypes.SCENARIO_STARTED, EventTypes.SYSTEM_CONSUMER_FAILED);
  }

  /** Spec 7: no consumer sees an effect before its cause, also not one that failed on the cause. */
  @Test
  void consumerThatFailedOnAnEventGetsLaterEventsOnlyAfterTheRetry() {
    boolean[] fail = {true};
    bus.register(consumer("b", e -> {
      if (fail[0] && e.type().equals(EventTypes.SCENARIO_STARTED)) throw new IllegalStateException("kurz weg");
    }));
    note(EventTypes.SCENARIO_STARTED);
    note(EventTypes.SCENARIO_FINISHED);
    assertThat(seen).as("the later event waits behind the failed one").isEmpty();

    fail[0] = false;
    bus.retryFailed();
    assertThat(seen).containsExactly("b:scenario.started", "b:scenario.finished");
    bus.retryFailed();
    assertThat(seen).hasSize(2);
  }

  @Test
  void afterGivingUpTheParkedEventsAreDeliveredInOrder() {
    bus.register(consumer("b", e -> {
      if (e.type().equals(EventTypes.SCENARIO_STARTED)) throw new IllegalStateException("immer kaputt");
    }));
    note(EventTypes.SCENARIO_STARTED);
    note(EventTypes.SCENARIO_FINISHED);
    for (int i = 0; i < 8; i++) bus.retryFailed();
    assertThat(seen).containsExactly("b:scenario.finished", "b:system.consumer_failed");
  }

  /** The failure report is visible to every client; only business messages may appear in it. */
  @Test
  void failureReportCarriesOnlyTheExceptionClassForUnexpectedErrors() {
    List<NexusEvent> reports = new ArrayList<>();
    bus.register(consumer("a", e -> {
      if (e.type().equals(EventTypes.SCENARIO_STARTED)) throw new IllegalStateException("jdbc:postgresql://geheim");
      if (e.type().equals(EventTypes.SCENARIO_FINISHED)) throw io.nexus.contracts.DomainException.conflict("bereits verladen");
    }));
    bus.register(consumer("z", e -> {
      if (e.type().equals(EventTypes.SYSTEM_CONSUMER_FAILED)) reports.add(e);
    }));
    note(EventTypes.SCENARIO_STARTED);
    note(EventTypes.SCENARIO_FINISHED);
    for (int i = 0; i < 12; i++) bus.retryFailed();
    assertThat(reports).extracting(r -> ((Payloads.ConsumerFailed) r.payload()).error())
        .containsExactly("IllegalStateException", "bereits verladen");
  }

  @Test
  void failingBookkeepingDoesNotEscapeAndOtherConsumersStillReceive() {
    var broken = new MemoryEventLog(work) {
      @Override
      public int recordFailure(String consumer, String eventId, String error) {
        throw new IllegalStateException("Datenbank weg");
      }
    };
    List<Throwable> internal = new ArrayList<>();
    var fragile = new EventBus(broken, work, time, () -> new EventIds(9), internal::add);
    fragile.register(consumer("a", e -> {
      throw new IllegalStateException("boom");
    }));
    fragile.register(consumer("z", e -> {}));
    fragile.publish(EventTypes.SCENARIO_STARTED, "test", Severity.INFO, null, new Payloads.Note("x"));
    assertThat(seen).containsExactly("z:scenario.started");
    assertThat(internal).extracting(Throwable::getMessage).containsExactly("Datenbank weg");
  }

  @Test
  void anErrorThrownByAConsumerDoesNotSilenceTheBus() {
    boolean[] fail = {true};
    bus.register(consumer("a", e -> {
      if (fail[0]) {
        fail[0] = false;
        throw new AssertionError("kein RuntimeException");
      }
    }));
    note(EventTypes.SCENARIO_STARTED);
    bus.retryFailed();
    note(EventTypes.SCENARIO_FINISHED);
    assertThat(seen).containsExactly("a:scenario.started", "a:scenario.finished");
  }

  @Test
  void wrongPayloadTypeIsRejectedAndNothingIsStored() {
    assertThatThrownBy(() -> bus.publish(EventTypes.TRANSPORT_DELIVERED, "test", Severity.INFO, null,
        new Payloads.MachineFault("CNC-01", "TOOL", "Werkzeugbruch", true)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TransportDelivered");
    assertThat(log.all()).isEmpty();
  }

  @Test
  void duplicateConsumerNameFailsAtRegistration() {
    bus.register(consumer("a", e -> {}));
    assertThatThrownBy(() -> bus.register(consumer("a", e -> {}))).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void eventCarriesSimulationTimeAndRunScopedId() {
    time.advance(300);
    NexusEvent first = note(EventTypes.SCENARIO_STARTED);
    time.advance(100);
    NexusEvent second = note(EventTypes.SCENARIO_FINISHED);
    assertThat(first.eventId()).isEqualTo("1-0");
    assertThat(second.eventId()).isEqualTo("1-1");
    assertThat(first.simTimeMs()).isEqualTo(300);
    assertThat(second.simTimeMs()).isEqualTo(400);
  }
}
