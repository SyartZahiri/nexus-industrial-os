package io.nexus.contracts.state;

import java.util.EnumSet;

public enum AgvStatus {
  IDLE, TO_PICKUP, LOADING, TO_DROP, UNLOADING, TO_CHARGE, CHARGING, FAULT, OFFLINE;

  public static final Transitions<AgvStatus> RULES = Transitions.of(AgvStatus.class)
      .allow(IDLE, TO_PICKUP, TO_CHARGE, OFFLINE)
      .allow(TO_PICKUP, LOADING, IDLE)
      .allow(LOADING, TO_DROP, IDLE)
      .allow(TO_DROP, UNLOADING)
      .allow(UNLOADING, IDLE, TO_DROP)
      .allow(TO_CHARGE, CHARGING, OFFLINE)
      .allow(CHARGING, IDLE, OFFLINE)
      .allow(OFFLINE, CHARGING, IDLE)
      .allowFromAll(EnumSet.of(IDLE, TO_PICKUP, LOADING, TO_DROP, UNLOADING, TO_CHARGE, CHARGING), FAULT)
      .allow(FAULT, IDLE)
      .build();
}
