package io.nexus.contracts.event;

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
}
