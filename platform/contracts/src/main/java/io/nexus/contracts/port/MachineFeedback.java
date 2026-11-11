package io.nexus.contracts.port;

import io.nexus.contracts.state.MachineStatus;

/** Implemented by SmartFactory. Called by the simulation when something happens at a machine. */
public interface MachineFeedback {
  void statusChanged(String machineId, MachineStatus from, MachineStatus to);

  /**
   * Called right before statusChanged(…, FAULT). If scrappedWorkpiece, the running batch is lost and no
   * jobFinished follows for it.
   */
  void faultRaised(String machineId, String faultTypeId, String label, boolean scrappedWorkpiece);

  /** good + scrap == quantity of the job. */
  void jobFinished(String machineId, String orderId, int stepSeq, int good, int scrap);

  /** Parts arrived at a machine that is fed by a conveyor (assembly cells hand over to QC on CV-03). */
  void conveyorDelivered(String machineId, String orderId, int stepSeq, int quantity);
}
