package io.nexus.contracts.port;

/** Implemented by the simulation (server: through OPC UA; browser: directly). Called by SmartFactory. */
public interface MachineControl {
  void start(String machineId);

  void stop(String machineId);

  /** @param faultTypeId null picks a fault type valid for the machine kind */
  void injectFault(String machineId, String faultTypeId);

  void repair(String machineId);

  void maintain(String machineId);

  /** Hands a batch to a machine whose material is in place. The machine reports back through MachineFeedback. */
  void startJob(MachineJob job);

  /** setupMs is 0 when the machine already ran this product. */
  record MachineJob(String machineId, String orderId, int stepSeq, String productId, int quantity,
                    long setupMs, long processMs) {}
}
