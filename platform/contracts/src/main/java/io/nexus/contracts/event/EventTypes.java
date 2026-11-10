package io.nexus.contracts.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Every event type of the platform and the payload record it carries. */
public final class EventTypes {
  private EventTypes() {}

  public static final String ORDER_CREATED = "order.created";
  public static final String ORDER_MATERIAL_PENDING = "order.material_pending";
  public static final String ORDER_SHORTAGE = "order.shortage";
  public static final String ORDER_RELEASED = "order.released";
  public static final String ORDER_STARTED = "order.started";
  public static final String ORDER_RESCHEDULED = "order.rescheduled";
  public static final String ORDER_COMPLETED = "order.completed";
  public static final String ORDER_CANCELLED = "order.cancelled";
  public static final String OPERATION_STARTED = "operation.started";
  public static final String OPERATION_COMPLETED = "operation.completed";
  public static final String MATERIAL_RESERVED = "material.reserved";
  public static final String MATERIAL_RELEASED = "material.released";
  public static final String MATERIAL_SHORTAGE = "material.shortage";
  public static final String STOCK_CHANGED = "stock.changed";
  public static final String TRANSPORT_CREATED = "transport.created";
  public static final String TRANSPORT_ASSIGNED = "transport.assigned";
  public static final String TRANSPORT_STATE = "transport.state";
  public static final String TRANSPORT_FAILED = "transport.failed";
  public static final String TRANSPORT_REASSIGNED = "transport.reassigned";
  public static final String TRANSPORT_DELIVERED = "transport.delivered";
  public static final String AGV_STATE = "agv.state";
  public static final String AGV_FAULT = "agv.fault";
  public static final String ROUTE_BLOCKED = "route.blocked";
  public static final String ROUTE_REPLANNED = "route.replanned";
  public static final String MACHINE_STATUS = "machine.status";
  public static final String MACHINE_FAULT = "machine.fault";
  public static final String MACHINE_MATERIAL_REQUEST = "machine.material_request";
  public static final String OPCUA_CONNECTION = "opcua.connection";
  public static final String SCENARIO_STARTED = "scenario.started";
  public static final String SCENARIO_FINISHED = "scenario.finished";
  /** A reasoned automatic decision, e.g. AGV selection or alternative machine. */
  public static final String DECISION = "decision";
  /** A consumer failed to process an event repeatedly and was given up on. */
  public static final String SYSTEM_CONSUMER_FAILED = "system.consumer_failed";

  private static final Map<String, Class<? extends Payload>> PAYLOADS = new LinkedHashMap<>();

  static {
    PAYLOADS.put(ORDER_CREATED, Payloads.OrderCreated.class);
    for (String t : new String[] {ORDER_MATERIAL_PENDING, ORDER_SHORTAGE, ORDER_RELEASED, ORDER_STARTED,
        ORDER_COMPLETED, ORDER_CANCELLED}) {
      PAYLOADS.put(t, Payloads.OrderStatusChanged.class);
    }
    PAYLOADS.put(ORDER_RESCHEDULED, Payloads.OrderRescheduled.class);
    PAYLOADS.put(OPERATION_STARTED, Payloads.OperationChanged.class);
    PAYLOADS.put(OPERATION_COMPLETED, Payloads.OperationChanged.class);
    PAYLOADS.put(MATERIAL_RESERVED, Payloads.MaterialReserved.class);
    PAYLOADS.put(MATERIAL_RELEASED, Payloads.MaterialReserved.class);
    PAYLOADS.put(MATERIAL_SHORTAGE, Payloads.MaterialShortage.class);
    PAYLOADS.put(STOCK_CHANGED, Payloads.StockChanged.class);
    PAYLOADS.put(TRANSPORT_CREATED, Payloads.TransportCreated.class);
    for (String t : new String[] {TRANSPORT_ASSIGNED, TRANSPORT_STATE, TRANSPORT_FAILED, TRANSPORT_REASSIGNED}) {
      PAYLOADS.put(t, Payloads.TransportStateChanged.class);
    }
    PAYLOADS.put(TRANSPORT_DELIVERED, Payloads.TransportDelivered.class);
    PAYLOADS.put(AGV_STATE, Payloads.AgvStateChanged.class);
    PAYLOADS.put(AGV_FAULT, Payloads.AgvFault.class);
    PAYLOADS.put(ROUTE_BLOCKED, Payloads.RouteChanged.class);
    PAYLOADS.put(ROUTE_REPLANNED, Payloads.RouteChanged.class);
    PAYLOADS.put(MACHINE_STATUS, Payloads.MachineStatusChanged.class);
    PAYLOADS.put(MACHINE_FAULT, Payloads.MachineFault.class);
    PAYLOADS.put(MACHINE_MATERIAL_REQUEST, Payloads.MaterialRequested.class);
    PAYLOADS.put(OPCUA_CONNECTION, Payloads.Note.class);
    PAYLOADS.put(SCENARIO_STARTED, Payloads.Note.class);
    PAYLOADS.put(SCENARIO_FINISHED, Payloads.Note.class);
    PAYLOADS.put(DECISION, Payloads.Decision.class);
    PAYLOADS.put(SYSTEM_CONSUMER_FAILED, Payloads.ConsumerFailed.class);
  }

  public static Set<String> all() {
    return Set.copyOf(PAYLOADS.keySet());
  }

  public static Class<? extends Payload> payloadType(String type) {
    Class<? extends Payload> c = PAYLOADS.get(type);
    if (c == null) throw new IllegalArgumentException("Unbekannter Ereignistyp: " + type);
    return c;
  }
}
