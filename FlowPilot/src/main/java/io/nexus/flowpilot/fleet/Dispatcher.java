package io.nexus.flowpilot.fleet;

import io.nexus.flowpilot.routing.PathGraph;
import io.nexus.flowpilot.routing.Route;
import io.nexus.flowpilot.store.TransportOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Chooses the AGV for a transport: among free vehicles with enough battery for approach and trip, the one
 * with the shortest approach; ties go to the lower id because vehicles are passed in id order.
 */
final class Dispatcher {
  /** The choice for one transport; vehicle is null if none qualifies. blocked: no route exists at all. */
  record Choice(Vehicle vehicle, Route approach, double approachLength, List<String> candidates, boolean blocked) {}

  /** Where a vehicle can start a new route: its node, or the next node if it is on an edge. */
  record Anchor(String node, double distance) {}

  private final PathGraph graph;

  Dispatcher(PathGraph graph) {
    this.graph = graph;
  }

  static boolean free(Vehicle v) {
    return v.enabled && v.transportId == null && v.heldLoad == null && !v.chargeRequested
        && v.chargeTarget <= AgvPhysics.RESUME_FROM_CHARGE
        && (v.status == io.nexus.contracts.state.AgvStatus.IDLE
            || v.status == io.nexus.contracts.state.AgvStatus.CHARGING
            || v.status == io.nexus.contracts.state.AgvStatus.TO_CHARGE);
  }

  Anchor anchor(Vehicle v) {
    if (!v.route.isEmpty() && !atNode(v)) {
      var n = graph.node(v.route.get(0));
      double dx = n.x() - v.x, dz = n.z() - v.z;
      return new Anchor(n.id(), Math.sqrt(dx * dx + dz * dz));
    }
    return new Anchor(v.nodeId, 0);
  }

  boolean atNode(Vehicle v) {
    var n = graph.node(v.nodeId);
    return Math.abs(n.x() - v.x) < 1e-9 && Math.abs(n.z() - v.z) < 1e-9;
  }

  /**
   * @param avoid     nodes the approach must not pass
   * @param tripAvoid nodes the trip must not pass; without the failed vehicle that holds the load, which will
   *                  be gone or repaired by the time the trip starts
   */
  Choice choose(TransportOrder t, List<Vehicle> free, Set<String> avoid, Set<String> tripAvoid) {
    Route trip = graph.route(t.fromNode(), t.toNode(), tripAvoid);
    if (!trip.found()) return new Choice(null, null, 0, List.of(), true);
    Vehicle best = null;
    Route bestRoute = null;
    double bestLength = Double.POSITIVE_INFINITY;
    boolean anyReachable = false;
    List<String> candidates = new ArrayList<>();
    for (Vehicle v : free) {
      Anchor a = anchor(v);
      Route approach = graph.route(a.node(), t.fromNode(), avoid);
      if (!approach.found()) continue;
      anyReachable = true;
      double length = a.distance() + approach.length();
      double needed = AgvPhysics.MIN_FOR_TASK + (length + trip.length()) * AgvPhysics.DRAIN_PER_METER
          + AgvPhysics.TASK_RESERVE;
      if (v.battery < needed) continue;
      candidates.add(v.id + " " + meters(length));
      if (length < bestLength) {
        best = v;
        bestRoute = approach;
        bestLength = length;
      }
    }
    return new Choice(best, bestRoute, bestLength, List.copyOf(candidates), !anyReachable && !free.isEmpty());
  }

  /** "23,4 m": German decimal comma without String.format, which the browser build does not carry. */
  static String meters(double m) {
    long tenths = Math.round(m * 10);
    return (tenths / 10) + "," + (tenths % 10) + " m";
  }
}
