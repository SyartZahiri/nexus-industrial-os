package io.nexus.contracts.port;

/** Implemented by FlowPilot. */
public interface MaterialService {
  /** Idempotent on requestId: the same request returns the same result and reserves once. All or nothing. */
  ReservationResult reserve(ReservationRequest request);

  /** Releases an active reservation. Releasing an already released reservation is a no-op. */
  void release(String reservationId);

  Availability availability(String materialId);

  record ReservationRequest(String requestId, String orderId, String materialId, int quantity) {}

  sealed interface ReservationResult {
    record Reserved(String reservationId, String locationId) implements ReservationResult {}
    record Shortage(String materialId, int requested, int available) implements ReservationResult {}
  }

  record Availability(String materialId, int onHand, int reserved) {
    public int available() {
      return onHand - reserved;
    }
  }
}
