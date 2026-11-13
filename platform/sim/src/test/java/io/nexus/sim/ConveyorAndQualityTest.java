package io.nexus.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.MachineKind;
import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.state.MachineStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConveyorAndQualityTest {
  static final SimRules QUIET = new SimRules(false, true, false, 0.85);
  /** Collecting conveyor CV-03 from FactoryTwin's initialState.ts: drop points 6.7 m and 15.7 m, QC takes at 27 m. */
  static final List<Simulation.ConveyorLink> LINKS = List.of(
      new Simulation.ConveyorLink("ROB-01", "CV-03", "QC-01", 6.7, 27, 0.6),
      new Simulation.ConveyorLink("ROB-02", "CV-03", "QC-01", 15.7, 27, 0.6));

  final SimulationTest.Recorder feedback = new SimulationTest.Recorder();

  Simulation sim(long seed) {
    var s = new Simulation(List.of(new Simulation.MachineSpec("CNC-01", MachineKind.CNC),
        new Simulation.MachineSpec("ROB-01", MachineKind.ROBOT), new Simulation.MachineSpec("ROB-02", MachineKind.ROBOT),
        new Simulation.MachineSpec("QC-01", MachineKind.QC)), LINKS, new SeededRandom(seed), feedback);
    s.setRules(QUIET);
    return s;
  }

  void runUntil(Simulation s, String fragment, long maxMs) {
    for (long t = 0; t < maxMs && !feedback.has(fragment); t += 100) {
      feedback.now += 100;
      s.step(100);
    }
    assertThat(feedback.has(fragment)).as("reached " + fragment).isTrue();
  }

  @Test
  void assemblyBatchArrivesAtQcAfterTheConveyorTime() {
    var s = sim(1);
    s.startJob(new MachineJob("ROB-01", "PO-0001", 2, "GH200", 5, 0, 10_000));
    runUntil(s, "conveyor QC-01", 120_000);
    // 20.3 m at 0.6 m/s = 33.834 s, rounded up to the next 100 ms step
    assertThat(feedback.timeOf("conveyor QC-01 PO-0001 3 5")).isEqualTo(10_000 + 33_900);
    assertThat(feedback.calls).containsSubsequence("10000 finished ROB-01 PO-0001 2 5 0", "43900 conveyor QC-01 PO-0001 3 5");
  }

  @Test
  void batchOnTheBeltIsVisibleWithItsPosition() {
    var s = sim(1);
    s.startJob(new MachineJob("ROB-02", "PO-0002", 2, "MT7", 3, 0, 1_000));
    runUntil(s, "finished ROB-02", 10_000);
    for (int i = 0; i < 50; i++) s.step(100);            // 5 s on the belt
    assertThat(s.conveyors()).singleElement().satisfies(c -> {
      assertThat(c.conveyorId()).isEqualTo("CV-03");
      assertThat(c.items()).singleElement().satisfies(item -> {
        assertThat(item.orderId()).isEqualTo("PO-0002");
        assertThat(item.positionM()).isCloseTo(15.7 + 3.0, org.assertj.core.data.Offset.offset(1e-9));
      });
    });
  }

  @Test
  void twoBatchesFromTheSameCellKeepTheirOrder() {
    var s = sim(1);
    s.startJob(new MachineJob("ROB-01", "PO-0001", 2, "GH200", 5, 0, 1_000));
    runUntil(s, "finished ROB-01 PO-0001", 5_000);
    s.startJob(new MachineJob("ROB-01", "PO-0003", 2, "GH200", 5, 0, 1_000));
    runUntil(s, "conveyor QC-01 PO-0003", 120_000);
    assertThat(feedback.calls.stream().filter(c -> c.contains(" conveyor ")).map(c -> c.substring(c.indexOf("conveyor"))))
        .containsExactly("conveyor QC-01 PO-0001 3 5", "conveyor QC-01 PO-0003 3 5");
  }

  private int[] qcResults(boolean harsh) {
    feedback.calls.clear();
    feedback.now = 0;
    var s = sim(42);
    if (harsh) s.configure("CNC-01", 130, false);
    int good = 0;
    int scrap = 0;
    for (int i = 1; i <= 200; i++) {
      String order = "PO-" + i;
      s.startJob(new MachineJob("CNC-01", order, 1, "FP12", 10, 0, 60_000));
      while (!feedback.has("finished CNC-01 " + order + " ")) {
        feedback.now += 100;
        s.step(100);
      }
      s.startJob(new MachineJob("QC-01", order, 2, "FP12", 10, 0, 10_000));
      while (!feedback.has("finished QC-01 " + order + " ")) {
        feedback.now += 100;
        s.step(100);
      }
      String line = feedback.calls.stream().filter(c -> c.contains("finished QC-01 " + order + " ")).findFirst().orElseThrow();
      String[] parts = line.split(" ");
      int g = Integer.parseInt(parts[parts.length - 2]);
      int sc = Integer.parseInt(parts[parts.length - 1]);
      assertThat(g + sc).as("good + scrap is the batch").isEqualTo(10);
      good += g;
      scrap += sc;
      // keep CNC-01 usable: after a thermal trip wait until it runs again
      while (s.machines().get(0).status() != MachineStatus.IDLE) {
        feedback.now += 100;
        s.step(100);
      }
    }
    return new int[] {good, scrap};
  }

  @Test
  void qcScrapGrowsWithWearAndHeatOfEarlierSteps() {
    int[] gentle = qcResults(false);
    int[] harsh = qcResults(true);
    assertThat(harsh[1]).isGreaterThan(gentle[1]);
    assertThat(gentle[0] + gentle[1]).isEqualTo(2000);
  }
}
