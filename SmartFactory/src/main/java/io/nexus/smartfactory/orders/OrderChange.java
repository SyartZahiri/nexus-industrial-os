package io.nexus.smartfactory.orders;

import io.nexus.contracts.Domain.Priority;

/** Changes to an order; null leaves a field unchanged. Quantity and due date only while the order waits for material. */
public record OrderChange(Priority priority, Integer quantity, Long dueInMs) {}
