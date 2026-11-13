package io.nexus.flowpilot.fleet;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.port.MaterialService.ReservationRequest;
import io.nexus.contracts.port.MaterialService.ReservationResult;
import io.nexus.contracts.port.TransportService.TransportRequest;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.store.AgvRecord;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.flowpilot.store.memory.MemoryFleetStore;
import io.nexus.flowpilot.store.memory.MemoryStockStore;
import io.nexus.flowpilot.store.memory.MemoryTransportStore;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** FlowPilot on memory stores with direct access to everything a fleet test needs. */
class FleetFixture {
  static final long STEP_MS = 100;

  final MemoryUnitOfWork work = new MemoryUnitOfWork();
  final MemoryEventLog log = new MemoryEventLog(work);
  final SimTime time = new SimTime();
  final EventIds eventIds = new EventIds(1);
  final EventBus bus = new EventBus(log, work, time, () -> eventIds);
  final MemoryIdSource ids = new MemoryIdSource(work);
  final MemoryStockStore stock = new MemoryStockStore(work);
  final MemoryTransportStore transports = new MemoryTransportStore(work);
  final MemoryFleetStore fleet = new MemoryFleetStore(work);
  final FlowPilot fp;
  private int requests;

  FleetFixture() {
    this(f -> {});
  }

  /** @param before changes the stores before FlowPilot loads them, e.g. a low battery */
  FleetFixture(Consumer<FleetFixture> before) {
    before.accept(this);
    fp = new FlowPilot(stock, transports, fleet, bus, work, time, ids);
  }

  void setBattery(String agvId, double battery) {
    for (AgvRecord a : fleet.agvs()) {
      if (a.id().equals(agvId)) {
        fleet.save(new AgvRecord(a.id(), a.name(), a.status(), a.nodeId(), battery, a.enabled(), a.version()));
      }
    }
  }

  void step() {
    time.advance(STEP_MS);
    fp.step(STEP_MS);
  }

  void run(double seconds) {
    for (long t = 0; t < seconds * 1000; t += STEP_MS) step();
  }

  /** Steps until the condition holds; fails after maxSeconds. Returns the simulated seconds it took. */
  double runUntil(BooleanSupplier done, double maxSeconds) {
    long start = time.nowMs();
    while (!done.getAsBoolean()) {
      if (time.nowMs() - start > maxSeconds * 1000) throw new AssertionError("not reached within " + maxSeconds + " s");
      step();
    }
    return (time.nowMs() - start) / 1000.0;
  }

  String supply(String materialId, String machineId, int quantity, Priority priority) {
    String n = "" + (++requests);
    String orderId = "PO-T" + n;
    var r = (ReservationResult.Success) fp.materials().reserve(
        new ReservationRequest("RES-REQ-" + n, orderId, List.of(new Line(materialId, quantity))));
    var reserved = r.reservations().get(0);
    return fp.transports().requestTransport(new TransportRequest("TR-" + n, TransportKind.SUPPLY, orderId, materialId,
        quantity, Place.storage(reserved.locationId()), Place.machineIn(machineId), priority, reserved.reservationId()));
  }

  String putaway(String materialId, int quantity) {
    String n = "" + (++requests);
    return fp.transports().requestTransport(new TransportRequest("TR-" + n, TransportKind.PUTAWAY, "PO-T" + n,
        materialId, quantity, Place.machineOut("QC-01"), Place.storage("FG-A"), Priority.NORMAL, null));
  }

  TransportOrder transport(String id) {
    return transports.byId(id).orElseThrow();
  }

  FlowPilot.VehicleView vehicle(String id) {
    return fp.vehicles().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
  }

  /** The node the FleetStore holds for the AGV: where it stood at its last state change. */
  String fleetNode(String agvId) {
    return fleet.agvs().stream().filter(a -> a.id().equals(agvId)).findFirst().orElseThrow().nodeId();
  }

  List<NexusEvent> events() {
    return log.all();
  }

  List<String> types() {
    return log.all().stream().map(NexusEvent::type).toList();
  }
}
