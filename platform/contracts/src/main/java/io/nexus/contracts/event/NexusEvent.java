package io.nexus.contracts.event;

import io.nexus.contracts.Domain.Severity;

/**
 * @param eventId       "<runId>-<counter>", see {@link EventIds}
 * @param correlationId the production order id where one exists, so an order can be traced end to end
 * @param payload       the record {@link EventTypes#payloadType(String)} names for {@code type}
 */
public record NexusEvent(String eventId, long simTimeMs, String type, String source, Severity severity,
                         String correlationId, Payload payload) {}
