package io.nexus.flowpilot.store;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.state.TransportStatus;

/** A transport of one load carrier; from and to are business places, the nodes are what FlowPilot resolved. */
public record TransportOrder(String id, String requestId, TransportKind kind, String orderId, String materialId,
                             int quantity, Place from, String fromNode, Place to, String toNode, Priority priority,
                             TransportStatus status, String agvId, String reservationId, long createdAtMs,
                             int version) {
  public TransportOrder withStatus(TransportStatus next, String agv) {
    return new TransportOrder(id, requestId, kind, orderId, materialId, quantity, from, fromNode, to, toNode,
        priority, next, agv, reservationId, createdAtMs, version);
  }

  /** The load is picked up somewhere else, e.g. where a failed AGV stopped. */
  public TransportOrder withPickup(String node) {
    return new TransportOrder(id, requestId, kind, orderId, materialId, quantity, from, node, to, toNode,
        priority, status, agvId, reservationId, createdAtMs, version);
  }

  public TransportOrder withTarget(Place place, String node) {
    return new TransportOrder(id, requestId, kind, orderId, materialId, quantity, from, fromNode, place, node,
        priority, status, agvId, reservationId, createdAtMs, version);
  }
}
