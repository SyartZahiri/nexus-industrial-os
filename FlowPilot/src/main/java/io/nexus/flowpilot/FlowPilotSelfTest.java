package io.nexus.flowpilot;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.port.MaterialService.ReservationRequest;
import io.nexus.contracts.port.MaterialService.ReservationResult;
import io.nexus.contracts.port.TransportService.TransportRequest;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.AgvStatus;
import io.nexus.flowpilot.routing.PathGraph;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-test of the module for the browser build: platform/web runs it on the JVM and, compiled by TeaVM,
 * in Node, and requires identical output. A supply transport RAW-A → CNC-01; once the load is on its way an
 * edge ahead of it is blocked for 30 s (which one depends on the seed): the AGV halts, reports route.blocked and
 * replans once the edge is free again (the hall has no bypass that avoids the parked AGVs). Prints the event types,
 * the delivery time and the bit patterns of battery and end position, which must match to the last bit.
 */
public final class FlowPilotSelfTest {
  private FlowPilotSelfTest() {}

  private static final long STEP_MS = 100;
  private static final long LIMIT_MS = 600_000;
  private static final long BLOCK_MS = 30_000;

  public static String run(long seed) {
    var work = new MemoryUnitOfWork();
    var log = new MemoryEventLog(work);
    var time = new SimTime();
    var eventIds = new EventIds(1);
    var bus = new EventBus(log, work, time, () -> eventIds);
    FlowPilot fp = FlowPilot.inMemory(bus, work, time, new MemoryIdSource(work));

    var reserved = (ReservationResult.Success) fp.materials().reserve(
        new ReservationRequest("SELF-RES", "PO-SELF", List.of(new Line("RM-GH200", 5))));
    var line = reserved.reservations().get(0);
    String transportId = fp.transports().requestTransport(new TransportRequest("SELF-TR", TransportKind.SUPPLY,
        "PO-SELF", "RM-GH200", 5, Place.storage(line.locationId()), Place.machineIn("CNC-01"), Priority.NORMAL,
        line.reservationId()));

    String blocked = "-";
    long unblockAtMs = Long.MAX_VALUE;
    String agv = null;
    while (!delivered(log) && time.nowMs() < LIMIT_MS) {
      time.advance(STEP_MS);
      fp.step(STEP_MS);
      if (time.nowMs() == unblockAtMs) fp.setEdgeBlocked(blocked, false);
      if (!blocked.equals("-")) continue;
      for (FlowPilot.VehicleView v : fp.vehicles()) {
        if (!transportId.equals(v.transportId()) || v.status() != AgvStatus.TO_DROP || v.route().size() < 3) continue;
        agv = v.id();
        blocked = blockAhead(fp, v.route(), new SeededRandom(seed).fork("flowpilot-selftest"));
        unblockAtMs = time.nowMs() + BLOCK_MS;
      }
    }

    List<String> types = new ArrayList<>();
    for (NexusEvent e : log.all()) types.add(e.type());
    FlowPilot.VehicleView end = null;
    for (FlowPilot.VehicleView v : fp.vehicles()) if (v.id().equals(agv)) end = v;
    List<String> out = new ArrayList<>();
    out.add("events=" + String.join(",", types));
    out.add("blocked=" + blocked);
    out.add("delivered=" + (delivered(log) ? time.nowMs() : -1) + "ms");
    out.add("battery=" + (end == null ? "-" : bits(end.battery())));
    out.add("position=" + (end == null ? "-" : bits(end.x()) + "," + bits(end.z())));
    return String.join("\n", out);
  }

  /** Blocks one edge of the remaining route, chosen by the seed; returns its id. */
  private static String blockAhead(FlowPilot fp, List<String> route, SeededRandom random) {
    PathGraph graph = fp.graph();
    int i = 2 + random.nextInt(route.size() - 2); // never the edge the vehicle is on right now
    String edge = graph.edge(route.get(i - 1), route.get(i)).id();
    fp.setEdgeBlocked(edge, true);
    return edge;
  }

  private static boolean delivered(MemoryEventLog log) {
    for (NexusEvent e : log.all()) if (e.type().equals(EventTypes.TRANSPORT_DELIVERED)) return true;
    return false;
  }

  private static String bits(double value) {
    return Long.toHexString(Double.doubleToLongBits(value));
  }
}
