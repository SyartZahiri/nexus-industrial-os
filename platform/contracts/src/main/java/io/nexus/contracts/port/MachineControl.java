package io.nexus.contracts.port;

/** Implemented by the simulation, reached through OPC UA method calls. */
public interface MachineControl {
  void start(String machineId);
  void stop(String machineId);
  /** @param faultTypeId null picks a fault type valid for the machine kind */
  void injectFault(String machineId, String faultTypeId);
  void repair(String machineId);
  void maintain(String machineId);
}
