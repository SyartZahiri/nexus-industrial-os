package io.nexus.flowpilot.store;

/** A quantity of one material held at one location for a production order. */
public record Reservation(String id, String requestId, String orderId, String materialId, String locationId,
                          int quantity, Status status, long createdAtMs) {
  public enum Status { ACTIVE, RELEASED, CONSUMED }

  public Reservation withStatus(Status next) {
    return new Reservation(id, requestId, orderId, materialId, locationId, quantity, next, createdAtMs);
  }
}
