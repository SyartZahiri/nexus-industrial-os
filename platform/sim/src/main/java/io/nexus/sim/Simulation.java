package io.nexus.sim;

import io.nexus.contracts.Domain.MachineKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.port.MachineControl;
import io.nexus.contracts.port.MachineFeedback;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.state.MachineStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * All simulated machines of the plant. Implements MachineControl and reports through MachineFeedback.
 * Single-threaded: the caller (the plant) serialises every call. Feedback calls happen inside step and inside
 * commands, and the feedback may call back into this class (SmartFactory starts the next job).
 */
public final class Simulation implements MachineControl {
  public record MachineSpec(String id, MachineKind kind) {}

  /** A conveyor that carries batches from an assembly cell (dropped at dropAtM) to the machine it feeds (at takeAtM). */
  public record ConveyorLink(String fromMachineId, String conveyorId, String toMachineId, double dropAtM,
                             double takeAtM, double speedMps) {}

  public record ConveyorItem(String orderId, int stepSeq, double positionM) {}

  public record ConveyorView(String conveyorId, List<ConveyorItem> items) {}

  public record MachineView(String id, MachineStatus status, String orderId, int stepSeq, double progress,
                            double temperature, double vibration, double power, double spindle, double wear,
                            String faultLabel) {}

  private final List<MachineSim> machines = new ArrayList<>();
  private final MachineFeedback feedback;
  private final SeededRandom random;
  private final List<ConveyorLink> links;
  private final List<ConveyorSim> conveyors = new ArrayList<>();
  private final QualityModel quality = new QualityModel();
  private SimRules rules = SimRules.DEFAULT;

  public Simulation(List<MachineSpec> specs, List<ConveyorLink> conveyors, SeededRandom random,
                    MachineFeedback feedback) {
    this.feedback = feedback;
    this.random = random;
    this.links = List.copyOf(conveyors);
    for (ConveyorLink l : this.links) {
      boolean known = false;
      for (ConveyorSim c : this.conveyors) known |= c.id.equals(l.conveyorId());
      if (!known) this.conveyors.add(new ConveyorSim(l.conveyorId()));
    }
    this.conveyors.sort(Comparator.comparing((ConveyorSim c) -> c.id));
    List<MachineSpec> sorted = new ArrayList<>(specs);
    sorted.sort(Comparator.comparing(MachineSpec::id));
    for (MachineSpec s : sorted) machines.add(new MachineSim(s.id(), s.kind(), random, feedback));
  }

  public void setRules(SimRules rules) {
    this.rules = rules;
  }

  public SimRules rules() {
    return rules;
  }

  /**
   * Advances conveyors, then every machine by dtMs in id order. Conveyors go first, so a batch put on the belt
   * in this step starts moving in the next one.
   */
  public void step(long dtMs) {
    for (ConveyorSim c : conveyors) {
      for (ConveyorSim.Item item : c.step(dtMs)) {
        feedback.conveyorDelivered(item.link.toMachineId(), item.orderId, item.stepSeq, item.quantity);
      }
    }
    for (int i = 0; i < machines.size(); i++) {
      MachineSim m = machines.get(i);
      MachineJob finished = m.step(dtMs, rules);
      if (finished != null) finish(m, finished);
    }
  }

  private void finish(MachineSim m, MachineJob job) {
    int scrap = 0;
    if (m.kind == MachineKind.QC) scrap = quality.inspect(job.orderId(), job.quantity(), random);
    else quality.recordStep(job.orderId(), m);
    feedback.jobFinished(m.id, job.orderId(), job.stepSeq(), job.quantity() - scrap, scrap);
    for (ConveyorLink l : links) {
      if (l.fromMachineId().equals(m.id)) {
        for (ConveyorSim c : conveyors) {
          if (c.id.equals(l.conveyorId())) {
            c.items.add(new ConveyorSim.Item(l, job.orderId(), job.stepSeq() + 1, job.quantity() - scrap));
          }
        }
      }
    }
  }

  public List<ConveyorView> conveyors() {
    List<ConveyorView> views = new ArrayList<>();
    for (ConveyorSim c : conveyors) {
      List<ConveyorItem> items = new ArrayList<>();
      for (ConveyorSim.Item i : c.items) items.add(new ConveyorItem(i.orderId, i.stepSeq, i.positionM()));
      views.add(new ConveyorView(c.id, items));
    }
    return views;
  }

  public List<MachineView> machines() {
    List<MachineView> views = new ArrayList<>();
    for (MachineSim m : machines) {
      views.add(new MachineView(m.id, m.status, m.job == null ? null : m.job.orderId(),
          m.job == null ? 0 : m.job.stepSeq(), m.progress(), m.temperature, m.vibration, m.power, m.spindle, m.wear,
          m.fault == null ? null : m.fault.label()));
    }
    return views;
  }

  public void configure(String machineId, int overridePercent, boolean coolant) {
    machine(machineId).configure(overridePercent, coolant);
  }

  @Override public void start(String machineId) { machine(machineId).start(); }

  @Override public void stop(String machineId) { machine(machineId).stop(); }

  @Override public void injectFault(String machineId, String faultTypeId) { machine(machineId).injectFault(faultTypeId); }

  @Override public void repair(String machineId) { machine(machineId).repair(); }

  @Override public void maintain(String machineId) { machine(machineId).maintain(); }

  @Override public void startJob(MachineJob job) { machine(job.machineId()).startJob(job); }

  private MachineSim machine(String id) {
    for (MachineSim m : machines) if (m.id.equals(id)) return m;
    throw DomainException.notFound("Maschine", id);
  }
}
