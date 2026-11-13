package io.nexus.smartfactory.planning;

import io.nexus.contracts.Domain.MachineKind;
import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.ProcessType;
import java.util.List;

/** Everything the scheduler needs, as plain values. */
public record PlanInput(long nowMs, DispatchRule rule, List<OrderToPlan> orders, List<MachineState> machines) {
  public record OrderToPlan(String orderId, String productId, int quantity, Priority priority, long dueAtMs,
                            long createdAtMs, List<OpToPlan> ops) {}

  /** fixedMachineId: running, or material already at the machine. doneAtMs set for finished operations. */
  public record OpToPlan(int seq, ProcessType process, int baseTimeS, String preferredMachineId,
                         String fixedMachineId, Long runningUntilMs, Long doneAtMs) {}

  /** @param maintenance windows as {startMs, endMs} */
  public record MachineState(String id, MachineKind kind, List<ProcessType> capabilities, boolean available,
                             long busyUntilMs, String lastProductId, List<long[]> maintenance) {}
}
