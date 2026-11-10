package io.nexus.contracts.port;

import java.util.List;

/** Implemented by FlowPilot. */
public interface MaterialService {
  /**
   * Reserves all lines or nothing. Idempotent on requestId for successful reservations: the same request
   * returns the same reservations and reserves once. A shortage is not stored; calling again re-checks stock.
   */
  ReservationResult reserve(ReservationRequest request);

  /** Releases every active reservation of the request. Unknown or already released requests are a no-op. */
  void release(String requestId);

  /** One entry per storage location holding the material. A reservation is always tied to one location. */
  List<Availability> availability(String materialId);

  record Line(String materialId, int quantity) {}

  record ReservationRequest(String requestId, String orderId, List<Line> lines) {}

  record Reserved(String reservationId, String materialId, String locationId, int quantity) {}

  sealed interface ReservationResult {
    record Success(String requestId, List<Reserved> reservations) implements ReservationResult {}

    /** The first line that could not be covered from a single storage location. */
    record Shortage(String materialId, int requested, int available) implements ReservationResult {}
  }

  record Availability(String materialId, String locationId, int onHand, int reserved) {
    public int available() {
      return onHand - reserved;
    }
  }
}
