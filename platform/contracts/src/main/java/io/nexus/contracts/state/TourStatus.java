package io.nexus.contracts.state;

/**
 * Tour of one delivery vehicle (Spec 15a). LOADING → REPLANNED: none of the tour's goods arrived before departure,
 * so the tour is dropped and its deliveries go into the next wave.
 */
public enum TourStatus {
  PLANNED, LOADING, EN_ROUTE, COMPLETED, REPLANNED, INTERRUPTED;

  public boolean terminal() {
    return this == COMPLETED || this == REPLANNED || this == INTERRUPTED;
  }

  public static final Transitions<TourStatus> RULES = Transitions.of(TourStatus.class)
      .allow(PLANNED, LOADING, REPLANNED)
      .allow(LOADING, EN_ROUTE, REPLANNED)
      .allow(EN_ROUTE, COMPLETED, INTERRUPTED)
      .build();
}
