package io.nexus.contracts.event;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.IllegalTransitionException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
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
 *       then reported once as {@code system.consumer_failed}. While a consumer has an open failure,
 *       later events are parked for it and delivered in order after the failure is resolved or given up,
 *       so it never sees an effect before its cause.</li>
 *   <li>Delivery never throws into the publisher: problems with the bookkeeping itself go to the
 *       internal error handler.</li>
 * </ul>
 *
 * <p>Threading rule (server): {@link #retryFailed()} and anything that publishes inside
 * {@link UnitOfWork#inNewTransaction} must not be called while the caller holds uncommitted writes,
 * because delivery runs in further transactions that may need the same rows.
 */
public final class EventBus implements EventPublisher {
  public static final int MAX_ATTEMPTS = 5;
  private static final String SOURCE = "nexus";

  private final EventLog log;
  private final UnitOfWork work;
  private final SimTime time;
  private final Supplier<EventIds> ids;
  private final Consumer<Throwable> internalError;
  private final List<EventConsumer> consumers = new ArrayList<>();
  private final Deque<NexusEvent> queue = new ArrayDeque<>();
  private boolean draining;

  /** @param ids the ids of the current run; read once per publish so run id and counter always match */
  public EventBus(EventLog log, UnitOfWork work, SimTime time, Supplier<EventIds> ids) {
    this(log, work, time, ids, t -> {});
  }

  /** @param internalError receives failures of the bus's own bookkeeping, e.g. the database being unreachable */
  public EventBus(EventLog log, UnitOfWork work, SimTime time, Supplier<EventIds> ids,
                  Consumer<Throwable> internalError) {
    this.log = log;
    this.work = work;
    this.time = time;
    this.ids = ids;
    this.internalError = internalError;
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

  /** For a reset of the simulation: forgets queued deliveries; registered consumers stay. */
  public synchronized void reset() {
    queue.clear();
    draining = false;
  }

  /** Delivers a stored event again; consumers that already processed it are skipped. */
  public void redeliver(String eventId) {
    NexusEvent event = log.find(eventId).orElseThrow(() -> DomainException.notFound("Ereignis", eventId));
    work.afterCommit(() -> enqueue(event));
  }

  /**
   * Called at the end of every simulation step, outside the step's transaction: retries failed and parked
   * deliveries in publish order. A consumer that fails again keeps its later events parked.
   */
  public synchronized void retryFailed() {
    List<EventLog.Failure> open;
    try {
      open = log.openFailures(ids.get().runId(), MAX_ATTEMPTS);
    } catch (RuntimeException | Error e) {
      internalError.accept(e);
      return;
    }
    Set<String> stillFailing = new HashSet<>();
    for (EventLog.Failure failure : open) {
      if (stillFailing.contains(failure.consumer())) continue;
      EventConsumer consumer = byName(failure.consumer());
      if (consumer == null) continue;
      if (attempt(consumer, failure.event())) {
        guarded(() -> work.inNewTransaction(() -> log.clearFailure(failure.consumer(), failure.event().eventId())));
      } else {
        stillFailing.add(failure.consumer());
      }
    }
  }

  private EventConsumer byName(String name) {
    for (EventConsumer c : consumers) if (c.name().equals(name)) return c;
    return null;
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

  /** First delivery: parks the event if the consumer still has an open failure on an earlier one. */
  private void deliver(EventConsumer consumer, NexusEvent event) {
    boolean[] parked = {false};
    guarded(() -> {
      if (log.hasOpenFailure(consumer.name(), ids.get().runId(), MAX_ATTEMPTS)) {
        work.inNewTransaction(() -> log.park(consumer.name(), event.eventId()));
        parked[0] = true;
      }
    });
    if (!parked[0]) attempt(consumer, event);
  }

  /** Returns true if the consumer has processed the event, now or earlier. Never throws. */
  private boolean attempt(EventConsumer consumer, NexusEvent event) {
    try {
      // marker and the consumer's own writes commit together or not at all
      work.inNewTransaction(() -> {
        if (log.markProcessed(consumer.name(), event.eventId())) consumer.on(event);
      });
      return true;
    } catch (RuntimeException | Error failure) {
      guarded(() -> recordFailure(consumer, event, failure));
      return false;
    }
  }

  private void recordFailure(EventConsumer consumer, NexusEvent event, Throwable failure) {
    int[] attempts = new int[1];
    // the full text stays in the failure record, which no API exposes
    work.inNewTransaction(() -> attempts[0] = log.recordFailure(consumer.name(), event.eventId(), String.valueOf(failure)));
    // a consumer that cannot process the failure report itself is not reported again
    if (attempts[0] == MAX_ATTEMPTS && !EventTypes.SYSTEM_CONSUMER_FAILED.equals(event.type())) {
      work.inNewTransaction(() -> publish(EventTypes.SYSTEM_CONSUMER_FAILED, SOURCE, Severity.ERROR,
          event.correlationId(),
          new Payloads.ConsumerFailed(consumer.name(), event.eventId(), event.type(), forClients(failure))));
    }
  }

  /** The report is visible to every client: only business messages, otherwise just the exception class. */
  private static String forClients(Throwable failure) {
    if (failure instanceof DomainException || failure instanceof IllegalTransitionException) {
      return failure.getMessage();
    }
    String name = failure.getClass().getName();
    return name.substring(name.lastIndexOf('.') + 1);
  }

  private void guarded(Runnable bookkeeping) {
    try {
      bookkeeping.run();
    } catch (RuntimeException | Error e) {
      internalError.accept(e);
    }
  }
}
