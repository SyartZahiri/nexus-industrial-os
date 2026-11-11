package io.nexus.contracts.event;

import io.nexus.contracts.Domain.Severity;

public interface EventPublisher {
  /**
   * Stores the event in the caller's transaction and delivers it to consumers after commit.
   * If the transaction rolls back, the event is neither stored nor delivered.
   *
   * @throws IllegalArgumentException if the payload is not the record defined for the type
   */
  NexusEvent publish(String type, String source, Severity severity, String correlationId, Payload payload);
}
