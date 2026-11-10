package io.nexus.contracts.event;

import io.nexus.contracts.Domain.Severity;
import java.util.Map;

public interface EventPublisher {
  /**
   * Stores the event in the caller's transaction and delivers it to consumers after commit.
   * If the transaction rolls back, the event is neither stored nor delivered.
   */
  NexusEvent publish(String type, String source, Severity severity, String correlationId, Map<String, Object> payload);
}
