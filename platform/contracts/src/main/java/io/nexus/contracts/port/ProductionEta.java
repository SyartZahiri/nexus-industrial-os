package io.nexus.contracts.port;

/** Implemented by SmartFactory: when the finished goods of an order are expected (Spec 15a, Integration 1). */
public interface ProductionEta {
  /** Planned end of the order's last operation in simulation ms; -1 if the order has no planned operation. */
  long plannedFinishMs(String orderId);
}
