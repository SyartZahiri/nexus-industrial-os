package io.nexus.smartfactory.store;

import io.nexus.contracts.Domain.ProcessType;

/** One routing step of a production order; the whole batch runs on one machine. */
public record Operation(String id, String orderId, int seq, ProcessType process, String machineId, Status status,
                        Long plannedStartMs, Long plannedEndMs, Long startedAtMs, Long endedAtMs, int good, int scrap) {
  public enum Status { PLANNED, WAITING_MATERIAL, READY, RUNNING, DONE, CANCELLED }

  public boolean finished() {
    return status == Status.DONE || status == Status.CANCELLED;
  }

  public Operation with(Status newStatus) {
    return new Operation(id, orderId, seq, process, machineId, newStatus, plannedStartMs, plannedEndMs, startedAtMs,
        endedAtMs, good, scrap);
  }
}
