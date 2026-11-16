package io.nexus.contracts.state;

/** Delivery order of RouteMind (Spec 15a). FAILED is retried from READY_TO_LOAD; RouteMind limits the attempts. */
public enum DeliveryStatus {
  CREATED, AWAITING_GOODS, READY_TO_LOAD, LOADED, IN_TRANSIT, DELIVERED, FAILED, CANCELLED;

  public boolean terminal() {
    return this == DELIVERED || this == CANCELLED;
  }

  public static final Transitions<DeliveryStatus> RULES = Transitions.of(DeliveryStatus.class)
      .allow(CREATED, AWAITING_GOODS, CANCELLED)
      .allow(AWAITING_GOODS, READY_TO_LOAD, CANCELLED)
      .allow(READY_TO_LOAD, LOADED, CANCELLED)
      .allow(LOADED, IN_TRANSIT)
      .allow(IN_TRANSIT, DELIVERED, FAILED)
      .allow(FAILED, READY_TO_LOAD)
      .build();
}
