package io.nexus.flowpilot.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.state.AgvStatus;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.routing.PathGraph;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.TransportOrder;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FleetTest {
  private static final PathGraph GRAPH = new PathGraph(FlowPilotMasterData.nodes(), FlowPilotMasterData.edges());

  private static int indexOf(List<String> types, String type, int from) {
    for (int i = from; i < types.size(); i++) if (types.get(i).equals(type)) return i;
    return -1;
  }

  /** The AGV with the shortest route from its charger to the node; ties by id. */
  private static String nearestTo(String node, Set<String> excluded) {
    String best = null;
    double bestLength = Double.POSITIVE_INFINITY;
    for (var a : FlowPilotMasterData.agvs()) {
      if (excluded.contains(a.id())) continue;
      double length = GRAPH.route(a.nodeId(), node, Set.of()).length();
      if (length < bestLength) {
        best = a.id();
        bestLength = length;
      }
    }
    return best;
  }

  @Test
  void supplyTransportIsDeliveredAndEventsComeInOrder() {
    var f = new FleetFixture();
    int before = f.events().size();
    String id = f.supply("RM-GH200", "CNC-01", 5, Priority.NORMAL);
    double seconds = f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 300);

    List<String> types = f.types().subList(before, f.types().size());
    int created = indexOf(types, EventTypes.TRANSPORT_CREATED, 0);
    int decision = indexOf(types, EventTypes.DECISION, created);
    int assigned = indexOf(types, EventTypes.TRANSPORT_ASSIGNED, decision);
    int stock = indexOf(types, EventTypes.STOCK_CHANGED, assigned);
    int delivered = indexOf(types, EventTypes.TRANSPORT_DELIVERED, stock);
    assertThat(List.of(created, decision, assigned, stock, delivered)).doesNotContain(-1).isSorted();

    TransportOrder t = f.transport(id);
    String agv = nearestTo("D_RAW", Set.of());
    assertThat(t.agvId()).isEqualTo(agv);
    double distance = GRAPH.route(FlowPilotMasterData.agvs().stream().filter(a -> a.id().equals(agv)).findFirst()
        .orElseThrow().nodeId(), "D_RAW", Set.of()).length() + GRAPH.route("D_RAW", "D_CNC01", Set.of()).length();
    double expected = distance / AgvPhysics.MAX_SPEED + 2 * AgvPhysics.LOAD_TIME_MS / 1000.0;
    assertThat(seconds).isBetween(expected * 0.8, expected * 1.2);

    // stock taken once, at pickup
    assertThat(f.stock.lockMaterial("RM-GH200").get(0)).isEqualTo(new StockLine("RM-GH200", "RAW-A", 25, 0));
    NexusEvent last = f.events().stream().filter(e -> e.type().equals(EventTypes.TRANSPORT_DELIVERED)).findFirst()
        .orElseThrow();
    assertThat(((Payloads.TransportDelivered) last.payload()).to().id()).isEqualTo("CNC-01");
    assertThat(f.vehicle(agv).transportId()).isNull();
    // the AGV parks at a charger afterwards
    f.run(60);
    assertThat(f.vehicle(agv).status()).isIn(AgvStatus.CHARGING, AgvStatus.IDLE);
    assertThat(f.vehicle(agv).speed()).isZero();
  }

  @Test
  void nearestIdleAgvGetsTheOrder() {
    var f = new FleetFixture();
    String id = f.supply("RM-AW50", "CNC-04", 2, Priority.NORMAL);
    f.step();
    String expected = nearestTo("D_RAW", Set.of());
    assertThat(f.transport(id).agvId()).isEqualTo(expected);
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.TO_PICKUP);
    var decision = (Payloads.Decision) f.events().stream().filter(e -> e.type().equals(EventTypes.DECISION))
        .reduce((a, b) -> b).orElseThrow().payload();
    assertThat(decision.chosen()).isEqualTo(expected);
    assertThat(decision.candidates()).hasSize(6);
    assertThat(decision.reason()).startsWith("kürzeste Anfahrt").endsWith(" m");
    assertThat(f.vehicle(expected).status()).isEqualTo(AgvStatus.TO_PICKUP);
  }

  @Test
  void urgentOrderIsDispatchedFirst() {
    var f = new FleetFixture();
    for (var a : FlowPilotMasterData.agvs()) if (!a.id().equals("AGV-01")) f.fp.setAgvEnabled(a.id(), false);
    String normal = f.supply("RM-GH200", "CNC-01", 1, Priority.NORMAL);
    f.time.advance(1000);
    String urgent = f.supply("RM-AW50", "CNC-03", 1, Priority.EILIG);
    f.step();
    assertThat(f.transport(urgent).agvId()).isEqualTo("AGV-01");
    assertThat(f.transport(normal).status()).isEqualTo(TransportStatus.CREATED);
  }

  @Test
  void disabledAgvIsNotDispatched() {
    var f = new FleetFixture();
    String nearest = nearestTo("D_RAW", Set.of());
    f.fp.setAgvEnabled(nearest, false);
    f.step();
    assertThat(f.vehicle(nearest).status()).isEqualTo(AgvStatus.OFFLINE);
    String id = f.supply("RM-GH200", "CNC-01", 1, Priority.NORMAL);
    f.step();
    assertThat(f.transport(id).agvId()).isEqualTo(nearestTo("D_RAW", Set.of(nearest)));
    f.fp.setAgvEnabled(nearest, true);
    f.step();
    assertThat(f.vehicle(nearest).status()).isEqualTo(AgvStatus.IDLE);
  }

  @Test
  void lowBatteryAgvChargesAndIsNotDispatchedBelowTheMinimum() {
    var f = new FleetFixture(fx -> fx.setBattery("AGV-01", 20));
    for (var a : FlowPilotMasterData.agvs()) if (!a.id().equals("AGV-01")) f.fp.setAgvEnabled(a.id(), false);
    f.step();
    assertThat(f.vehicle("AGV-01").status()).isEqualTo(AgvStatus.CHARGING);
    String id = f.supply("RM-GH200", "CNC-01", 1, Priority.NORMAL);
    f.run(5);
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.CREATED);
    double trip = GRAPH.route("D_CH1", "D_RAW", Set.of()).length() + GRAPH.route("D_RAW", "D_CNC01", Set.of()).length();
    double needed = AgvPhysics.MIN_FOR_TASK + trip * AgvPhysics.DRAIN_PER_METER + AgvPhysics.TASK_RESERVE;
    f.runUntil(() -> f.transport(id).status() != TransportStatus.CREATED, 120);
    assertThat(f.vehicle("AGV-01").battery()).isGreaterThanOrEqualTo(needed - 0.1);
    assertThat(f.transport(id).agvId()).isEqualTo("AGV-01");
  }

  @Test
  void idleAgvBelowLowBatteryGoesCharging() {
    var f = new FleetFixture(fx -> fx.setBattery("AGV-02", 24));
    f.step();
    assertThat(f.vehicle("AGV-02").status()).isEqualTo(AgvStatus.CHARGING);
    f.runUntil(() -> f.vehicle("AGV-02").status() == AgvStatus.IDLE, 120);
    assertThat(f.vehicle("AGV-02").battery()).isBetween(AgvPhysics.RESUME_FROM_CHARGE, AgvPhysics.RESUME_FROM_CHARGE + 0.1);
    assertThat(f.fleet.agvs().get(1).battery()).isBetween(40.0, 40.1); // persisted, rounded to 0.1
  }

  @Test
  void chargingAgvWithEnoughBatteryCanBeDispatched() {
    var f = new FleetFixture(fx -> fx.setBattery("AGV-01", 24));
    for (var a : FlowPilotMasterData.agvs()) if (!a.id().equals("AGV-01")) f.fp.setAgvEnabled(a.id(), false);
    f.step();
    assertThat(f.vehicle("AGV-01").status()).isEqualTo(AgvStatus.CHARGING);
    double trip = GRAPH.route("D_CH1", "D_RAW", Set.of()).length() + GRAPH.route("D_RAW", "D_CNC01", Set.of()).length();
    double needed = AgvPhysics.MIN_FOR_TASK + trip * AgvPhysics.DRAIN_PER_METER + AgvPhysics.TASK_RESERVE;
    assertThat(needed).isLessThan(AgvPhysics.RESUME_FROM_CHARGE);
    f.runUntil(() -> f.vehicle("AGV-01").battery() >= needed + 0.1, 60);
    assertThat(f.vehicle("AGV-01").status()).isEqualTo(AgvStatus.CHARGING);
    String id = f.supply("RM-GH200", "CNC-01", 1, Priority.NORMAL);
    f.step();
    assertThat(f.transport(id).agvId()).isEqualTo("AGV-01");
    assertThat(f.vehicle("AGV-01").status()).isEqualTo(AgvStatus.TO_PICKUP);
  }

  @Test
  void sendToChargeChargesToNinetyFivePercentFirst() {
    var f = new FleetFixture(fx -> fx.setBattery("AGV-01", 60));
    for (var a : FlowPilotMasterData.agvs()) if (!a.id().equals("AGV-01")) f.fp.setAgvEnabled(a.id(), false);
    f.fp.sendToCharge("AGV-01");
    f.step();
    String id = f.supply("RM-GH200", "CNC-01", 1, Priority.NORMAL);
    f.run(10);
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.CREATED);
    f.runUntil(() -> f.transport(id).status() != TransportStatus.CREATED, 120);
    assertThat(f.vehicle("AGV-01").battery()).isGreaterThanOrEqualTo(94.9);
  }

  @Test
  void putawayIntoAFullWarehouseWaitsAndCompletesWhenSpaceFrees() {
    var f = new FleetFixture();
    f.stock.saveLine(new StockLine("FG-GH200", "FG-A", 145, 0)); // FG-A: 145 + 4 + 8 + 3 = 160 of 160
    String id = f.putaway("FG-GH200", 5);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.UNLOADING, 300);
    f.run(30);
    assertThat(f.transport(id).status()).isEqualTo(TransportStatus.UNLOADING);
    assertThat(f.events().stream().filter(e -> e.type().equals(EventTypes.DECISION)
        && ((Payloads.Decision) e.payload()).reason().contains("Fertiglager voll"))).hasSize(1);
    f.stock.saveLine(new StockLine("FG-GH200", "FG-A", 100, 0));
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 10);
    assertThat(f.stock.lockMaterial("FG-GH200").get(0).onHand()).isEqualTo(105);
  }

  @Test
  void cancelledTransportFreesItsAgv() {
    var f = new FleetFixture();
    String id = f.supply("RM-GH200", "CNC-01", 1, Priority.NORMAL);
    f.run(1);
    String agv = f.transport(id).agvId();
    f.fp.transports().cancel(id);
    assertThat(f.vehicle(agv).transportId()).isNull();
    assertThat(f.vehicle(agv).status()).isNotEqualTo(AgvStatus.TO_PICKUP);
    f.run(60);
    assertThat(f.vehicle(agv).status()).isIn(AgvStatus.CHARGING, AgvStatus.IDLE);
  }

  @Test
  void redirectedLoadGoesToTheNewTarget() {
    var f = new FleetFixture();
    String id = f.supply("RM-GH200", "CNC-01", 1, Priority.NORMAL);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.IN_TRANSIT, 120);
    f.step();
    f.fp.transports().redirect(id, io.nexus.contracts.Place.machineIn("CNC-04"));
    assertThat(f.types()).contains(EventTypes.ROUTE_REPLANNED);
    assertThat(f.vehicle(f.transport(id).agvId()).route()).endsWith("D_CNC04");
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 120);
    var delivered = (Payloads.TransportDelivered) f.events().stream()
        .filter(e -> e.type().equals(EventTypes.TRANSPORT_DELIVERED)).findFirst().orElseThrow().payload();
    assertThat(delivered.to().id()).isEqualTo("CNC-04");
  }

  /** Found by the self-test: the detour around LN_-19>LN_-15 runs through the spur of CH-1, where AGV-01 parks. */
  @Test
  void detourNeverLeadsThroughAParkedVehicle() {
    var f = new FleetFixture();
    String id = f.supply("RM-GH200", "CNC-01", 5, Priority.NORMAL);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.IN_TRANSIT, 120);
    String agv = f.transport(id).agvId();
    assertThat(agv).isNotEqualTo("AGV-01");
    f.fp.setEdgeBlocked("LN_-19>LN_-15", true);
    assertThat(f.vehicle(agv).route()).doesNotContain("D_CH1");
    f.run(60);
    f.fp.setEdgeBlocked("LN_-19>LN_-15", false);
    f.runUntil(() -> f.transport(id).status() == TransportStatus.DELIVERED, 120);
  }

  @Test
  void sameSeedSameEvents() {
    assertThat(scriptedRun()).isEqualTo(scriptedRun());
  }

  private static List<String> scriptedRun() {
    var f = new FleetFixture();
    f.supply("RM-GH200", "CNC-01", 2, Priority.NORMAL);
    f.supply("RM-AW50", "CNC-03", 1, Priority.HOCH);
    f.run(5);
    f.supply("KIT-GH200", "ROB-01", 1, Priority.EILIG);
    f.putaway("FG-MT7", 1);
    f.run(120);
    return f.events().stream().map(e -> e.eventId() + " " + e.simTimeMs() + " " + e.type() + " " + e.payload()).toList();
  }
}
