package io.nexus.contracts.state;

/** Delivery vehicle (Spec 15a). A vehicle being loaded cannot break down: its goods would have no state to go to. */
public enum VehicleStatus {
  AVAILABLE, LOADING, EN_ROUTE, RETURNING, MAINTENANCE, BROKEN_DOWN;

  public static final Transitions<VehicleStatus> RULES = Transitions.of(VehicleStatus.class)
      .allow(AVAILABLE, LOADING, MAINTENANCE, BROKEN_DOWN)
      .allow(LOADING, EN_ROUTE, AVAILABLE)
      .allow(EN_ROUTE, RETURNING, BROKEN_DOWN)
      .allow(RETURNING, AVAILABLE, BROKEN_DOWN)
      .allow(MAINTENANCE, AVAILABLE)
      .allow(BROKEN_DOWN, AVAILABLE)
      .build();
}
