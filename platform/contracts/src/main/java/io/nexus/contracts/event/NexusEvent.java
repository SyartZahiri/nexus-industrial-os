package io.nexus.contracts.event;

import io.nexus.contracts.Domain.Severity;
import java.util.Map;
import java.util.UUID;

/** correlationId is the production order id where one exists, so an order can be traced end to end. */
public record NexusEvent(UUID eventId, long simTimeMs, String type, String source, Severity severity,
                         String correlationId, Map<String, Object> payload) {}
