package io.nexus.routemind.store;

import io.nexus.contracts.state.TourStatus;
import java.util.ArrayList;
import java.util.List;

/** One vehicle's round trip from the depot in one wave. Stops are ordered by seq (1..n). */
public record Tour(String id, String planId, String vehicleId, long departAtMs, TourStatus status, List<Stop> stops,
                   double km, double cost, Long startedAtMs, Long endedAtMs, int version) {
  /** arrivedAtMs is set once the vehicle reached the stop; such a stop never changes again. */
  public record Stop(int seq, String deliveryId, String customerId, long plannedArrivalMs, Long arrivedAtMs) {}

  public Tour {
    stops = List.copyOf(stops);
  }

  public Tour withStatus(TourStatus next) {
    return new Tour(id, planId, vehicleId, departAtMs, next, stops, km, cost, startedAtMs, endedAtMs, version);
  }

  public Tour withStarted(long atMs) {
    return new Tour(id, planId, vehicleId, departAtMs, status, stops, km, cost, atMs, endedAtMs, version);
  }

  public Tour withEnded(long atMs) {
    return new Tour(id, planId, vehicleId, departAtMs, status, stops, km, cost, startedAtMs, atMs, version);
  }

  public Tour withStops(List<Stop> next, double nextKm, double nextCost) {
    return new Tour(id, planId, vehicleId, departAtMs, status, next, nextKm, nextCost, startedAtMs, endedAtMs, version);
  }

  public Tour withArrival(int seq, long atMs) {
    List<Stop> next = new ArrayList<>();
    for (Stop s : stops) {
      next.add(s.seq() == seq ? new Stop(s.seq(), s.deliveryId(), s.customerId(), s.plannedArrivalMs(), atMs) : s);
    }
    return withStops(next, km, cost);
  }
}
