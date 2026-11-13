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
  /** Nodes where two or more lanes merge. */
  private final Set<String> junctions = new java.util.HashSet<>(); // lookups only
  /** Junction → vehicle allowed to come closer than JUNCTION_CLAIM. Sorted, so release order is stable. */
  private final java.util.TreeMap<String, String> claims = new java.util.TreeMap<>();

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
    Set<String> entered = new java.util.HashSet<>();
    for (var e : FlowPilotMasterData.edges()) if (!entered.add(e.to())) junctions.add(e.to());
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

  /**
   * Nodes no route may pass (the target of a route is always allowed): where failed vehicles stand, and the node
   * ahead if one stopped on an edge; where a repaired vehicle waits to hand over its load; and chargers with a
   * parked vehicle, which would otherwise block a detour through the charger spur for good.
   */
  Set<String> avoidNodes() {
    return avoidNodesExcept(null);
  }

  private Set<String> avoidNodesExcept(Vehicle except) {
    TreeSet<String> nodes = new TreeSet<>();
    for (Vehicle v : vehicles) {
      if (v == except) continue;
      if (v.status == AgvStatus.FAULT || v.heldLoad != null) {
        nodes.add(v.nodeId);
        if (!v.route.isEmpty() && !dispatcher.atNode(v)) nodes.add(v.route.get(0));
      } else if (parked(v)) {
        nodes.add(v.nodeId);
      }
    }
    return nodes;
  }

  private boolean parked(Vehicle v) {
    return (v.status == AgvStatus.IDLE || v.status == AgvStatus.CHARGING || v.status == AgvStatus.OFFLINE)
        && atOwnCharger(v);
  }

  private Vehicle holderOf(String transportId) {
    for (Vehicle v : vehicles) if (transportId.equals(v.heldLoad)) return v;
    return null;
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

  /**
   * One step. A unit that fails for one vehicle or transport is reported once and skipped; the others carry on.
   * Its vehicle keeps its state (memory only changes after a commit) and tries again next step.
   */
  public void step(long dtMs) {
    dispatch();
    releaseClaims();
    for (Vehicle v : vehicles) {
      try {
        move(v, dtMs);
        v.lastFailure = null;
      } catch (RuntimeException e) {
        reportFailure(v.id, v.transportId, v, e);
      }
    }
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
      Vehicle holder = holderOf(t.id());
      Dispatcher.Choice c = dispatcher.choose(t, free, avoid, holder == null ? avoid : avoidNodesExcept(holder));
      if (c.vehicle() == null) {
        if (c.blocked()) reportBlocked(t);
        continue;
      }
      try {
        assign(t, c);
        free.remove(c.vehicle());
      } catch (RuntimeException e) {
        reportFailure(c.vehicle().id, t.id(), c.vehicle(), e);
      }
    }
  }

  /** decision with severity ERROR, once per vehicle until it moves without failing again. */
  private void reportFailure(String agvId, String transportId, Vehicle v, RuntimeException e) {
    // only business messages leave the core, as in EventBus
    String message = e instanceof DomainException || e instanceof io.nexus.contracts.state.IllegalTransitionException
        ? e.getMessage() : e.getClass().getSimpleName();
    if (message.equals(v.lastFailure)) return;
    v.lastFailure = message;
    work.inNewTransaction(() -> events.publish(EventTypes.DECISION, SOURCE, Severity.ERROR, null,
        new Payloads.Decision(transportId == null ? agvId : transportId, agvId, List.of(),
            "Schritt übersprungen: " + message)));
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
      saved[0] = saveAgv(v, v.status, AgvStatus.TO_PICKUP, t.orderId());
    });
    v.status = AgvStatus.TO_PICKUP;
    v.saved = saved[0];
    v.transportId = t.id();
    Vehicle holder = holderOf(t.id());
    v.rescueFrom = holder == null ? null : holder.id;
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
        if (v.holding || (v.route.isEmpty() && !v.nodeId.equals(v.goal))) replan(v);
        if (v.route.isEmpty() || v.holding) stand(v, dt);
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
        if (v.enabled) change(v, AgvStatus.IDLE, null); // a charge ordered meanwhile is pending in chargeRequested
      }
      case IDLE -> {
        stand(v, dt);
        idle(v);
      }
      case FAULT -> {
        stand(v, dt);
        if (time.nowMs() >= v.faultUntilMs) repair(v);
      }
    }
    if (v.driving() && arrived(v)) arrive(v);
  }

  private void idle(Vehicle v) {
    if (v.heldLoad != null) return; // still holds a load (repair takes it back); never drive off with it
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
    if (v.status == AgvStatus.TO_PICKUP && v.rescueFrom != null && v.rescueFrom.equals(v.blockedBy)) {
      return true; // up to the following distance behind the failed vehicle
    }
    return v.route.isEmpty() && v.nodeId.equals(v.goal) && dispatcher.atNode(v);
  }

  private void arrive(Vehicle v) {
    switch (v.status) {
      case TO_PICKUP -> arrivedAtPickup(v);
      case TO_DROP -> arrivedAtDrop(v);
      case TO_CHARGE -> {
        change(v, v.enabled ? AgvStatus.CHARGING : AgvStatus.OFFLINE, null);
        // parked now: whoever planned a detour through this charger plans again
        Set<String> here = Set.of(v.nodeId);
        for (Vehicle u : vehicles) {
          if (u != v && u.driving() && !v.nodeId.equals(u.goal) && routeUsesNode(u, here)) replan(u);
        }
      }
      default -> { }
    }
  }

  private void arrivedAtPickup(Vehicle v) {
    TransportOrder t = transport(v.transportId);
    if (t.kind() == TransportKind.SUPPLY && t.reservationId() != null && materials.released(t.reservationId())) {
      cancelReleased(v, t);
      return;
    }
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> {
      moveTransport(t, TransportStatus.LOADING, v.id, EventTypes.TRANSPORT_STATE);
      // stock leaves at pickup; a reservation already consumed (rescue of a failed load) is not taken twice
      if (t.kind() == TransportKind.SUPPLY && t.reservationId() != null) materials.consume(t.reservationId());
      saved[0] = saveAgv(v, v.status, AgvStatus.LOADING, t.orderId());
    });
    v.status = AgvStatus.LOADING;
    v.saved = saved[0];
    v.timerMs = AgvPhysics.LOAD_TIME_MS;
    v.speed = 0;
    Vehicle holder = holderOf(t.id());
    if (holder != null) holder.heldLoad = null; // the load changes vehicles
    v.rescueFrom = null;
  }

  /** The order gave its reservation back before pickup (e.g. it was cancelled): nothing to carry any more. */
  private void cancelReleased(Vehicle v, TransportOrder t) {
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> {
      events.publish(EventTypes.DECISION, SOURCE, Severity.WARNING, t.orderId(), new Payloads.Decision(t.id(), v.id,
          List.of(), "Reservierung " + t.reservationId() + " freigegeben, Transport entfällt"));
      moveTransport(t, TransportStatus.CANCELLED, v.id, EventTypes.TRANSPORT_STATE);
      saved[0] = saveAgv(v, v.status, AgvStatus.IDLE, t.orderId());
    });
    v.status = AgvStatus.IDLE;
    v.saved = saved[0];
    v.transportId = null;
    v.goal = null;
    v.rescueFrom = null;
  }

  /** Refuses to cancel a transport whose load is still on a failed vehicle: it would be stranded there. */
  public void checkCancellable(String transportId) {
    if (holderOf(transportId) != null) {
      throw DomainException.conflict("Transport " + transportId
          + " kann nicht storniert werden: die Ladung liegt auf einem ausgefallenen AGV");
    }
  }

  private void finishLoading(Vehicle v) {
    TransportOrder t = transport(v.transportId);
    Route r = graph.route(dispatcher.anchor(v).node(), t.toNode(), avoidNodes());
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> {
      moveTransport(t, TransportStatus.IN_TRANSIT, v.id, EventTypes.TRANSPORT_STATE);
      saved[0] = saveAgv(v, v.status, AgvStatus.TO_DROP, t.orderId());
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
      saved[0] = saveAgv(v, v.status, AgvStatus.UNLOADING, t.orderId());
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
        saved[0] = saveAgv(v, v.status, AgvStatus.IDLE, t.orderId());
      });
    } catch (DomainException notStored) {
      if (notStored.kind() != DomainException.Kind.CONFLICT) throw notStored; // anything else is not "full"
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
        replan(v);
      }
    }
  }

  // ---- faults and blocks ---------------------------------------------------------------------------

  /**
   * The vehicle stops where it is. An unloaded transport goes back to the queue; a loaded one fails and is
   * picked up where the vehicle stands, by a replacement or by this vehicle once it is repaired.
   */
  public void injectFault(String agvId, String faultTypeId) {
    String type = faultTypeId == null ? "NAV" : faultTypeId;
    String label = switch (type) {
      case "NAV" -> "Navigationsfehler (Lidar)";
      case "COMM" -> "Kommunikationsfehler (Feldbus)";
      default -> throw DomainException.invalid("Störungsart " + type + " gibt es für AGVs nicht (NAV, COMM)");
    };
    Vehicle v = vehicle(agvId);
    AgvStatus.RULES.move(v.id, v.status, AgvStatus.FAULT);
    TransportOrder t = v.transportId == null ? null : transport(v.transportId);
    boolean loaded = t != null && (t.status() == TransportStatus.LOADING || t.status() == TransportStatus.IN_TRANSIT
        || t.status() == TransportStatus.UNLOADING);
    String correlation = t == null ? null : t.orderId();
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> {
      saved[0] = saveAgv(v, v.status, AgvStatus.FAULT, correlation);
      events.publish(EventTypes.AGV_FAULT, SOURCE, Severity.ERROR, correlation,
          new Payloads.AgvFault(v.id, type, label));
      if (t == null) return;
      TransportStatus to = loaded ? TransportStatus.FAILED : TransportStatus.CREATED;
      TransportStatus.RULES.move(t.id(), t.status(), to);
      // a failed load is picked up where the vehicle stopped
      transports.update(loaded ? t.withStatus(to, v.id).withPickup(v.nodeId) : t.withStatus(to, null));
      events.publish(loaded ? EventTypes.TRANSPORT_FAILED : EventTypes.TRANSPORT_STATE, SOURCE,
          loaded ? Severity.ERROR : Severity.WARNING, t.orderId(),
          new Payloads.TransportStateChanged(t.id(), t.status(), to, loaded ? v.id : null));
    });
    v.status = AgvStatus.FAULT;
    v.saved = saved[0];
    v.speed = 0;
    v.blockedBy = null;
    v.faultTypeId = type;
    v.faultUntilMs = time.nowMs() + AgvPhysics.FAULT_REPAIR_MS;
    v.rescueFrom = null;
    v.goal = null;
    if (t != null) {
      v.transportId = null;
      if (loaded) v.heldLoad = t.id();
    }
    graphChanged();
    Set<String> blocked = avoidNodes();
    for (Vehicle u : vehicles) {
      if (u != v && u.driving() && routeUsesNode(u, blocked)) replan(u);
    }
  }

  private void repair(Vehicle v) {
    change(v, AgvStatus.IDLE, null);
    v.faultTypeId = null;
    graphChanged(); // vehicles waiting for a route replan in their next move
    if (v.heldLoad == null) return;
    TransportOrder t = transport(v.heldLoad);
    if (t.status() == TransportStatus.ASSIGNED || t.status() == TransportStatus.TO_PICKUP) {
      t = releaseReplacement(t);
    }
    takeBack(v, t);
  }

  /** The replacement on its way is no longer needed: back to the queue, its vehicle is free. */
  private TransportOrder releaseReplacement(TransportOrder t) {
    Vehicle rescuer = vehicle(t.agvId());
    AgvRecord[] saved = new AgvRecord[1];
    TransportOrder[] back = new TransportOrder[1];
    work.inNewTransaction(() -> {
      back[0] = moveTransport(t, TransportStatus.CREATED, null, EventTypes.TRANSPORT_STATE);
      saved[0] = saveAgv(rescuer, rescuer.status, AgvStatus.IDLE, t.orderId());
    });
    rescuer.status = AgvStatus.IDLE;
    rescuer.saved = saved[0];
    rescuer.transportId = null;
    rescuer.goal = null;
    rescuer.rescueFrom = null;
    keepOnlyNextNode(rescuer);
    return back[0];
  }

  /** A repaired vehicle that still carries its load continues the transport itself; the load is on board. */
  private void takeBack(Vehicle v, TransportOrder t) {
    Route r = graph.route(dispatcher.anchor(v).node(), t.toNode(), avoidNodes());
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> {
      TransportOrder a = moveTransport(t, TransportStatus.ASSIGNED, v.id,
          t.status() == TransportStatus.FAILED ? EventTypes.TRANSPORT_REASSIGNED : EventTypes.TRANSPORT_ASSIGNED);
      TransportOrder b = moveTransport(a, TransportStatus.TO_PICKUP, v.id, EventTypes.TRANSPORT_STATE);
      TransportOrder c = moveTransport(b, TransportStatus.LOADING, v.id, EventTypes.TRANSPORT_STATE);
      moveTransport(c, TransportStatus.IN_TRANSIT, v.id, EventTypes.TRANSPORT_STATE);
      agvState(v, AgvStatus.IDLE, AgvStatus.TO_PICKUP, t.orderId());
      agvState(v, AgvStatus.TO_PICKUP, AgvStatus.LOADING, t.orderId());
      saved[0] = saveAgv(v, AgvStatus.LOADING, AgvStatus.TO_DROP, t.orderId());
    });
    v.status = AgvStatus.TO_DROP;
    v.saved = saved[0];
    v.transportId = t.id();
    v.heldLoad = null;
    v.chargerId = null;
    setRoute(v, t.toNode(), r);
  }

  public void setEdgeBlocked(String edgeId, boolean blocked) {
    work.inNewTransaction(() -> store.setEdgeBlocked(edgeId, blocked));
    graph.setBlocked(edgeId, blocked);
    graphChanged();
    if (!blocked) return; // waiting vehicles replan in their next move
    for (Vehicle u : vehicles) if (u.driving() && routeUsesEdge(u, edgeId)) replan(u);
  }

  private void graphChanged() {
    reportedBlocked.clear();
  }

  /** The edges still ahead; the one the vehicle is on right now is not counted, it drives that one to its end. */
  private boolean routeUsesEdge(Vehicle u, String edgeId) {
    String prev = dispatcher.atNode(u) ? u.nodeId : null;
    for (String n : u.route) {
      if (prev != null) {
        var e = graph.edge(prev, n);
        if (e != null && e.id().equals(edgeId)) return true;
      }
      prev = n;
    }
    return false;
  }

  private static boolean routeUsesNode(Vehicle u, Set<String> nodes) {
    for (String n : u.route) if (nodes.contains(n)) return true;
    return false;
  }

  // ---- motion --------------------------------------------------------------------------------------

  private void setRoute(Vehicle v, String goal, Route r) {
    v.goal = goal;
    v.routeBlockedReported = false;
    if (r == null || !r.found()) {
      // waits for a route at the next node; dropping that node would make it drive back against its edge
      keepOnlyNextNode(v);
      return;
    }
    v.route.clear();
    v.holding = false;
    List<String> nodes = r.nodes();
    int first = nodes.get(0).equals(v.nodeId) && dispatcher.atNode(v) ? 1 : 0;
    for (int i = first; i < nodes.size(); i++) v.route.add(nodes.get(i));
  }

  /** A vehicle without a route keeps only the node ahead (if it stands on an edge) and waits where it is. */
  private void keepOnlyNextNode(Vehicle v) {
    if (v.route.isEmpty() || dispatcher.atNode(v)) {
      v.route.clear();
      v.holding = false;
      return;
    }
    String next = v.route.get(0);
    v.route.clear();
    v.route.add(next);
    v.holding = true;
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
    if (step > 1e-9 && !claimJunctions(v, AgvPhysics.JUNCTION_CLAIM + step)) {
      v.speed = 0; // waits outside the claim distance until the junction is free
      step = 0;
    }
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

  /**
   * Claims every junction within the given distance along the route. False, and nothing claimed, if another
   * vehicle holds one of them; the holder is then recorded as what this vehicle waits for.
   */
  private boolean claimJunctions(Vehicle v, double within) {
    List<String> ahead = new ArrayList<>();
    double px = v.x, pz = v.z, travelled = 0;
    for (String id : v.route) {
      var n = graph.node(id);
      double dx = n.x() - px, dz = n.z() - pz;
      travelled += Math.sqrt(dx * dx + dz * dz);
      if (travelled > within) break;
      if (junctions.contains(id)) ahead.add(id);
      px = n.x();
      pz = n.z();
    }
    for (String j : ahead) {
      String holder = claims.get(j);
      if (holder != null && !holder.equals(v.id)) {
        v.blockedBy = holder;
        return false;
      }
    }
    for (String j : ahead) claims.put(j, v.id);
    return true;
  }

  /** A vehicle gives a junction up once it is no longer on its route and it is a safety distance away. */
  private void releaseClaims() {
    for (String j : new ArrayList<>(claims.keySet())) {
      Vehicle u = vehicle(claims.get(j));
      var n = graph.node(j);
      double dx = n.x() - u.x, dz = n.z() - u.z;
      if (!u.route.contains(j) && Math.sqrt(dx * dx + dz * dz) >= AgvPhysics.SAFETY_DISTANCE) claims.remove(j);
    }
  }

  /**
   * True if another vehicle is ahead within the safety distance plus this step: straight ahead, or along the
   * route around a corner. Lower id wins a mutual block.
   */
  private boolean obstructed(Vehicle v, double look) {
    double limit = AgvPhysics.SAFETY_DISTANCE + look;
    for (Vehicle o : vehicles) {
      if (o == v) continue;
      double dx = o.x - v.x, dz = o.z - v.z;
      double ahead = dx * v.dirX + dz * v.dirZ;
      double side = Math.abs(-dx * v.dirZ + dz * v.dirX);
      boolean straight = ahead > 0 && ahead < limit && side < AgvPhysics.LANE_HALF_WIDTH;
      // a replacement comes up to the following distance of the failed vehicle, from whatever side
      boolean rescue = o.id.equals(v.rescueFrom) && Math.sqrt(dx * dx + dz * dz) < limit;
      if (straight || rescue || onRouteWithin(v, o, limit)) {
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

  /**
   * True if o stands on v's remaining route (within a lane's half width) less than limit metres ahead. Past the
   * goal the route counts as going straight on: a vehicle just beyond a dock blocks the one heading for it.
   */
  private boolean onRouteWithin(Vehicle v, Vehicle o, double limit) {
    double px = v.x, pz = v.z, travelled = 0;
    double ux = v.dirX, uz = v.dirZ;
    for (String id : v.route) {
      if (travelled >= limit) return false;
      var n = graph.node(id);
      double sx = n.x() - px, sz = n.z() - pz;
      double len = Math.sqrt(sx * sx + sz * sz);
      if (len > 1e-9) {
        ux = sx / len;
        uz = sz / len;
        if (onSegment(o, px, pz, ux, uz, len, limit - travelled)) return true;
      }
      travelled += len;
      px = n.x();
      pz = n.z();
    }
    return travelled < limit && onSegment(o, px, pz, ux, uz, Double.POSITIVE_INFINITY, limit - travelled);
  }

  /** o lies within a lane's half width of the segment from (px, pz) along (ux, uz), less than limit along it. */
  private static boolean onSegment(Vehicle o, double px, double pz, double ux, double uz, double len, double limit) {
    double dx = o.x - px, dz = o.z - pz;
    double along = dx * ux + dz * uz;
    double side = Math.abs(-dx * uz + dz * ux);
    return along > 0 && along <= len && along < limit && side < AgvPhysics.LANE_HALF_WIDTH;
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

  /**
   * Charges to 95 %. A vehicle already charging or on its way just gets the higher target; any other one goes
   * as soon as it is idle (after its transport, its repair, or once enabled again).
   */
  public void sendToCharge(String agvId) {
    Vehicle v = vehicle(agvId);
    if (v.transportId == null && (v.status == AgvStatus.CHARGING || v.status == AgvStatus.TO_CHARGE)) {
      v.chargeTarget = AgvPhysics.MANUAL_CHARGE_TARGET;
    } else {
      v.chargeRequested = true;
    }
  }

  // ---- bookkeeping ---------------------------------------------------------------------------------

  /** Changes the AGV state in a unit of its own and publishes agv.state. */
  void change(Vehicle v, AgvStatus to, String correlationId) {
    AgvRecord[] saved = new AgvRecord[1];
    work.inNewTransaction(() -> saved[0] = saveAgv(v, v.status, to, correlationId));
    v.status = to;
    v.saved = saved[0];
  }

  /** Inside a unit: checks the transition, publishes agv.state and stores the AGV in its new state. */
  private AgvRecord saveAgv(Vehicle v, AgvStatus from, AgvStatus to, String correlationId) {
    agvState(v, from, to, correlationId);
    AgvRecord r = v.record();
    return store.save(new AgvRecord(r.id(), r.name(), to, r.nodeId(), r.battery(), r.enabled(), r.version()));
  }

  /** Inside a unit: checks the transition and publishes agv.state, without storing. */
  private void agvState(Vehicle v, AgvStatus from, AgvStatus to, String correlationId) {
    AgvStatus.RULES.move(v.id, from, to);
    if (from != to) {
      events.publish(EventTypes.AGV_STATE, SOURCE, Severity.INFO, correlationId,
          new Payloads.AgvStateChanged(v.id, from, to));
    }
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
