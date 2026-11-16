package io.nexus.contracts.event;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.state.AgvStatus;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.contracts.state.TourStatus;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.contracts.state.VehicleStatus;
import java.util.List;

/** Payload of every event type. Which record belongs to which type is defined in {@link EventTypes}. */
public final class Payloads {
  private Payloads() {}

  public record Note(String message) implements Payload {}

  /** A reasoned automatic decision, e.g. which AGV got a transport or which machine replaces a failed one. */
  public record Decision(String subject, String chosen, List<String> candidates, String reason) implements Payload {}

  public record ConsumerFailed(String consumer, String eventId, String eventType, String error) implements Payload {}

  public record OrderCreated(String orderId, String productId, int quantity, Priority priority, long dueAtMs)
      implements Payload {}

  public record OrderStatusChanged(String orderId, OrderStatus from, OrderStatus to, String reason) implements Payload {}

  public record OrderRescheduled(String orderId, int stepSeq, String fromMachineId, String toMachineId, String reason)
      implements Payload {}

  public record OperationChanged(String orderId, int stepSeq, String machineId) implements Payload {}

  public record MaterialReserved(String orderId, String requestId, List<String> reservationIds) implements Payload {}

  public record MaterialShortage(String orderId, String materialId, int requested, int available) implements Payload {}

  public record StockChanged(String materialId, String locationId, int onHand, int reserved) implements Payload {}

  public record TransportCreated(String transportId, TransportKind kind, String orderId, String materialId,
                                 int quantity, Place from, Place to) implements Payload {}

  public record TransportStateChanged(String transportId, TransportStatus from, TransportStatus to, String agvId)
      implements Payload {}

  public record TransportDelivered(String transportId, TransportKind kind, String orderId, String materialId,
                                   int quantity, Place to) implements Payload {}

  public record AgvStateChanged(String agvId, AgvStatus from, AgvStatus to) implements Payload {}

  public record AgvFault(String agvId, String faultTypeId, String label) implements Payload {}

  public record RouteChanged(String agvId, String edgeId, List<String> route) implements Payload {}

  public record MachineStatusChanged(String machineId, MachineStatus from, MachineStatus to) implements Payload {}

  public record MachineFault(String machineId, String faultTypeId, String label, boolean scrappedWorkpiece)
      implements Payload {}

  public record MaterialRequested(String machineId, String orderId, String materialId, int quantity)
      implements Payload {}

  public record DeliveryCreated(String deliveryId, String orderId, String customerId, int quantity, int weightKg,
                                int pallets, long earliestMs, long latestMs) implements Payload {}

  public record DeliveryStateChanged(String deliveryId, DeliveryStatus from, DeliveryStatus to, String tourId,
                                     String reason) implements Payload {}

  public record TourPlanned(String tourId, String vehicleId, String planId, long departAtMs, List<String> deliveryIds,
                            double km, double cost) implements Payload {}

  public record TourStateChanged(String tourId, TourStatus from, TourStatus to, String reason) implements Payload {}

  public record VehicleStateChanged(String vehicleId, VehicleStatus from, VehicleStatus to) implements Payload {}

  /** Key figures of one plan: distance, driving time, cost (CHF), lateness against the customers' windows. */
  public record PlanFigures(double km, double driveMinutes, double cost, double lateMinutes, int tours) {}

  /** One planning run: the simple start plan (nearest neighbour) against the optimised one, and what stayed open. */
  public record DeliveryPlanComputed(String planId, String solver, long waveAtMs, String trigger, PlanFigures baseline,
                                     PlanFigures optimized, List<String> unassigned) implements Payload {}
}
