package io.nexus.contracts.event;

public interface EventConsumer {
  /** Stable name; idempotency is tracked per (name, eventId). */
  String name();

  /** Called at most once per event with effect. Runs in its own transaction. */
  void on(NexusEvent event);
}
