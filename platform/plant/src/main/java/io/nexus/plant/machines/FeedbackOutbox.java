package io.nexus.plant.machines;

import io.nexus.contracts.port.MachineFeedback;
import io.nexus.contracts.state.MachineStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects what the machines report instead of passing it on at once. The gateway hands the reports on after the
 * call that caused them (in memory) or sends them back as the call's answer (OPC UA), so both runtimes deliver the
 * same reports in the same order at the same moment.
 */
public final class FeedbackOutbox implements MachineFeedback {
  public sealed interface Item permits StatusChanged, FaultRaised, JobFinished, ConveyorDelivered {
    String machineId();

    void deliverTo(MachineFeedback target);
  }

  public record StatusChanged(String machineId, MachineStatus from, MachineStatus to) implements Item {
    @Override
    public void deliverTo(MachineFeedback target) {
      target.statusChanged(machineId, from, to);
    }
  }

  public record FaultRaised(String machineId, String faultTypeId, String label, boolean scrappedWorkpiece)
      implements Item {
    @Override
    public void deliverTo(MachineFeedback target) {
      target.faultRaised(machineId, faultTypeId, label, scrappedWorkpiece);
    }
  }

  public record JobFinished(String machineId, String orderId, int stepSeq, int good, int scrap) implements Item {
    @Override
    public void deliverTo(MachineFeedback target) {
      target.jobFinished(machineId, orderId, stepSeq, good, scrap);
    }
  }

  public record ConveyorDelivered(String machineId, String orderId, int stepSeq, int quantity) implements Item {
    @Override
    public void deliverTo(MachineFeedback target) {
      target.conveyorDelivered(machineId, orderId, stepSeq, quantity);
    }
  }

  private final List<Item> items = new ArrayList<>();

  @Override
  public void statusChanged(String machineId, MachineStatus from, MachineStatus to) {
    items.add(new StatusChanged(machineId, from, to));
  }

  @Override
  public void faultRaised(String machineId, String faultTypeId, String label, boolean scrappedWorkpiece) {
    items.add(new FaultRaised(machineId, faultTypeId, label, scrappedWorkpiece));
  }

  @Override
  public void jobFinished(String machineId, String orderId, int stepSeq, int good, int scrap) {
    items.add(new JobFinished(machineId, orderId, stepSeq, good, scrap));
  }

  @Override
  public void conveyorDelivered(String machineId, String orderId, int stepSeq, int quantity) {
    items.add(new ConveyorDelivered(machineId, orderId, stepSeq, quantity));
  }

  /** Everything reported since the last drain, oldest first; the outbox is empty afterwards. */
  public List<Item> drain() {
    List<Item> out = new ArrayList<>(items);
    items.clear();
    return out;
  }
}
