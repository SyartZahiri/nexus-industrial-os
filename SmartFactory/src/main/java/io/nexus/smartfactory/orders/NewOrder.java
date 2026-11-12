package io.nexus.smartfactory.orders;

import io.nexus.contracts.Domain.Priority;

/** A new production order; the due date is relative to the current simulation time. */
public record NewOrder(String productId, int quantity, Priority priority, long dueInMs) {}
