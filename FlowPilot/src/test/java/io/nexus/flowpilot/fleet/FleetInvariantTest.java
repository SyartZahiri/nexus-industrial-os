package io.nexus.flowpilot.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.port.MaterialService.ReservationRequest;
import io.nexus.contracts.port.MaterialService.ReservationResult;
import io.nexus.contracts.port.TransportService.TransportRequest;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.flowpilot.FlowPilot.VehicleView;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.store.Reservation;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.TransportOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Two hours of simulated traffic with random orders (Review Focus 1): after every step no moving AGV has another
 * one within the safety distance ahead, no AGV holds two active transports, no unit of material is lost or doubled.
 */
class FleetInvariantTest {
  static final String[] RAW = {"RM-GH200", "RM-AW50", "RM-FP12", "RM-MT7"};
  static final String[] CNC = {"CNC-01", "CNC-02", "CNC-03", "CNC-04"};
  static final String[] FINISHED = {"FG-GH200", "FG-AW50", "FG-FP12", "FG-MT7"};

  /** Hook for Task 7: random faults and edge blocks. */
  protected void disturb(FleetFixture f, SeededRandom random) {}

  @Test
  void longRunKeepsDistanceAndConservesMaterial() {
    var f = new FleetFixture();
    var random = new SeededRandom(7).fork("orders");
    var disturbances = new SeededRandom(7).fork("disturbances");
    Map<String, Integer> lastOnHand = new HashMap<>();
    Map<String, Integer> replenished = new HashMap<>();
    for (var s : FlowPilotMasterData.initialStock()) lastOnHand.put(s.materialId() + "@" + s.locationId(), s.onHand());
    f.bus.register(new EventConsumer() {
      public String name() { return "test-stock"; }
      public void on(NexusEvent e) {
        if (!e.type().equals(EventTypes.STOCK_CHANGED)) return;
        var p = (Payloads.StockChanged) e.payload();
        Integer before = lastOnHand.put(p.materialId() + "@" + p.locationId(), p.onHand());
        if (e.correlationId() == null) replenished.merge(p.materialId(), p.onHand() - (before == null ? 0 : before), Integer::sum);
      }
    });

    int created = 0;
    long end = 2 * 3600 * 1000;
    for (int step = 0; f.time.nowMs() < end; step++) {
      if (step % 300 == 0) created += order(f, random, created);
      disturb(f, disturbances);
      f.step();
      checkDistances(f.fp.vehicles());
      checkTransports(f);
      if (step % 50 == 0) checkMaterial(f, replenished);
    }
    checkMaterial(f, replenished);
    long delivered = f.transports.all().stream().filter(t -> t.status() == TransportStatus.DELIVERED).count();
    assertThat(created).isGreaterThan(200);
    assertThat(delivered).as("delivered of " + created).isGreaterThanOrEqualTo((long) Math.ceil(created * 0.9));
  }

  private static int order(FleetFixture f, SeededRandom r, int n) {
    String id = "INV-" + n;
    Priority priority = Priority.values()[r.nextInt(3)];
    int kind = r.nextInt(4);
    if (kind == 0) {
      String material = FINISHED[r.nextInt(4)];
      f.fp.transports().requestTransport(new TransportRequest(id, TransportKind.PUTAWAY, "PO-" + id, material,
          1 + r.nextInt(2), Place.machineOut("QC-01"), Place.storage("FG-A"), priority, null));
      return 1;
    }
    if (kind == 1) {
      String from = CNC[r.nextInt(4)];
      String to = CNC[r.nextInt(4)];
      if (from.equals(to)) return 0;
      f.fp.transports().requestTransport(new TransportRequest(id, TransportKind.WIP, "PO-" + id, null, 1,
          Place.machineOut(from), Place.machineIn(to), priority, null));
      return 1;
    }
    String material = RAW[r.nextInt(4)];
    var result = f.fp.materials().reserve(new ReservationRequest("RQ-" + id, "PO-" + id,
        List.of(new Line(material, 1 + r.nextInt(3)))));
    if (!(result instanceof ReservationResult.Success ok)) return 0;
    var res = ok.reservations().get(0);
    f.fp.transports().requestTransport(new TransportRequest(id, TransportKind.SUPPLY, "PO-" + id, material,
        res.quantity(), Place.storage(res.locationId()), Place.machineIn(CNC[r.nextInt(4)]), priority,
        res.reservationId()));
    return 1;
  }

  static void checkDistances(List<VehicleView> vehicles) {
    for (VehicleView v : vehicles) {
      if (v.speed() <= 0) continue;
      double hx = Math.sin(v.heading()), hz = Math.cos(v.heading());
      for (VehicleView o : vehicles) {
        if (o == v) continue;
        double dx = o.x() - v.x(), dz = o.z() - v.z();
        double ahead = dx * hx + dz * hz;
        double side = Math.abs(-dx * hz + dz * hx);
        assertThat(ahead > 0 && ahead < AgvPhysics.SAFETY_DISTANCE - 0.05 && side < 0.6)
            .as(v.id() + " drives with " + o.id() + " " + ahead + " m ahead").isFalse();
      }
      assertThat(v.battery()).isBetween(0.0, 100.0);
    }
  }

  static void checkTransports(FleetFixture f) {
    Map<String, String> activeByAgv = new HashMap<>();
    for (TransportOrder t : f.transports.all()) {
      if (!TransportStatus.ACTIVE.contains(t.status())) continue;
      assertThat(t.agvId()).as(t.id()).isNotNull();
      String other = activeByAgv.put(t.agvId(), t.id());
      assertThat(other).as(t.agvId() + " holds " + other + " and " + t.id()).isNull();
      assertThat(f.vehicle(t.agvId()).transportId()).as(t.id()).isEqualTo(t.id());
    }
    for (VehicleView v : f.fp.vehicles()) {
      assertThat(v.battery()).isBetween(0.0, 100.0);
      if (v.transportId() != null) {
        assertThat(f.transport(v.transportId()).agvId()).isEqualTo(v.id());
      }
    }
  }

  /** Every unit is on hand, on an AGV or delivered: on hand = initial + receipts + put away − consumed. */
  static void checkMaterial(FleetFixture f, Map<String, Integer> replenished) {
    Map<String, Integer> balance = new HashMap<>();
    for (var s : FlowPilotMasterData.initialStock()) balance.merge(s.materialId(), s.onHand(), Integer::sum);
    replenished.forEach((m, q) -> balance.merge(m, q, Integer::sum));
    Map<String, Integer> consumed = new HashMap<>();
    for (StockLine l : f.stock.lines()) balance.merge(l.materialId(), -l.onHand(), Integer::sum);
    for (TransportOrder t : f.transports.all()) {
      if (t.kind() == TransportKind.PUTAWAY && t.status() == TransportStatus.DELIVERED) {
        balance.merge(t.materialId(), t.quantity(), Integer::sum);
      }
      if (t.kind() == TransportKind.SUPPLY) {
        boolean pickedUp = t.status() == TransportStatus.LOADING || t.status() == TransportStatus.IN_TRANSIT
            || t.status() == TransportStatus.UNLOADING || t.status() == TransportStatus.FAILED
            || t.status() == TransportStatus.DELIVERED || (t.status() == TransportStatus.ASSIGNED
                && f.stock.reservation(t.reservationId()).orElseThrow().status() == Reservation.Status.CONSUMED)
            || (t.status() == TransportStatus.TO_PICKUP
                && f.stock.reservation(t.reservationId()).orElseThrow().status() == Reservation.Status.CONSUMED);
        if (pickedUp) consumed.merge(t.materialId(), t.quantity(), Integer::sum);
      }
    }
    Map<String, Integer> consumedReservations = new HashMap<>();
    for (TransportOrder t : f.transports.all()) {
      if (t.reservationId() == null) continue;
      var r = f.stock.reservation(t.reservationId()).orElseThrow();
      if (r.status() == Reservation.Status.CONSUMED) consumedReservations.merge(r.materialId(), r.quantity(), Integer::sum);
    }
    assertThat(consumedReservations).as("consumed exactly once per picked up load").isEqualTo(consumed);
    consumed.forEach((m, q) -> balance.merge(m, -q, Integer::sum));
    balance.forEach((m, b) -> assertThat(b).as("balance of " + m).isZero());
  }
}
