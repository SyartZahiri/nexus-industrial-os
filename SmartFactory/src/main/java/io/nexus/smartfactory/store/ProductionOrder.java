package io.nexus.smartfactory.store;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.state.OrderStatus;

/** @param version optimistic lock; {@link OrderStore#update} rejects a stale one */
public record ProductionOrder(String id, String productId, int quantity, Priority priority, OrderStatus status,
                              long dueAtMs, long createdAtMs, Long completedAtMs, int good, int scrapped, int version) {
  public ProductionOrder withStatus(OrderStatus newStatus) {
    return new ProductionOrder(id, productId, quantity, priority, newStatus, dueAtMs, createdAtMs, completedAtMs,
        good, scrapped, version);
  }
}
