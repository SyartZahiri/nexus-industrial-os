package io.nexus.flowpilot.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.port.TransportService.TransportRequest;
import io.nexus.contracts.state.AgvStatus;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.routing.PathGraph;
import io.nexus.flowpilot.routing.Route;
import io.nexus.flowpilot.store.AgvRecord;
import io.nexus.flowpilot.store.Reservation;
import io.nexus.flowpilot.store.StockLine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FaultTest {
  private static long count(FleetFixture f, String type) {
    return f.events().stream().filter(e -> e.type().equals(type)).count();
  }

  private static void assertKind(Throwable t, DomainException.Kind kind) {
    assertThat(t).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(kind));
  }

  @Test
  void unloadedFailedAgvReturnsItsOrderToTheQueue() {
    var f = new FleetFixture();
    String id = f.supply("RM-GH200", "CNC-01", 5, Priority.NORMAL);
    f.run(1);
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.TO_PICKUP);
    String failed = f.transport(id).agvId();
    f.fp.injectFault(failed, null);

    assertThat(f.vehicle(failed).status()).isEqualTo(AgvStatus.FAULT);
    assertThat(f.vehicle(failed).speed()).isZero();
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.CREATED);
    assertThat(f.transport(id).agvId()).isNull();
    NexusEvent fault = f.events().stream().filter(e -> e.type().equals(EventTypes.AGV_FAULT)).findFirst().orElseThrow();
    assertThat(fault.payload()).isEqualTo(new Payloads.AgvFault(failed, "NAV", "Navigationsfehler (Lidar)"));

    f.step();
    assertThat(f.transport(id).agvId()).isNotNull().isNotEqualTo(failed);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 300);
    f.runUntil(() -> f.vehicle(failed).status() != AgvStatus.FAULT, 70); // repaired after 60 s
  }

  @Test
  void loadedFailedAgvIsReplacedAndTheLoadArrives() {
    var f = new FleetFixture();
    String id = f.supply("RM-GH200", "CNC-01", 5, Priority.NORMAL);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.IN_TRANSIT, 120);
    f.run(2);
    String failed = f.transport(id).agvId();
    f.fp.injectFault(failed, "COMM");
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.FAILED);
    assertThat(f.types()).contains(EventTypes.TRANSPORT_FAILED);
    assertThat(f.transport(id).fromNode()).isEqualTo(f.fleetNode(failed));

    f.step();
    String rescuer = f.transport(id).agvId();
    assertThat(rescuer).isNotEqualTo(failed);
    assertThat(f.types()).contains(EventTypes.TRANSPORT_REASSIGNED);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 400);
    assertThat(f.transport(id).agvId()).isEqualTo(rescuer);
    assertThat(f.stock.lockMaterial("RM-GH200").get(0)).isEqualTo(new StockLine("RM-GH200", "RAW-A", 25, 0));
  }

  @Test
  void failureDuringLoadingAfterConsumptionDoesNotConsumeTwice() {
    var f = new FleetFixture();
    String id = f.supply("RM-GH200", "CNC-01", 5, Priority.NORMAL);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.LOADING, 120);
    var reservation = f.stock.reservation(f.transport(id).reservationId()).orElseThrow();
    assertThat(reservation.status()).isEqualTo(Reservation.Status.CONSUMED);
    String failed = f.transport(id).agvId();
    f.fp.injectFault(failed, "NAV");
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.FAILED);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 400);
    assertThat(f.stock.lockMaterial("RM-GH200").get(0)).isEqualTo(new StockLine("RM-GH200", "RAW-A", 25, 0));
    assertThat(f.events().stream().filter(e -> e.type().equals(EventTypes.STOCK_CHANGED)
        && ((Payloads.StockChanged) e.payload()).materialId().equals("RM-GH200"))).hasSize(2); // reserve, consume
  }

  /** Review I1: the load is on the failed AGV; cancelling would strand it and take that AGV out of service. */
  @Test
  void transportWhoseLoadIsOnAFailedAgvCannotBeCancelled() {
    var f = new FleetFixture();
    String id = f.supply("RM-GH200", "CNC-01", 5, Priority.NORMAL);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.IN_TRANSIT, 120);
    f.run(2);
    String failed = f.transport(id).agvId();
    f.fp.injectFault(failed, null);
    f.step();
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.TO_PICKUP);
    assertKind(catchThrowable(() -> f.fp.transports().cancel(id)), DomainException.Kind.CONFLICT);
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.TO_PICKUP);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 400);
    f.runUntil(() -> f.vehicle(failed).status() == AgvStatus.CHARGING || f.vehicle(failed).status() == AgvStatus.IDLE
        && f.vehicle(failed).route().isEmpty(), 120);
  }

  @Test
  void repairedAgvBeforeRescueTakesItsOrderBack() {
    var f = new FleetFixture();
    String id = f.supply("RM-GH200", "CNC-01", 5, Priority.NORMAL);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.IN_TRANSIT, 120);
    String failed = f.transport(id).agvId();
    for (var a : FlowPilotMasterData.agvs()) if (!a.id().equals(failed)) f.fp.setAgvEnabled(a.id(), false);
    f.fp.injectFault(failed, null);
    f.run(30);
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.FAILED);
    f.runUntil(() -> f.transport(id).status() != TransportStatus.FAILED, 40);
    assertThat(f.transport(id).agvId()).isEqualTo(failed);
    assertThat(f.vehicle(failed).transportId()).isEqualTo(id);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 300);
    assertThat(f.stock.lockMaterial("RM-GH200").get(0).onHand()).isEqualTo(25);
  }

  /** A transport whose shortest route passes a node with a failed AGV, and a detour that avoids it. */
  record Detour(String fromMachine, String toMachine, String node) {}

  private static Detour findDetour() {
    var graph = new PathGraph(FlowPilotMasterData.nodes(), FlowPilotMasterData.edges());
    Map<String, String> out = new LinkedHashMap<>();
    Map<String, String> in = new LinkedHashMap<>();
    for (var m : FlowPilotMasterData.machineDocks()) {
      if (m.outNode() != null) out.put(m.outNode(), m.machineId());
      in.put(m.inNode(), m.machineId());
    }
    Set<String> docks = Set.copyOf(FlowPilotMasterData.nodes().stream().filter(FlowPilotMasterData.Node::dock)
        .map(FlowPilotMasterData.Node::id).toList());
    for (var from : out.entrySet()) {
      for (var to : in.entrySet()) {
        Route direct = graph.route(from.getKey(), to.getKey(), Set.of());
        if (direct.nodes().size() < 3) continue;
        for (String middle : direct.nodes().subList(1, direct.nodes().size() - 1)) {
          if (docks.contains(middle) || middle.startsWith("D_")) continue;
          if (graph.route(from.getKey(), to.getKey(), Set.of(middle)).found()) {
            return new Detour(from.getValue(), to.getValue(), middle);
          }
        }
      }
    }
    throw new AssertionError("no detour in the hall graph");
  }

  @Test
  void othersRouteAroundAFailedAgv() {
    Detour d = findDetour();
    var f = new FleetFixture(fx -> {
      AgvRecord a = fx.fleet.agvs().get(5);
      fx.fleet.save(new AgvRecord(a.id(), a.name(), AgvStatus.IDLE, d.node(), a.battery(), true, a.version()));
    });
    f.fp.injectFault("AGV-06", null);
    String id = f.fp.transports().requestTransport(new TransportRequest("TR-WIP", TransportKind.WIP, "PO-1", null, 1,
        Place.machineOut(d.fromMachine()), Place.machineIn(d.toMachine()), Priority.NORMAL, null));
    while (f.vehicle("AGV-06").status() == AgvStatus.FAULT) {
      f.step();
      for (var v : f.fp.vehicles()) if (!v.id().equals("AGV-06")) assertThat(v.route()).doesNotContain(d.node());
    }
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 300);
  }

  @Test
  void blockedEdgeReroutesAndUnreachableWaitsWithOneEvent() {
    var f = new FleetFixture();
    // unreachable target: every edge into D_CNC01 is blocked before the order exists
    List<String> into = FlowPilotMasterData.edges().stream().filter(e -> e.to().equals("D_CNC01"))
        .map(FlowPilotMasterData.Edge::id).toList();
    for (String e : into) f.fp.setEdgeBlocked(e, true);
    assertThat(f.fleet.blockedEdges()).containsAll(into);
    assertThat(f.fp.graph().blockedEdges()).containsAll(into);
    String id = f.supply("RM-GH200", "CNC-01", 1, Priority.NORMAL);
    f.run(30);
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.CREATED);
    assertThat(count(f, EventTypes.ROUTE_BLOCKED)).isEqualTo(1);
    for (String e : into) f.fp.setEdgeBlocked(e, false);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 300);

    // blocked while driving: halts at the next node, reports once, drives on after unblocking
    String second = f.supply("RM-GH200", "CNC-01", 1, Priority.NORMAL);
    f.runUntil(() -> f.transport(second).status() == TransportStatus.IN_TRANSIT, 120);
    f.step();
    long blockedBefore = count(f, EventTypes.ROUTE_BLOCKED);
    for (String e : into) f.fp.setEdgeBlocked(e, true);
    f.run(40);
    assertThat(f.transport(second).status()).isEqualTo(TransportStatus.IN_TRANSIT);
    assertThat(count(f, EventTypes.ROUTE_BLOCKED)).isEqualTo(blockedBefore + 1);
    String agv = f.transport(second).agvId();
    assertThat(f.vehicle(agv).speed()).isZero();
    long replannedBefore = count(f, EventTypes.ROUTE_REPLANNED);
    for (String e : into) f.fp.setEdgeBlocked(e, false);
    f.step();
    assertThat(count(f, EventTypes.ROUTE_REPLANNED)).isGreaterThan(replannedBefore);
    f.runUntil(() -> f.transport(second).status() == TransportStatus.DELIVERED, 120);
  }

  @Test
  void blockedEdgeOnTheRouteForcesADetour() {
    var f = new FleetFixture();
    String id = f.supply("RM-GH200", "CNC-04", 1, Priority.NORMAL);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.IN_TRANSIT, 120);
    f.step();
    String agv = f.transport(id).agvId();
    List<String> route = f.vehicle(agv).route();
    var graph = f.fp.graph();
    String blocked = null;
    for (int i = route.size() - 1; i > 1 && blocked == null; i--) {
      String edge = graph.edge(route.get(i - 1), route.get(i)).id();
      graph.setBlocked(edge, true);
      boolean detour = graph.route(route.get(0), route.get(route.size() - 1), Set.of()).found();
      graph.setBlocked(edge, false);
      if (detour) blocked = edge;
    }
    assertThat(blocked).as("an edge with a detour on the route").isNotNull();
    long replanned = count(f, EventTypes.ROUTE_REPLANNED);
    f.fp.setEdgeBlocked(blocked, true);
    assertThat(count(f, EventTypes.ROUTE_REPLANNED)).isEqualTo(replanned + 1);
    List<String> now = f.vehicle(agv).route();
    for (int i = 1; i < now.size(); i++) assertThat(graph.edge(now.get(i - 1), now.get(i)).id()).isNotEqualTo(blocked);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 300);
  }

  @Test
  void unknownFaultTypeIsInvalid() {
    var f = new FleetFixture();
    assertKind(catchThrowable(() -> f.fp.injectFault("AGV-01", "BELT")), DomainException.Kind.INVALID);
    assertKind(catchThrowable(() -> f.fp.injectFault("AGV-99", null)), DomainException.Kind.NOT_FOUND);
    assertKind(catchThrowable(() -> f.fp.setEdgeBlocked("X>Y", true)), DomainException.Kind.NOT_FOUND);
    f.fp.injectFault("AGV-01", "COMM");
    assertThat(f.events().get(f.events().size() - 1).payload())
        .isEqualTo(new Payloads.AgvFault("AGV-01", "COMM", "Kommunikationsfehler (Feldbus)"));
  }
}
