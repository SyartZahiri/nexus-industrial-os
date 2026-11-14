package io.nexus.plant.machines;

import io.nexus.contracts.port.MachineFeedback;
import io.nexus.sim.SimRules;
import io.nexus.sim.Simulation;
import java.util.List;

/**
 * The machines in memory: the browser runtime and the core tests. Each call runs on the simulation, then the
 * reports it caused are handed to the target in order. A report may lead to a new command (SmartFactory starts the
 * next batch); that command's reports are handed on before the rest of the outer ones, as OPC UA does it too.
 */
public final class LocalMachines implements MachineGateway {
  private final MachineFeedback target;
  private final FeedbackOutbox outbox = new FeedbackOutbox();
  private Simulation simulation;

  public LocalMachines(MachineFeedback target) {
    this.target = target;
  }

  /**
   * Every report is handed on, even when the command or an earlier report failed: a lost report would leave
   * SmartFactory and the machines out of step for good. The command's own error wins; others are attached to it.
   */
  private void call(Runnable command) {
    RuntimeException failure = null;
    try {
      command.run();
    } catch (RuntimeException refused) {
      failure = refused;
    }
    for (FeedbackOutbox.Item item : outbox.drain()) {
      try {
        item.deliverTo(target);
      } catch (RuntimeException notTaken) {
        if (failure == null) failure = notTaken;
        else failure.addSuppressed(notTaken);
      }
    }
    if (failure != null) throw failure;
  }

  @Override public void reset(long seed) { simulation = PlantMachines.newSimulation(seed, outbox); }

  @Override public void step(long dtMs) { call(() -> simulation.step(dtMs)); }

  @Override public void setRules(SimRules rules) { simulation.setRules(rules); }

  @Override public void configure(String machineId, int overridePercent, boolean coolant) {
    call(() -> simulation.configure(machineId, overridePercent, coolant));
  }

  @Override public List<Simulation.MachineView> views() { return simulation.machines(); }

  @Override public List<Simulation.ConveyorView> conveyors() { return simulation.conveyors(); }

  @Override public void start(String machineId) { call(() -> simulation.start(machineId)); }

  @Override public void stop(String machineId) { call(() -> simulation.stop(machineId)); }

  @Override public void injectFault(String machineId, String faultTypeId) {
    call(() -> simulation.injectFault(machineId, faultTypeId));
  }

  @Override public void repair(String machineId) { call(() -> simulation.repair(machineId)); }

  @Override public void maintain(String machineId) { call(() -> simulation.maintain(machineId)); }

  @Override public void startJob(MachineJob job) { call(() -> simulation.startJob(job)); }
}
