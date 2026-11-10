package io.nexus.contracts.state;

import java.util.EnumSet;
import java.util.Set;

public enum TransportStatus {
  CREATED, ASSIGNED, TO_PICKUP, LOADING, IN_TRANSIT, UNLOADING, DELIVERED, FAILED, CANCELLED;

  /** States in which the order occupies an AGV. Mirrors the partial unique index in the database. */
  public static final Set<TransportStatus> ACTIVE =
      Set.copyOf(EnumSet.of(ASSIGNED, TO_PICKUP, LOADING, IN_TRANSIT, UNLOADING));

  public boolean terminal() {
    return this == DELIVERED || this == CANCELLED;
  }

  public static final Transitions<TransportStatus> RULES = Transitions.of(TransportStatus.class)
      .allow(CREATED, ASSIGNED, CANCELLED)
      .allow(ASSIGNED, TO_PICKUP, CREATED, CANCELLED)
      .allow(TO_PICKUP, LOADING, CREATED, CANCELLED)
      .allow(LOADING, IN_TRANSIT, FAILED)
      .allow(IN_TRANSIT, UNLOADING, FAILED)
      .allow(UNLOADING, DELIVERED, FAILED)
      .allow(FAILED, ASSIGNED)
      .build();
}
