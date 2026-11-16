package io.nexus.plant;

import io.nexus.flowpilot.FlowPilot;
import io.nexus.routemind.RouteMind;
import io.nexus.sim.Simulation;
import java.util.List;

/** Everything the user interface shows, at one point in simulation time. Every list has a fixed order. */
public record PlantFrame(long simTimeMs, List<Simulation.MachineView> machines, List<Simulation.ConveyorView> conveyors,
                         List<FlowPilot.VehicleView> agvs, List<StockView> stock, List<OrderView> orders,
                         List<TransportView> transports, List<RouteMind.TruckView> trucks, List<DockView> dock) {
  /** Goods of a delivery waiting at the loading zone or loaded on a vehicle that has not left. */
  public record DockView(String deliveryId, String status, int pallets) {}

  public record StockView(String materialId, String locationId, int onHand, int reserved) {}

  public record OrderView(String id, String productId, int quantity, String priority, String status, long dueAtMs,
                          int good, int scrapped, int stepsDone, int steps) {}

  /** from and to as "KIND:id", e.g. "STORAGE:RAW-A". */
  public record TransportView(String id, String kind, String orderId, String materialId, int quantity, String status,
                              String agvId, String from, String to) {}
}
