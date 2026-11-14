package io.nexus.plant.machines;

import io.nexus.contracts.port.MachineControl;
import io.nexus.sim.SimRules;
import io.nexus.sim.Simulation;
import java.util.List;

/**
 * The plant's way to its machines (Spec 2a, port MachineGateway): in memory a direct call, on the server OPC UA.
 * Either way every report a call causes has reached the feedback target (SmartFactory) before the call returns, in
 * the order the machines made them, and an exception the machines threw is rethrown after those reports. All calls
 * come from the plant, one at a time, under the platform lock.
 */
public interface MachineGateway extends MachineControl {
  /** New machines for this seed: idle, cold, unworn, random stream new SeededRandom(seed).fork("sim"). */
  void reset(long seed);

  void step(long dtMs);

  void setRules(SimRules rules);

  void configure(String machineId, int overridePercent, boolean coolant);

  /** The machines in id order. */
  List<Simulation.MachineView> views();

  List<Simulation.ConveyorView> conveyors();
}
