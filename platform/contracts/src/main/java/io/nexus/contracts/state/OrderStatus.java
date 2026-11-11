package io.nexus.contracts.state;

import java.util.EnumSet;

public enum OrderStatus {
  CREATED, MATERIAL_PENDING, SHORTAGE, RELEASED, IN_PROGRESS, COMPLETED, CANCELLED;

  public boolean terminal() {
    return this == COMPLETED || this == CANCELLED;
  }

  public static final Transitions<OrderStatus> RULES = Transitions.of(OrderStatus.class)
      .allow(CREATED, MATERIAL_PENDING)
      .allow(MATERIAL_PENDING, RELEASED, SHORTAGE)
      .allow(SHORTAGE, MATERIAL_PENDING)
      .allow(RELEASED, IN_PROGRESS)
      .allow(IN_PROGRESS, COMPLETED)
      .allowFromAll(EnumSet.of(CREATED, MATERIAL_PENDING, SHORTAGE, RELEASED, IN_PROGRESS), CANCELLED)
      .build();
}
