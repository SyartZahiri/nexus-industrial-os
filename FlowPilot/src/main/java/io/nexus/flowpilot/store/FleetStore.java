package io.nexus.flowpilot.store;

import java.util.List;
import java.util.Set;

public interface FleetStore {
  /** Ordered by id. */
  List<AgvRecord> agvs();

  /** Optimistic like TransportStore.update: CONFLICT on a stale version, returns version + 1. */
  AgvRecord save(AgvRecord agv);

  /** Sorted edge ids. */
  Set<String> blockedEdges();

  /** Unknown edge: DomainException NOT_FOUND. */
  void setEdgeBlocked(String edgeId, boolean blocked);
}
