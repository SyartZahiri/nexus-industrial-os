package io.nexus.smartfactory.planning;

import java.util.List;
import java.util.Map;

/**
 * Result of a planning run.
 *
 * @param utilization         booked time / planning horizon per machine, ordered by machine id
 * @param bottleneckMachineId highest utilization, ties by id; null if nothing is planned
 */
public record Plan(List<PlannedOp> ops, List<Unplannable> unplannable, List<String> lateOrders,
                   Map<String, Double> utilization, String bottleneckMachineId) {
  public record PlannedOp(String orderId, int seq, String machineId, long startMs, long endMs, long setupMs) {}

  public record Unplannable(String orderId, int seq, String reason) {}

  public PlannedOp op(String orderId, int seq) {
    for (PlannedOp p : ops) if (p.orderId().equals(orderId) && p.seq() == seq) return p;
    return null;
  }

  public Unplannable unplannable(String orderId, int seq) {
    for (Unplannable u : unplannable) if (u.orderId().equals(orderId) && u.seq() == seq) return u;
    return null;
  }
}
