package io.nexus.contracts.state;

import java.util.EnumSet;

public enum MachineStatus {
  IDLE, SETUP, RUNNING, BLOCKED, STOPPED, FAULT, REPAIR, MAINTENANCE;

  public static final Transitions<MachineStatus> RULES = Transitions.of(MachineStatus.class)
      .allow(IDLE, SETUP, RUNNING, STOPPED)
      .allow(SETUP, RUNNING, STOPPED)
      .allow(RUNNING, IDLE, BLOCKED, STOPPED)
      .allow(BLOCKED, IDLE, STOPPED)
      .allow(STOPPED, IDLE, SETUP, RUNNING, BLOCKED)
      // maintenance and faults can interrupt any operational state; afterwards the machine resumes where it was
      .allowFromAll(EnumSet.of(IDLE, SETUP, RUNNING, BLOCKED, STOPPED), MAINTENANCE)
      .allowFromAll(EnumSet.of(IDLE, SETUP, RUNNING, BLOCKED, STOPPED, MAINTENANCE), FAULT)
      .allow(FAULT, REPAIR)
      .allow(REPAIR, IDLE, SETUP, RUNNING, BLOCKED, STOPPED)
      .allow(MAINTENANCE, IDLE, SETUP, RUNNING, BLOCKED, STOPPED)
      .build();
}
