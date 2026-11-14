package io.nexus.plant.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.contracts.port.MachineFeedback;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.sim.SimRules;
import io.nexus.sim.Simulation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalMachinesTest {
  private static final SimRules QUIET = new SimRules(false, false, false, 0.85);
  private final List<String> seen = new ArrayList<>();
  private Runnable onFinished = () -> {};
  private boolean statusFails;
  private LocalMachines machines;

  private final MachineFeedback recorder = new MachineFeedback() {
    @Override public void statusChanged(String m, MachineStatus from, MachineStatus to) {
      seen.add(m + " " + from + ">" + to);
      if (statusFails) throw new IllegalStateException("Empfänger kaputt");
    }

    @Override public void faultRaised(String m, String faultTypeId, String label, boolean scrapped) {
      seen.add(m + " fault " + faultTypeId);
    }

    @Override public void jobFinished(String m, String orderId, int stepSeq, int good, int scrap) {
      seen.add(m + " done " + orderId + "/" + stepSeq + " " + good + "+" + scrap);
      onFinished.run();
    }

    @Override public void conveyorDelivered(String m, String orderId, int stepSeq, int quantity) {
      seen.add(m + " belt " + orderId);
    }
  };

  @BeforeEach
  void newMachines() {
    machines = new LocalMachines(recorder);
    machines.reset(1);
    machines.setRules(QUIET);
  }

  /** One batch of one part, no setup, 100 ms work: done after one step. */
  private static MachineJob job(String orderId) {
    return new MachineJob("CNC-01", orderId, 1, "GH200", 1, 0, 100);
  }

  private Simulation.MachineView view(String id) {
    return machines.views().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
  }

  @Test
  void reportsArriveAfterTheCallInTheOrderTheMachinesMadeThem() {
    onFinished = () -> machines.startJob(job("PO-2"));     // SmartFactory starts the next batch from inside a report
    machines.startJob(job("PO-1"));
    machines.step(100);
    assertThat(seen).containsExactly("CNC-01 IDLE>RUNNING", "CNC-01 RUNNING>IDLE", "CNC-01 done PO-1/1 1+0",
        "CNC-01 IDLE>RUNNING");
    assertThat(view("CNC-01").orderId()).isEqualTo("PO-2");
  }

  @Test
  void aRefusedCommandThrowsAsBeforeAndLeavesNoReportBehind() {
    machines.startJob(job("PO-1"));
    seen.clear();
    assertThatThrownBy(() -> machines.startJob(job("PO-2"))).isInstanceOf(IllegalTransitionException.class)
        .hasMessage("Ungültiger Zustandsübergang für CNC-01: RUNNING → RUNNING");
    machines.step(100);
    assertThat(seen).containsExactly("CNC-01 RUNNING>IDLE", "CNC-01 done PO-1/1 1+0");
  }

  /** Security review: a report the target cannot take must not swallow the reports after it. */
  @Test
  void aFailingReportDoesNotSwallowTheOnesAfterIt() {
    machines.startJob(job("PO-1"));
    seen.clear();
    statusFails = true;
    assertThatThrownBy(() -> machines.step(100)).isInstanceOf(IllegalStateException.class)
        .hasMessage("Empfänger kaputt");
    assertThat(seen).containsExactly("CNC-01 RUNNING>IDLE", "CNC-01 done PO-1/1 1+0");
  }

  @Test
  void resetGivesNewMachinesWithoutReports() {
    machines.injectFault("CNC-01", null);
    assertThat(view("CNC-01").status()).isEqualTo(MachineStatus.FAULT);
    seen.clear();
    machines.reset(1);
    assertThat(machines.views()).allSatisfy(v -> assertThat(v.status()).isEqualTo(MachineStatus.IDLE));
    assertThat(seen).isEmpty();
  }
}
