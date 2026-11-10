package io.nexus.contracts.event;

import java.util.List;
import java.util.Optional;

/** Storage behind the {@link EventBus}. All writes take part in the surrounding {@link UnitOfWork}. */
public interface EventLog {
  void append(long runId, NexusEvent event);

  /** True exactly once per (consumer, eventId). Called inside the consumer's unit of work. */
  boolean markProcessed(String consumer, String eventId);

  /** Counts a failed delivery and returns the number of attempts so far. */
  int recordFailure(String consumer, String eventId, String error);

  void clearFailure(String consumer, String eventId);

  /** Failed deliveries of the run with fewer than maxAttempts attempts, in publish order. */
  List<Failure> openFailures(long runId, int maxAttempts);

  Optional<NexusEvent> find(String eventId);

  record Failure(String consumer, NexusEvent event, int attempts) {}
}
