package io.nexus.flowpilot.store;

import java.util.List;
import java.util.Optional;

public interface StockStore {
  /** Locks every stock line of the material for the rest of the unit of work; ordered by location id. */
  List<StockLine> lockMaterial(String materialId);

  /** Inserts or updates the line of (material, location). */
  void saveLine(StockLine line);

  /** Ordered by material, location. */
  List<StockLine> lines();

  /** Ordered by material id. */
  List<Reservation> reservationsOfRequest(String requestId);

  Optional<Reservation> reservation(String id);

  /** Inserts a reservation or updates its status. */
  void saveReservation(Reservation r);

  /** Ordered by id. */
  List<Reservation> reservationsOfOrder(String orderId);
}
