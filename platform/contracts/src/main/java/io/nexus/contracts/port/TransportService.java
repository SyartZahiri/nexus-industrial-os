package io.nexus.contracts.port;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;

/** Implemented by FlowPilot. */
public interface TransportService {
  /** Idempotent on requestId. Returns the transport order id. */
  String requestTransport(TransportRequest request);

  /** Allowed only while the load has not been picked up; otherwise IllegalTransitionException. */
  void cancel(String transportId);

  /** Changes the destination of a transport that has not been delivered yet (machine failure rerouting). */
  void redirect(String transportId, String newToNode);

  /**
   * @param reservationId reservation consumed on pickup; null for WIP and PUTAWAY
   * @param materialId    null for WIP moves of a workpiece
   */
  record TransportRequest(String requestId, TransportKind kind, String orderId, String materialId, int quantity,
                          String fromNode, String toNode, Priority priority, String reservationId) {}
}
