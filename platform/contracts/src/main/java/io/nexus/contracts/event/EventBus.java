package io.nexus.contracts.event;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.sim.SimTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * Stores events and delivers them to consumers. Runs unchanged on the server and in the browser;
 * storage and transactions come from {@link EventLog} and {@link UnitOfWork}.
 *
 * <ul>
 *   <li>An event is stored in the publisher's unit of work and delivered after it commits.</li>
 *   <li>Delivery is first in, first out: an event a consumer publishes is delivered after the event
 *       that caused it has reached every consumer.</li>
 *   <li>Each consumer processes an event at most once with effect.</li>
 *   <li>A failed delivery is retried by {@link #retryFailed()} up to {@link #MAX_ATTEMPTS} times,
 *       then reported once as {@code system.consumer_failed}.</li>
 * </ul>
 */
public final class EventBus implements EventPublisher {
  public static final int MAX_ATTEMPTS = 5;
  private static final String SOURCE = "nexus";

  private final EventLog log;
  private final UnitOfWork work;
  private final SimTime time;
  private final Supplier<EventIds> ids;
  private final List<EventConsumer> consumers = new ArrayList<>();
  private final Deque<NexusEvent> queue = new ArrayDeque<>();
  private boolean draining;

  /** @param ids the ids of the current run; read once per publish so run id and counter always match */
  public EventBus(EventLog log, UnitOfWork work, SimTime time, Supplier<EventIds> ids) {
    this.log = log;
    this.work = work;
    this.time = time;
    this.ids = ids;
  }

  /** Consumers are called in name order. A second consumer with the same name is a configuration error. */
  public synchronized void register(EventConsumer consumer) {
    for (EventConsumer c : consumers) {
      if (c.name().equals(consumer.name())) {
        throw new IllegalStateException("Verbraucher doppelt registriert: " + c.name());
      }
    }
    consumers.add(consumer);
    consumers.sort(Comparator.comparing(EventConsumer::name));
  }

  @Override
  public NexusEvent publish(String type, String source, Severity severity, String correlationId, Payload payload) {
    Class<? extends Payload> expected = EventTypes.payloadType(type);
    if (!expected.isInstance(payload)) {
      throw new IllegalArgumentException("Ereignis " + type + " erwartet " + expected.getSimpleName());
    }
    EventIds current = ids.get();
    var event = new NexusEvent(current.next(), time.nowMs(), type, source, severity, correlationId, payload);
    log.append(current.runId(), event);
    work.afterCommit(() -> enqueue(event));
    return event;
  }

  /** Delivers a stored event again; consumers that already processed it are skipped. */
  public void redeliver(String eventId) {
    NexusEvent event = log.find(eventId).orElseThrow(() -> DomainException.notFound("Ereignis", eventId));
    work.afterCommit(() -> enqueue(event));
  }

  /** Called at the end of every simulation step: retries deliveries that failed fewer than MAX_ATTEMPTS times. */
  public synchronized void retryFailed() {
    for (EventLog.Failure failure : log.openFailures(ids.get().runId(), MAX_ATTEMPTS)) {
      for (EventConsumer consumer : List.copyOf(consumers)) {
        if (consumer.name().equals(failure.consumer()) && deliver(consumer, failure.event())) {
          work.inNewTransaction(() -> log.clearFailure(failure.consumer(), failure.event().eventId()));
        }
      }
    }
  }

  private synchronized void enqueue(NexusEvent event) {
    queue.addLast(event);
    if (draining) return; // the outermost call delivers it, after the event being processed right now
    draining = true;
    try {
      NexusEvent next;
      while ((next = queue.pollFirst()) != null) {
        for (EventConsumer consumer : List.copyOf(consumers)) deliver(consumer, next);
      }
    } finally {
      draining = false;
    }
  }

  /** Returns true if the consumer has processed the event, now or earlier. */
  private boolean deliver(EventConsumer consumer, NexusEvent event) {
    try {
      // marker and the consumer's own writes commit together or not at all
      work.inNewTransaction(() -> {
        if (log.markProcessed(consumer.name(), event.eventId())) consumer.on(event);
      });
      return true;
    } catch (RuntimeException e) {
      String error = String.valueOf(e.getMessage());
      int[] attempts = new int[1];
      work.inNewTransaction(() -> attempts[0] = log.recordFailure(consumer.name(), event.eventId(), error));
      // a consumer that cannot process the failure report itself is not reported again
      if (attempts[0] == MAX_ATTEMPTS && !EventTypes.SYSTEM_CONSUMER_FAILED.equals(event.type())) {
        work.inNewTransaction(() -> publish(EventTypes.SYSTEM_CONSUMER_FAILED, SOURCE, Severity.ERROR,
            event.correlationId(),
            new Payloads.ConsumerFailed(consumer.name(), event.eventId(), event.type(), error)));
      }
      return false;
    }
  }
}
