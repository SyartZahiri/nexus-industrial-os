package io.nexus.routemind.store;

import io.nexus.contracts.state.VehicleStatus;

/**
 * Where a delivery vehicle is. On a tour, leg i runs from stop i (0 = depot) to stop i+1; the last leg returns to the
 * depot. breakdownAtMs: a breakdown drawn for the current leg (EN_ROUTE, RETURNING) or the moment it happened
 * (BROKEN_DOWN). readyAtMs: when a broken-down vehicle is back in service at the depot.
 */
public record VehicleRecord(String id, VehicleStatus status, String tourId, int leg, long legStartMs, long legEndMs,
                            Long breakdownAtMs, long readyAtMs, int version) {
  public static VehicleRecord atDepot(String id) {
    return new VehicleRecord(id, VehicleStatus.AVAILABLE, null, 0, 0, 0, null, 0, 0);
  }

  public VehicleRecord withStatus(VehicleStatus next) {
    return new VehicleRecord(id, next, tourId, leg, legStartMs, legEndMs, breakdownAtMs, readyAtMs, version);
  }

  public VehicleRecord withTour(String tour) {
    return new VehicleRecord(id, status, tour, 0, 0, 0, null, readyAtMs, version);
  }

  public VehicleRecord withLeg(int index, long startMs, long endMs, Long breakdownAt) {
    return new VehicleRecord(id, status, tourId, index, startMs, endMs, breakdownAt, readyAtMs, version);
  }

  public VehicleRecord withBreakdown(long atMs, long readyAt) {
    return new VehicleRecord(id, VehicleStatus.BROKEN_DOWN, tourId, leg, legStartMs, legEndMs, atMs, readyAt, version);
  }

  /** Back at the depot, without tour, in the given state. */
  public VehicleRecord back(VehicleStatus next) {
    return new VehicleRecord(id, next, null, 0, 0, 0, null, 0, version);
  }
}
