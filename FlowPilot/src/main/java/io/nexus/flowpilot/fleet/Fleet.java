package io.nexus.flowpilot.fleet;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.AgvStatus;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.material.MaterialServiceImpl;
import io.nexus.flowpilot.routing.PathGraph;
import io.nexus.flowpilot.routing.Route;
import io.nexus.flowpilot.store.AgvRecord;
import io.nexus.flowpilot.store.FleetStore;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.flowpilot.store.TransportStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The AGV fleet: dispatch, driving with following distance, loading, unloading and charging.
 * Every business change (transport and AGV state, stock, events) runs in a unit of work of its own; the
 * vehicle in memory changes only after its unit committed. Vehicles are always handled in id order.
 */
public final class Fleet {
  static final String SOURCE = MaterialServiceImpl.SOURCE;

  private final FleetStore store;
  private final TransportStore transports;
  private final MaterialServiceImpl materials;
  private final PathGraph graph;
  private final EventPublisher events;
  private final UnitOfWork work;
  private final SimTime time;
  private final Dispatcher dispatcher;
  private final List<Vehicle> vehicles = new ArrayList<>();       // id order, as FleetStore returns them
  /** Transports already reported as unroutable; cleared whenever the graph changes. */
  private final TreeSet<String> reportedBlocked = new TreeSet<>();

  public Fleet(FleetStore store, TransportStore transports, MaterialServiceImpl materials, PathGraph graph,
               EventPublisher events, UnitOfWork work, SimTime time) {
    this.store = store;
    this.transports = transports;
    this.materials = materials;
    this.graph = graph;
    this.events = events;
    this.work = work;
    this.time = time;
    this.dispatcher = new Dispatcher(graph);
    for (String edge : store.blockedEdges()) graph.setBlocked(edge, true);
    for (AgvRecord r : store.agvs()) {
      var node = graph.node(r.nodeId());
      var v = new Vehicle(r, node.x(), node.z());
      v.chargerId = chargerAt(r.nodeId());
      vehicles.add(v);
    }
  }

  // ---- queries -----------------------------------------------------------------------------------

  List<Vehicle> vehicles() {
    return vehicles;
  }

  /** Snapshot of every vehicle in id order. The heading (atan2) is for display only, never used in the logic. */
  public List<FlowPilot.VehicleView> views() {
    List<FlowPilot.VehicleView> out = new ArrayList<>();
    for (Vehicle v : vehicles) {
      out.add(new FlowPilot.VehicleView(v.id, v.name, v.status, v.x, v.z, Math.atan2(v.dirX, v.dirZ), v.speed,
          v.battery, v.transportId, List.copyOf(v.route)));
    }
    return out;
  }

  Vehicle vehicle(String id) {
    for (Vehicle v : vehicles) if (v.id.equals(id)) return v;
    throw DomainException.notFound("AGV", id);
  }

  /** Nodes no route may pass. Task 7 adds the nodes of failed vehicles. */
  Set<String> avoidNodes() {
    return Set.of();
  }

  private static String chargerAt(String nodeId) {
    for (var c : FlowPilotMasterData.chargers()) if (c.nodeId().equals(nodeId)) return c.id();
    return null;
  }

  private static String chargerNode(String chargerId) {
    for (var c : FlowPilotMasterData.chargers()) if (c.id().equals(chargerId)) return c.nodeId();
    throw DomainException.notFound("Ladestation", chargerId);
  }

  // ---- step ----------------------------------------------------------------------------------------

  public void step(long dtMs) {
    dispatch();
    for (Vehicle v : vehicles) move(v, dtMs);
    persist();
  }

  private void dispatch() {
    List<Vehicle> free = new ArrayList<>();
    for (Vehicle v : vehicles) if (Dispatcher.free(v)) free.add(v);
    if (free.isEmpty()) return;
    Set<String> avoid = avoidNodes();
    for (TransportOrder t : transports.open()) {
      if (t.status() != TransportStatus.CREATED && t.status() != TransportStatus.FAILED) continue;
      if (free.isEmpty()) break;
      Dispatcher.Choice c = dispatcher.choose(t, free, avoid);
      if (c.vehicle() == null) {
        if (c.blocked()) reportBlocked(t);
        continue;
      }
      assign(t, c);
      free.remove(c.vehicle());
    }
  }

  private void reportBlocked(TransportOrder t) {
    if (!reportedBlocked.add(t.id())) return;
    work.inNewTransaction(() -> events.publish(EventTypes.ROUTE_BLOCKED, SOURCE, Severity.WARNING, t.orderId(),
        new Payloads.RouteChanged(null, null, List.of())));
  }

  private void assign(TransportOrder t, Dispatcher.Choice c) {
    Vehicle v = c.vehicle();
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> {
      events.publish(EventTypes.DECISION, SOURCE, Severity.INFO, t.orderId(), new Payloads.Decision(t.id(), v.id,
          c.candidates(), "kürzeste Anfahrt " + Dispatcher.meters(c.approachLength())));
      boolean again = t.status() == TransportStatus.FAILED;
      TransportOrder a = moveTransport(t, TransportStatus.ASSIGNED, v.id,
          again ? EventTypes.TRANSPORT_REASSIGNED : EventTypes.TRANSPORT_ASSIGNED);
      moveTransport(a, TransportStatus.TO_PICKUP, v.id, EventTypes.TRANSPORT_STATE);
      saved[0] = saveAgv(v, AgvStatus.TO_PICKUP, t.orderId());
    });
    v.status = AgvStatus.TO_PICKUP;
    v.saved = saved[0];
    v.transportId = t.id();
    v.chargerId = null;
    v.chargeTarget = AgvPhysics.RESUME_FROM_CHARGE;
    setRoute(v, t.fromNode(), c.approach());
  }

  private void move(Vehicle v, long dtMs) {
    double dt = dtMs / 1000.0;
    switch (v.status) {
      case LOADING, UNLOADING -> {
        stand(v, dt);
        v.timerMs -= dtMs;
        if (v.timerMs <= 0) {
          if (v.status == AgvStatus.LOADING) finishLoading(v);
          else finishUnloading(v);
        }
      }
      case TO_PICKUP, TO_DROP, TO_CHARGE -> {
        if (v.route.isEmpty() && !v.nodeId.equals(v.goal)) replan(v);
        if (v.route.isEmpty()) stand(v, dt);
        else drive(v, dt);
      }
      case CHARGING -> {
        charge(v, dt);
        if (!v.enabled) change(v, AgvStatus.OFFLINE, null);
        else if (v.battery >= v.chargeTarget) {
          change(v, AgvStatus.IDLE, null);
          v.chargeTarget = AgvPhysics.RESUME_FROM_CHARGE;
        }
      }
      case OFFLINE -> {
        if (atOwnCharger(v)) charge(v, dt);
        else stand(v, dt);
        if (v.enabled) change(v, AgvStatus.IDLE, null);
      }
      case IDLE -> {
        stand(v, dt);
        idle(v);
      }
      case FAULT -> stand(v, dt);
    }
    if (v.driving() && arrived(v)) arrive(v);
  }

  private void idle(Vehicle v) {
    if (v.chargeRequested) {
      v.chargeRequested = false;
      v.chargeTarget = AgvPhysics.MANUAL_CHARGE_TARGET;
      goCharge(v);
      return;
    }
    if (atOwnCharger(v)) {
      if (!v.enabled) change(v, AgvStatus.OFFLINE, null);
      else if (v.battery < AgvPhysics.LOW_BATTERY) goCharge(v);
      return;
    }
    goCharge(v); // park at the nearest free charger, also when disabled
  }

  /** Claims the nearest free charger it can reach and heads there; stays IDLE if there is none. */
  void goCharge(Vehicle v) {
    Dispatcher.Anchor a = dispatcher.anchor(v);
    if (v.chargerId == null) {
      String best = null;
      double bestLength = Double.POSITIVE_INFINITY;
      for (var c : FlowPilotMasterData.chargers()) {
        if (claimedByOther(c.id(), v)) continue;
        Route r = graph.route(a.node(), c.nodeId(), avoidNodes());
        if (r.found() && r.length() < bestLength) {
          best = c.id();
          bestLength = r.length();
        }
      }
      if (best == null) return;
      v.chargerId = best;
    }
    String node = chargerNode(v.chargerId);
    Route r = graph.route(a.node(), node, avoidNodes());
    if (!r.found()) return;
    change(v, AgvStatus.TO_CHARGE, null);
    setRoute(v, node, r);
  }

  private boolean claimedByOther(String chargerId, Vehicle v) {
    for (Vehicle o : vehicles) if (o != v && chargerId.equals(o.chargerId)) return true;
    return false;
  }

  private boolean atOwnCharger(Vehicle v) {
    return v.chargerId != null && v.route.isEmpty() && chargerNode(v.chargerId).equals(v.nodeId)
        && dispatcher.atNode(v);
  }

  private boolean arrived(Vehicle v) {
    return v.route.isEmpty() && v.nodeId.equals(v.goal) && dispatcher.atNode(v);
  }

  private void arrive(Vehicle v) {
    switch (v.status) {
      case TO_PICKUP -> arrivedAtPickup(v);
      case TO_DROP -> arrivedAtDrop(v);
      case TO_CHARGE -> change(v, v.enabled ? AgvStatus.CHARGING : AgvStatus.OFFLINE, null);
      default -> { }
    }
  }

  private void arrivedAtPickup(Vehicle v) {
    TransportOrder t = transport(v.transportId);
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> {
      moveTransport(t, TransportStatus.LOADING, v.id, EventTypes.TRANSPORT_STATE);
      // stock leaves at pickup; a reservation already consumed (rescue of a failed load) is not taken twice
      if (t.kind() == TransportKind.SUPPLY && t.reservationId() != null) materials.consume(t.reservationId());
      saved[0] = saveAgv(v, AgvStatus.LOADING, t.orderId());
    });
    v.status = AgvStatus.LOADING;
    v.saved = saved[0];
    v.timerMs = AgvPhysics.LOAD_TIME_MS;
  }

  private void finishLoading(Vehicle v) {
    TransportOrder t = transport(v.transportId);
    Route r = graph.route(v.nodeId, t.toNode(), avoidNodes());
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> {
      moveTransport(t, TransportStatus.IN_TRANSIT, v.id, EventTypes.TRANSPORT_STATE);
      saved[0] = saveAgv(v, AgvStatus.TO_DROP, t.orderId());
    });
    v.status = AgvStatus.TO_DROP;
    v.saved = saved[0];
    setRoute(v, t.toNode(), r); // without a route the vehicle waits here and reports once, see replan
  }

  private void arrivedAtDrop(Vehicle v) {
    TransportOrder t = transport(v.transportId);
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> {
      moveTransport(t, TransportStatus.UNLOADING, v.id, EventTypes.TRANSPORT_STATE);
      saved[0] = saveAgv(v, AgvStatus.UNLOADING, t.orderId());
    });
    v.status = AgvStatus.UNLOADING;
    v.saved = saved[0];
    v.timerMs = AgvPhysics.LOAD_TIME_MS;
    v.unloadWarned = false;
  }

  private void finishUnloading(Vehicle v) {
    TransportOrder t = transport(v.transportId);
    AgvRecord[] saved = new AgvRecord[1];
    try {
      work.inNewTransaction(() -> {
        if (t.kind() == TransportKind.PUTAWAY) {
          materials.receive(t.materialId(), t.to().id(), t.quantity(), t.orderId());
        }
        TransportStatus.RULES.move(t.id(), t.status(), TransportStatus.DELIVERED);
        transports.update(t.withStatus(TransportStatus.DELIVERED, v.id));
        events.publish(EventTypes.TRANSPORT_DELIVERED, SOURCE, Severity.SUCCESS, t.orderId(),
            new Payloads.TransportDelivered(t.id(), t.kind(), t.orderId(), t.materialId(), t.quantity(), t.to()));
        saved[0] = saveAgv(v, AgvStatus.IDLE, t.orderId());
      });
    } catch (DomainException notStored) {
      // the finished goods store is full: stay in UNLOADING, retry every step, warn once
      if (!v.unloadWarned) {
        v.unloadWarned = true;
        work.inNewTransaction(() -> events.publish(EventTypes.DECISION, SOURCE, Severity.WARNING, t.orderId(),
            new Payloads.Decision(t.id(), v.id, List.of(),
                "Fertiglager voll, Einlagerung wartet: " + notStored.getMessage())));
      }
      return;
    }
    v.status = AgvStatus.IDLE;
    v.saved = saved[0];
    v.transportId = null;
    v.goal = null;
  }

  // ---- transport changes from outside ----------------------------------------------------------------

  /** Called after a transport was cancelled or redirected: frees or reroutes the vehicle carrying it. */
  public void transportChanged(String transportId) {
    for (Vehicle v : vehicles) {
      if (!transportId.equals(v.transportId)) continue;
      TransportOrder t = transport(transportId);
      if (t.status() == TransportStatus.CANCELLED) {
        change(v, AgvStatus.IDLE, t.orderId());
        v.transportId = null;
        v.goal = null;
        keepOnlyNextNode(v);
      } else if (v.status == AgvStatus.TO_DROP && !t.toNode().equals(v.goal)) {
        v.goal = t.toNode();
        v.route.clear();
        replan(v);
      }
    }
  }

  // ---- motion --------------------------------------------------------------------------------------

  private void setRoute(Vehicle v, String goal, Route r) {
    v.goal = goal;
    v.route.clear();
    v.routeBlockedReported = false;
    if (r == null || !r.found()) return;
    List<String> nodes = r.nodes();
    int first = nodes.get(0).equals(v.nodeId) && dispatcher.atNode(v) ? 1 : 0;
    for (int i = first; i < nodes.size(); i++) v.route.add(nodes.get(i));
  }

  /** A vehicle stopped on an edge keeps only the node it is heading for. */
  private void keepOnlyNextNode(Vehicle v) {
    if (v.route.isEmpty() || dispatcher.atNode(v)) {
      v.route.clear();
      return;
    }
    String next = v.route.get(0);
    v.route.clear();
    v.route.add(next);
  }

  /** New route to the goal; reports route.blocked once while there is none and route.replanned once there is. */
  void replan(Vehicle v) {
    Dispatcher.Anchor a = dispatcher.anchor(v);
    Route r = graph.route(a.node(), v.goal, avoidNodes());
    if (!r.found()) {
      keepOnlyNextNode(v);
      if (!v.routeBlockedReported) {
        v.routeBlockedReported = true;
        work.inNewTransaction(() -> events.publish(EventTypes.ROUTE_BLOCKED, SOURCE, Severity.WARNING,
            correlation(v), new Payloads.RouteChanged(v.id, null, List.of())));
      }
      return;
    }
    String goal = v.goal;
    setRoute(v, goal, r);
    List<String> route = List.copyOf(v.route);
    work.inNewTransaction(() -> events.publish(EventTypes.ROUTE_REPLANNED, SOURCE, Severity.INFO, correlation(v),
        new Payloads.RouteChanged(v.id, null, route)));
  }

  private String correlation(Vehicle v) {
    return v.transportId == null ? null : transport(v.transportId).orderId();
  }

  private void drive(Vehicle v, double dt) {
    double remaining = distanceToGoal(v);
    double vmax = v.battery <= 0.5 ? AgvPhysics.CRAWL_SPEED : AgvPhysics.MAX_SPEED;
    vmax = Math.min(vmax, Math.sqrt(2 * AgvPhysics.ACCEL * remaining) + 0.08); // brake before the goal
    if (v.speed < vmax) v.speed = Math.min(vmax, v.speed + AgvPhysics.ACCEL * dt);
    else v.speed = Math.max(vmax, v.speed - AgvPhysics.BRAKE * dt);
    double step = v.speed * dt;
    double moved = 0;
    while (step > 1e-9 && !v.route.isEmpty()) {
      var n = graph.node(v.route.get(0));
      double dx = n.x() - v.x, dz = n.z() - v.z;
      double d = Math.sqrt(dx * dx + dz * dz);
      if (d < 1e-9) {
        v.nodeId = v.route.remove(0);
        continue;
      }
      v.dirX = dx / d;
      v.dirZ = dz / d;
      if (obstructed(v, step)) {
        v.speed = 0;
        break;
      }
      boolean reaches = step >= d;
      double nx = reaches ? n.x() : v.x + v.dirX * step;
      double nz = reaches ? n.z() : v.z + v.dirZ * step;
      if (cutsIn(v, nx, nz)) {
        v.speed = 0;
        break;
      }
      double advance = reaches ? d : step;
      v.x = nx;
      v.z = nz;
      moved += advance;
      step -= advance;
      if (reaches) v.nodeId = v.route.remove(0);
    }
    if (v.route.isEmpty()) v.speed = 0;
    if (moved > 0) v.battery = Math.max(0, v.battery - moved * AgvPhysics.DRAIN_PER_METER);
    else v.battery = Math.max(0, v.battery - AgvPhysics.DRAIN_IDLE * dt);
  }

  private double distanceToGoal(Vehicle v) {
    var first = graph.node(v.route.get(0));
    double dx = first.x() - v.x, dz = first.z() - v.z;
    double sum = Math.sqrt(dx * dx + dz * dz);
    for (int i = 1; i < v.route.size() && sum < 10; i++) {
      sum += graph.edge(v.route.get(i - 1), v.route.get(i)).length();
    }
    return sum;
  }

  /** True if another vehicle is ahead within the safety distance plus this step. Lower id wins a mutual block. */
  private boolean obstructed(Vehicle v, double look) {
    for (Vehicle o : vehicles) {
      if (o == v) continue;
      double dx = o.x - v.x, dz = o.z - v.z;
      double ahead = dx * v.dirX + dz * v.dirZ;
      double side = Math.abs(-dx * v.dirZ + dz * v.dirX);
      if (ahead > 0 && ahead < AgvPhysics.SAFETY_DISTANCE + look && side < AgvPhysics.LANE_HALF_WIDTH) {
        boolean mutual = v.id.equals(o.blockedBy);
        if (!mutual || o.id.compareTo(v.id) < 0) {
          v.blockedBy = o.id;
          return true;
        }
      }
    }
    v.blockedBy = null;
    return false;
  }

  /** True if moving to (nx, nz) would put this vehicle into the safety zone in front of a moving vehicle. */
  private boolean cutsIn(Vehicle v, double nx, double nz) {
    for (Vehicle w : vehicles) {
      if (w == v || w.speed <= 0) continue;
      double after = aheadInZone(w, nx, nz);
      if (after < 0) continue;
      double before = aheadInZone(w, v.x, v.z);
      if (before < 0 || after < before) return true;
    }
    return false;
  }

  /** Distance ahead of w if (x, z) lies in its safety zone, otherwise -1. */
  private static double aheadInZone(Vehicle w, double x, double z) {
    double dx = x - w.x, dz = z - w.z;
    double ahead = dx * w.dirX + dz * w.dirZ;
    double side = Math.abs(-dx * w.dirZ + dz * w.dirX);
    return ahead > 0 && ahead < AgvPhysics.SAFETY_DISTANCE && side < AgvPhysics.LANE_HALF_WIDTH ? ahead : -1;
  }

  private static void stand(Vehicle v, double dt) {
    v.speed = 0;
    v.blockedBy = null;
    v.battery = Math.max(0, v.battery - AgvPhysics.DRAIN_IDLE * dt);
  }

  private static void charge(Vehicle v, double dt) {
    v.speed = 0;
    v.blockedBy = null;
    v.battery = Math.min(100, v.battery + AgvPhysics.CHARGE_RATE * dt);
  }

  // ---- commands ------------------------------------------------------------------------------------

  public void setEnabled(String agvId, boolean enabled) {
    Vehicle v = vehicle(agvId);
    v.enabled = enabled;
    persist();
  }

  /** Charges to 95 %; a busy vehicle goes once its transport is done. */
  public void sendToCharge(String agvId) {
    Vehicle v = vehicle(agvId);
    if (v.transportId != null || v.status == AgvStatus.FAULT) {
      v.chargeRequested = true;
      return;
    }
    v.chargeTarget = AgvPhysics.MANUAL_CHARGE_TARGET;
    if (v.status == AgvStatus.IDLE) goCharge(v);
  }

  // ---- bookkeeping ---------------------------------------------------------------------------------

  /** Changes the AGV state in a unit of its own and publishes agv.state. */
  void change(Vehicle v, AgvStatus to, String correlationId) {
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> saved[0] = saveAgv(v, to, correlationId));
    v.status = to;
    v.saved = saved[0];
  }

  /** Inside a unit: checks the transition, stores the AGV, publishes agv.state. */
  private AgvRecord saveAgv(Vehicle v, AgvStatus to, String correlationId) {
    AgvStatus from = v.status;
    AgvStatus.RULES.move(v.id, from, to);
    AgvRecord r = v.record();
    AgvRecord saved = store.save(new AgvRecord(r.id(), r.name(), to, r.nodeId(), r.battery(), r.enabled(),
        r.version()));
    if (from != to) {
      events.publish(EventTypes.AGV_STATE, SOURCE, Severity.INFO, correlationId,
          new Payloads.AgvStateChanged(v.id, from, to));
    }
    return saved;
  }

  /** Inside a unit: checks the transition, stores the transport, publishes the event. */
  private TransportOrder moveTransport(TransportOrder t, TransportStatus to, String agvId, String eventType) {
    TransportStatus.RULES.move(t.id(), t.status(), to);
    TransportOrder saved = transports.update(t.withStatus(to, agvId));
    events.publish(eventType, SOURCE, Severity.INFO, t.orderId(),
        new Payloads.TransportStateChanged(t.id(), t.status(), to, agvId));
    return saved;
  }

  private TransportOrder transport(String id) {
    return transports.byId(id).orElseThrow(() -> DomainException.notFound("Transportauftrag", id));
  }

  /** Writes node, battery and enabled flag of every vehicle whose stored record is out of date. */
  void persist() {
    for (Vehicle v : vehicles) {
      AgvRecord r = v.record();
      if (r.equals(v.saved)) continue;
      AgvRecord[] saved = new AgvRecord[1];
      work.inNewTransaction(() -> saved[0] = store.save(r));
      v.saved = saved[0];
    }
  }

  SimTime time() {
    return time;
  }
}
