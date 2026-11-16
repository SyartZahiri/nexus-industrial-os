package io.nexus.routemind.store;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.state.DeliveryStatus;

/**
 * A delivery order: the finished goods of one production order (same id as correlation) to one customer.
 *
 * @param quantity       ordered units
 * @param storedQuantity good units put away for it (0 until the putaway); what is actually shipped
 * @param reservationId  request id of the finished-goods reservation at FlowPilot, null before
 * @param transportId    FlowPilot transport to the loading zone, null before it is requested
 * @param failures       failed delivery attempts (vehicle breakdown under way)
 */
public record Delivery(String id, String orderId, String customerId, String productId, int quantity,
                       int storedQuantity, int weightKg, int pallets, Priority priority, long earliestMs,
                       long latestMs, DeliveryStatus status, String tourId, Long plannedArrivalMs,
                       Long deliveredAtMs, String reservationId, String transportId, int failures, long createdAtMs,
                       int version) {
  public Delivery withStatus(DeliveryStatus next) {
    return new Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority,
        earliestMs, latestMs, next, tourId, plannedArrivalMs, deliveredAtMs, reservationId, transportId, failures,
        createdAtMs, version);
  }

  public Delivery withTour(String tour, Long plannedArrival) {
    return new Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority,
        earliestMs, latestMs, status, tour, plannedArrival, deliveredAtMs, reservationId, transportId, failures,
        createdAtMs, version);
  }

  public Delivery withGoods(int stored, int kg, int palletCount) {
    return new Delivery(id, orderId, customerId, productId, quantity, stored, kg, palletCount, priority, earliestMs,
        latestMs, status, tourId, plannedArrivalMs, deliveredAtMs, reservationId, transportId, failures, createdAtMs,
        version);
  }

  public Delivery withShipment(String reservation, String transport) {
    return new Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority,
        earliestMs, latestMs, status, tourId, plannedArrivalMs, deliveredAtMs, reservation, transport, failures,
        createdAtMs, version);
  }

  public Delivery withFailure() {
    return new Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority,
        earliestMs, latestMs, status, tourId, plannedArrivalMs, deliveredAtMs, reservationId, transportId,
        failures + 1, createdAtMs, version);
  }

  public Delivery withDelivered(long atMs) {
    return new Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority,
        earliestMs, latestMs, status, tourId, plannedArrivalMs, atMs, reservationId, transportId, failures,
        createdAtMs, version);
  }
}
