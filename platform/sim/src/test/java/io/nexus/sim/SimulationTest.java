package io.nexus.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.MachineKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.contracts.port.MachineFeedback;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.MachineStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulationTest {
  static final SimRules QUIET = new SimRules(false, true, false, 0.85);

  /** Records every feedback call as text, with the simulation time it happened at. */
  static final class Recorder implements MachineFeedback {
    final List<String> calls = new ArrayList<>();
    long now;

    @Override public void statusChanged(String machineId, MachineStatus from, MachineStatus to) {
      calls.add(now + " status " + machineId + " " + from + ">" + to);
    }
    @Override public void faultRaised(String machineId, String faultTypeId, String label, boolean scrapped) {
      calls.add(now + " fault " + machineId + " " + faultTypeId + " " + scrapped);
    }
    @Override public void jobFinished(String machineId, String orderId, int stepSeq, int good, int scrap) {
      calls.add(now + " finished " + machineId + " " + orderId + " " + stepSeq + " " + good + " " + scrap);
    }
    @Override public void conveyorDelivered(String machineId, String orderId, int stepSeq, int quantity) {
      calls.add(now + " conveyor " + machineId + " " + orderId + " " + stepSeq + " " + quantity);
    }

    boolean has(String fragment) {
      return calls.stream().anyMatch(c -> c.contains(fragment));
    }

    long timeOf(String fragment) {
      return calls.stream().filter(c -> c.contains(fragment)).map(c -> Long.parseLong(c.split(" ")[0])).findFirst()
          .orElseThrow(() -> new AssertionError("no call with " + fragment + " in " + calls));
    }
  }

  final Recorder feedback = new Recorder();

  Simulation sim(long seed, SimRules rules) {
    var s = new Simulation(List.of(new Simulation.MachineSpec("CNC-01", MachineKind.CNC),
        new Simulation.MachineSpec("ROB-01", MachineKind.ROBOT), new Simulation.MachineSpec("QC-01", MachineKind.QC)),
        List.of(), new SeededRandom(seed), feedback);
    s.setRules(rules);
    return s;
  }

  void run(Simulation s, long ms) {
    for (long t = 0; t < ms; t += 100) {
      feedback.now += 100;
      s.step(100);
    }
  }

  void runUntil(Simulation s, String fragment, long maxMs) {
    for (long t = 0; t < maxMs && !feedback.has(fragment); t += 100) {
      feedback.now += 100;
      s.step(100);
    }
    assertThat(feedback.has(fragment)).as("reached " + fragment).isTrue();
  }

  Simulation.MachineView view(Simulation s, String id) {
    return s.machines().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
  }

  static MachineJob job(String machine, long setupMs, long processMs) {
    return new MachineJob(machine, "PO-0001", 1, "GH200", 5, setupMs, processMs);
  }

  @Test
  void jobRunsSetupThenProcessingAndReportsFinished() {
    var s = sim(1, QUIET);
    s.startJob(job("CNC-01", 30_000, 425_000));
    runUntil(s, "finished CNC-01", 600_000);
    assertThat(feedback.timeOf("finished CNC-01")).isEqualTo(455_000);
    assertThat(feedback.calls).containsSubsequence(
        "0 status CNC-01 IDLE>SETUP", "30000 status CNC-01 SETUP>RUNNING", "455000 status CNC-01 RUNNING>IDLE",
        "455000 finished CNC-01 PO-0001 1 5 0");
    assertThat(view(s, "CNC-01").status()).isEqualTo(MachineStatus.IDLE);
  }

  @Test
  void overrideShortensTheJobAndHeatsTheMachine() {
    var normal = sim(1, QUIET);
    normal.startJob(job("CNC-01", 0, 300_000));
    run(normal, 200_000);
    double normalTemp = view(normal, "CNC-01").temperature();

    feedback.calls.clear();
    feedback.now = 0;
    var fast = sim(1, QUIET);
    fast.configure("CNC-01", 130, true);
    fast.startJob(job("CNC-01", 0, 300_000));
    run(fast, 200_000);
    assertThat(view(fast, "CNC-01").temperature()).isGreaterThan(normalTemp + 5);
    runUntil(fast, "finished CNC-01", 300_000);
    assertThat(feedback.timeOf("finished CNC-01")).isBetween(230_700L, 230_800L);   // 300 s / 1.3
  }

  @Test
  void startJobOnABusyMachineIsRejected() {
    var s = sim(1, QUIET);
    s.startJob(job("CNC-01", 0, 100_000));
    run(s, 1_000);
    assertThatThrownBy(() -> s.startJob(new MachineJob("CNC-01", "PO-0002", 1, "FP12", 1, 0, 10_000)))
        .isInstanceOf(IllegalTransitionException.class);
    assertThat(view(s, "CNC-01").orderId()).isEqualTo("PO-0001");
  }

  @Test
  void unknownMachineAndUnknownFaultTypeAreRejected() {
    var s = sim(1, QUIET);
    assertThatThrownBy(() -> s.start("CNC-99")).isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).kind()).isEqualTo(DomainException.Kind.NOT_FOUND);
    assertThatThrownBy(() -> s.injectFault("CNC-01", "GRIPPER")).isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).kind()).isEqualTo(DomainException.Kind.INVALID);
  }

  @Test
  void faultWithoutScrapResumesAfterRepairWhereItStopped() {
    var s = sim(1, QUIET);
    s.startJob(job("CNC-01", 0, 300_000));
    run(s, 100_000);
    s.injectFault("CNC-01", "SERVO");
    assertThat(feedback.calls).containsSubsequence("100000 fault CNC-01 SERVO false",
        "100000 status CNC-01 RUNNING>FAULT");
    runUntil(s, "finished CNC-01", 3_600_000);
    long downtime = feedback.timeOf("REPAIR>RUNNING") - 100_000;
    assertThat(feedback.timeOf("finished CNC-01")).isEqualTo(300_000 + downtime);
  }

  @Test
  void scrappingFaultLosesTheBatchAndSendsNoJobFinished() {
    var s = sim(1, QUIET);
    s.startJob(job("CNC-01", 0, 300_000));
    run(s, 100_000);
    s.injectFault("CNC-01", "TOOL");
    assertThat(feedback.has("fault CNC-01 TOOL true")).isTrue();
    run(s, 1_800_000);
    assertThat(feedback.has("finished CNC-01")).isFalse();
    assertThat(view(s, "CNC-01").status()).isEqualTo(MachineStatus.IDLE);
    assertThat(view(s, "CNC-01").orderId()).isNull();
  }

  @Test
  void faultDuringSetupResumesTheSetupWhereItStopped() {
    var s = sim(1, QUIET);
    s.startJob(job("CNC-01", 60_000, 100_000));
    run(s, 30_000);
    s.injectFault("CNC-01", "SERVO");
    assertThat(feedback.has("30000 status CNC-01 SETUP>FAULT")).isTrue();
    runUntil(s, "finished CNC-01", 3_600_000);
    long downtime = feedback.timeOf("REPAIR>SETUP") - 30_000;
    assertThat(feedback.timeOf("finished CNC-01")).isEqualTo(160_000 + downtime);
  }

  @Test
  void scrappingFaultDuringSetupLosesTheBatch() {
    var s = sim(1, QUIET);
    s.startJob(job("CNC-01", 60_000, 100_000));
    run(s, 30_000);
    s.injectFault("CNC-01", "TOOL");
    assertThat(feedback.has("30000 fault CNC-01 TOOL true")).isTrue();
    run(s, 1_800_000);
    assertThat(feedback.has("finished CNC-01")).isFalse();
    assertThat(view(s, "CNC-01").status()).isEqualTo(MachineStatus.IDLE);
  }

  /** A stop must not cut a repair or maintenance short: the operator stops a machine that is working or idle. */
  @Test
  void stopIsRejectedDuringFaultRepairAndMaintenance() {
    var s = sim(1, QUIET);
    s.injectFault("CNC-01", "COMM");
    assertThatThrownBy(() -> s.stop("CNC-01")).isInstanceOf(IllegalTransitionException.class);
    s.repair("CNC-01");
    assertThatThrownBy(() -> s.stop("CNC-01")).isInstanceOf(IllegalTransitionException.class);
    s.maintain("ROB-01");
    assertThatThrownBy(() -> s.stop("ROB-01")).isInstanceOf(IllegalTransitionException.class);
    assertThat(view(s, "CNC-01").status()).isEqualTo(MachineStatus.REPAIR);
    assertThat(view(s, "ROB-01").status()).isEqualTo(MachineStatus.MAINTENANCE);
  }

  @Test
  void faultDuringMaintenanceOfAStoppedMachineKeepsItStopped() {
    var s = sim(1, QUIET);
    s.stop("CNC-01");
    s.maintain("CNC-01");
    s.injectFault("CNC-01", "COMM");
    runUntil(s, "REPAIR>STOPPED", 600_000);
    assertThat(view(s, "CNC-01").status()).isEqualTo(MachineStatus.STOPPED);
  }

  @Test
  void thermalProtectionTripsWithoutCoolantAtHighOverride() {
    var s = sim(1, QUIET);
    s.configure("CNC-01", 130, false);
    s.startJob(job("CNC-01", 0, 7_200_000));
    runUntil(s, "fault CNC-01 OVERTEMP", 7_200_000);
  }

  @Test
  void autoRepairStartsAfterTheDelayAndEndsAfterTheRepairTime() {
    var s = sim(3, QUIET);
    s.injectFault("CNC-01", "COMM");                 // repair time 60 s
    runUntil(s, "REPAIR>IDLE", 600_000);
    long repairStart = feedback.timeOf("FAULT>REPAIR");
    assertThat(repairStart).isBetween(15_000L, 35_100L);
    assertThat(feedback.timeOf("REPAIR>IDLE") - repairStart).isBetween(51_000L, 72_100L);
  }

  @Test
  void manualRepairOnlyFromFault() {
    var s = sim(1, QUIET);
    assertThatThrownBy(() -> s.repair("CNC-01")).isInstanceOf(IllegalTransitionException.class);
    s.injectFault("CNC-01", "COMM");
    s.repair("CNC-01");
    assertThat(view(s, "CNC-01").status()).isEqualTo(MachineStatus.REPAIR);
  }

  @Test
  void stopHoldsTheBatchAndStartContinuesIt() {
    var s = sim(1, QUIET);
    s.startJob(job("CNC-01", 0, 100_000));
    run(s, 50_000);
    s.stop("CNC-01");
    run(s, 200_000);
    assertThat(feedback.has("finished CNC-01")).isFalse();
    s.start("CNC-01");
    runUntil(s, "finished CNC-01", 100_000);
    assertThat(feedback.timeOf("finished CNC-01")).isEqualTo(300_000);
  }

  @Test
  void wornMachineGoesToMaintenanceBeforeTheNextJob() {
    var s = sim(1, new SimRules(false, true, true, 0.85));
    for (long t = 0; t < 4 * 3_600_000 && !feedback.has(">MAINTENANCE"); t += 100) {
      if (view(s, "CNC-01").status() == MachineStatus.IDLE && view(s, "CNC-01").orderId() == null) {
        s.startJob(job("CNC-01", 0, 600_000));
      }
      feedback.now += 100;
      s.step(100);
    }
    assertThat(feedback.has("RUNNING>MAINTENANCE")).as("straight from the finished job").isTrue();
    runUntil(s, "MAINTENANCE>IDLE", 600_000);
    assertThat(view(s, "CNC-01").wear()).isLessThan(0.05);
  }

  private List<String> faultsOver(long seed, boolean random, long ms) {
    feedback.calls.clear();
    feedback.now = 0;
    var s = sim(seed, new SimRules(random, true, false, 0.85));
    for (long t = 0; t < ms; t += 100) {
      if (view(s, "CNC-01").status() == MachineStatus.IDLE && view(s, "CNC-01").orderId() == null) {
        s.startJob(job("CNC-01", 0, 600_000));
      }
      feedback.now += 100;
      s.step(100);
    }
    return feedback.calls.stream().filter(c -> c.contains(" fault ")).toList();
  }

  @Test
  void randomFaultsFollowTheSeed() {
    List<String> first = faultsOver(5, true, 8 * 3_600_000L);
    List<String> second = faultsOver(5, true, 8 * 3_600_000L);
    assertThat(first).isNotEmpty().isEqualTo(second);
    assertThat(faultsOver(5, false, 8 * 3_600_000L)).isEmpty();
  }

  @Test
  void everyStatusChangeIsALegalTransition() {
    var legal = new MachineFeedback() {
      @Override public void statusChanged(String m, MachineStatus from, MachineStatus to) {
        assertThat(from).isNotEqualTo(to);
        assertThat(MachineStatus.RULES.can(from, to)).as(m + " " + from + ">" + to).isTrue();
      }
      @Override public void faultRaised(String m, String t, String l, boolean s) {}
      @Override public void jobFinished(String m, String o, int seq, int g, int sc) {}
      @Override public void conveyorDelivered(String m, String o, int seq, int q) {}
    };
    var s = new Simulation(List.of(new Simulation.MachineSpec("CNC-01", MachineKind.CNC),
        new Simulation.MachineSpec("ROB-01", MachineKind.ROBOT)), List.of(), new SeededRandom(11), legal);
    s.setRules(SimRules.DEFAULT);
    var chaos = new SeededRandom(99);
    String[] ids = {"CNC-01", "ROB-01"};
    for (long t = 0; t < 8 * 3_600_000L; t += 100) {
      if (chaos.nextInt(200) == 0) {
        String id = ids[chaos.nextInt(2)];
        try {
          switch (chaos.nextInt(6)) {
            case 0 -> s.startJob(new MachineJob(id, "PO-1", 1, "GH200", 2, chaos.nextInt(2) * 15_000L, 120_000));
            case 1 -> s.stop(id);
            case 2 -> s.start(id);
            case 3 -> s.injectFault(id, null);
            case 4 -> s.repair(id);
            default -> s.maintain(id);
          }
        } catch (IllegalTransitionException expected) {
          // rejected commands must leave the machine consistent; checked by the feedback above
        }
      }
      s.step(100);
    }
  }
}
