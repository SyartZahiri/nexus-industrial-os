package io.nexus.sim;

import io.nexus.contracts.Domain.MachineKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.contracts.port.MachineFeedback;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.MachineStatus;
import java.util.List;

/**
 * One simulated machine. Models after FactoryTwin/src/sim/engine.ts (updateMachine, injectFault, beginRepair,
 * startMaintenance, resumeAfterDowntime), adapted to batches. Work is counted in exact integer units
 * (milliseconds × override percent), so a job at 100 % takes exactly setupMs + processMs.
 */
final class MachineSim {
  final String id;
  final MachineKind kind;
  private final MachinePhysics physics;
  private final SeededRandom random;
  private final MachineFeedback feedback;

  MachineStatus status = MachineStatus.IDLE;
  /** Status to return to after a fault or maintenance that interrupted a stopped machine. */
  private boolean resumeStopped;
  MachineJob job;
  private long setupUnitsLeft;
  private long processUnitsLeft;
  private long totalUnits;

  double wear = 0.02;
  double temperature = MachinePhysics.AMBIENT_TEMP;
  double vibration;
  double power;
  double spindle;
  int overridePercent = 100;
  boolean coolant = true;

  FaultType fault;
  private double repairDelayS;
  private double repairLeftS;
  private double maintenanceLeftS;

  MachineSim(String id, MachineKind kind, SeededRandom random, MachineFeedback feedback) {
    this.id = id;
    this.kind = kind;
    this.physics = MachinePhysics.of(kind);
    this.random = random;
    this.feedback = feedback;
    this.power = physics.idlePower();
  }

  double progress() {
    if (job == null || totalUnits == 0) return 0;
    return (double) (totalUnits - setupUnitsLeft - processUnitsLeft) / totalUnits;
  }

  /* ---------------------------------------------------------------- commands */

  void startJob(MachineJob newJob) {
    if (status != MachineStatus.IDLE || job != null) {
      throw new IllegalTransitionException(id, status, MachineStatus.RUNNING);
    }
    if (newJob.quantity() <= 0 || newJob.processMs() <= 0 || newJob.setupMs() < 0) {
      throw DomainException.invalid("Maschinenauftrag für " + id + " ungültig: " + newJob);
    }
    job = newJob;
    setupUnitsLeft = newJob.setupMs() * 100;
    processUnitsLeft = newJob.processMs() * 100;
    totalUnits = setupUnitsLeft + processUnitsLeft;
    setStatus(setupUnitsLeft > 0 ? MachineStatus.SETUP : MachineStatus.RUNNING);
  }

  void start() {
    if (status != MachineStatus.STOPPED) throw new IllegalTransitionException(id, status, MachineStatus.IDLE);
    resumeStopped = false;
    resume();
  }

  /** Stops a machine that is working or idle; a fault, repair or maintenance runs to its end first. */
  void stop() {
    if (status == MachineStatus.STOPPED) return;
    if (status != MachineStatus.IDLE && status != MachineStatus.SETUP && status != MachineStatus.RUNNING
        && status != MachineStatus.BLOCKED) {
      throw new IllegalTransitionException(id, status, MachineStatus.STOPPED);
    }
    setStatus(MachineStatus.STOPPED);
  }

  void injectFault(String faultTypeId) {
    if (status == MachineStatus.FAULT || status == MachineStatus.REPAIR) {
      throw new IllegalTransitionException(id, status, MachineStatus.FAULT);
    }
    List<FaultType> types = FaultType.forKind(kind);
    FaultType type = null;
    if (faultTypeId == null) {
      type = types.get(random.nextInt(types.size()));
    } else {
      for (FaultType t : types) if (t.id().equals(faultTypeId)) type = t;
      if (type == null) throw DomainException.invalid("Störungsart " + faultTypeId + " passt nicht zu " + id);
    }
    raise(type);
  }

  void repair() {
    if (status != MachineStatus.FAULT) throw new IllegalTransitionException(id, status, MachineStatus.REPAIR);
    beginRepair();
  }

  void maintain() {
    MachineStatus.RULES.move(id, status, MachineStatus.MAINTENANCE);
    if (status == MachineStatus.MAINTENANCE) return;
    startMaintenance();
  }

  void configure(int override, boolean withCoolant) {
    if (override < 50 || override > 130) throw DomainException.invalid("Override muss zwischen 50 und 130 % liegen");
    overridePercent = override;
    coolant = kind == MachineKind.CNC && withCoolant;
  }

  /* ---------------------------------------------------------------- simulation */

  /** Advances the machine. Returns a finished job (status already IDLE), or null; the caller reports it. */
  MachineJob step(long dtMs, SimRules rules) {
    double dtS = dtMs / 1000.0;
    double ov = overridePercent / 100.0;
    MachineJob finished = null;
    switch (status) {
      case FAULT -> {
        if (rules.autoRepair()) {
          repairDelayS -= dtS;
          if (repairDelayS <= 0) beginRepair();
        }
      }
      case REPAIR -> {
        repairLeftS -= dtS;
        if (repairLeftS <= 0) {
          fault = null;
          resume();
        }
      }
      case MAINTENANCE -> {
        maintenanceLeftS -= dtS;
        wear = Math.max(0.02, wear - dtS * 0.006);
        if (maintenanceLeftS <= 0) {
          wear = 0.02;
          resume();
        }
      }
      case IDLE -> {
        if (rules.autoMaintenance() && job == null && wear >= rules.maintenanceThreshold()) startMaintenance();
      }
      case SETUP -> {
        setupUnitsLeft -= dtMs * overridePercent;
        if (setupUnitsLeft <= 0) {
          setupUnitsLeft = 0;
          setStatus(MachineStatus.RUNNING);
        }
      }
      case RUNNING -> {
        wear = Math.min(1, wear + (dtS / physics.lifetimeS()) * ov * ov
            * (kind == MachineKind.CNC && !coolant ? 1.5 : 1));
        if (rules.randomFaults()) {
          double hot = temperature > MachinePhysics.TEMP_WARN ? 2.5 : 1;
          double hazard = (1 / physics.mtbfS()) * (0.35 + 2.4 * wear * wear) * hot * (ov > 1 ? ov * ov : 1);
          if (random.nextDouble() < hazard * dtS) {
            List<FaultType> types = FaultType.randomForKind(kind);
            raise(types.get(random.nextInt(types.size())));
            break;
          }
        }
        processUnitsLeft -= dtMs * overridePercent;
        if (processUnitsLeft <= 0) {
          finished = job;
          job = null;
          processUnitsLeft = 0;
          setupUnitsLeft = 0;
          // A worn machine goes into maintenance before the next job: SmartFactory starts a new job the moment
          // the machine reports IDLE, so the check in the IDLE case alone would never get a chance.
          if (rules.autoMaintenance() && wear >= rules.maintenanceThreshold()) startMaintenance();
          else setStatus(MachineStatus.IDLE);
        }
      }
      default -> { }
    }
    updateSensors(dtS, ov);
    if (temperature >= MachinePhysics.TEMP_TRIP
        && (status == MachineStatus.RUNNING || status == MachineStatus.SETUP || status == MachineStatus.BLOCKED)) {
      for (FaultType t : FaultType.ALL_TYPES) if (t.id().equals("OVERTEMP")) raise(t);
    }
    return finished;
  }

  private void updateSensors(double dtS, double ov) {
    boolean running = status == MachineStatus.RUNNING;
    boolean active = running || status == MachineStatus.SETUP;
    double coolantEffect = kind == MachineKind.CNC && coolant ? physics.coolantEffect() : 0;
    double heat = active ? physics.heatRun() * ov * ov * (status == MachineStatus.SETUP ? 0.35 : 1) : physics.heatIdle();
    double targetT = MachinePhysics.AMBIENT_TEMP + heat - (active ? coolantEffect : coolantEffect * 0.2) + wear * 9;
    temperature += (targetT - temperature) * (dtS / MachinePhysics.THERMAL_TAU_S) + gauss(0.015);
    // engine.ts uses wear^1.6; wear^1.5 = wear * sqrt(wear) is close and computed bit-identically on every runtime
    double vibTarget = running ? (0.8 + 5.2 * wear * Math.sqrt(wear)) * ov : status == MachineStatus.SETUP ? 0.5 : 0.08;
    vibration = Math.max(0, vibration + (vibTarget - vibration) * Math.min(1, dtS * 1.5) + gauss(0.01));
    double coolantPower = kind == MachineKind.CNC && coolant && active ? 1.4 : 0;
    double powerTarget = switch (status) {
      case STOPPED, FAULT, REPAIR, MAINTENANCE -> physics.idlePower() * 0.35;
      case RUNNING -> physics.runPower() * MachinePhysics.overridePow16(overridePercent) + coolantPower;
      case SETUP -> physics.idlePower() + physics.runPower() * 0.25;
      default -> physics.idlePower();
    };
    power = Math.max(0, power + (powerTarget - power) * Math.min(1, dtS * 2) + gauss(0.02));
    double spindleTarget = running ? physics.spindleRpm() * ov : 0;
    spindle += (spindleTarget - spindle) * Math.min(1, dtS * 1.2);
  }

  /** Approximately normal noise from additions only (Irwin-Hall), identical on the JVM and in the browser. */
  private double gauss(double sigma) {
    double sum = 0;
    for (int i = 0; i < 12; i++) sum += random.nextDouble();
    return (sum - 6) * sigma;
  }

  private void raise(FaultType type) {
    boolean scrapped = type.scrapsWorkpiece() && job != null;
    // a machine the operator stopped stays stopped, also when the fault interrupts its maintenance
    resumeStopped = status == MachineStatus.STOPPED || (status == MachineStatus.MAINTENANCE && resumeStopped);
    maintenanceLeftS = 0;
    fault = type;
    repairDelayS = random.range(15, 35);
    if (scrapped) {
      job = null;
      setupUnitsLeft = 0;
      processUnitsLeft = 0;
    }
    feedback.faultRaised(id, type.id(), type.label(), scrapped);
    setStatus(MachineStatus.FAULT);
  }

  private void beginRepair() {
    repairLeftS = fault.repairTimeS() * random.range(0.85, 1.2);
    setStatus(MachineStatus.REPAIR);
  }

  private void startMaintenance() {
    resumeStopped = status == MachineStatus.STOPPED;
    maintenanceLeftS = 150 + wear * 120;
    setStatus(MachineStatus.MAINTENANCE);
  }

  private void resume() {
    if (resumeStopped) {
      setStatus(MachineStatus.STOPPED);
    } else if (job != null) {
      setStatus(setupUnitsLeft > 0 ? MachineStatus.SETUP : MachineStatus.RUNNING);
    } else {
      setStatus(MachineStatus.IDLE);
    }
  }

  private void setStatus(MachineStatus to) {
    if (to == status) return;
    MachineStatus from = status;
    status = MachineStatus.RULES.move(id, from, to);
    feedback.statusChanged(id, from, to);
  }
}
