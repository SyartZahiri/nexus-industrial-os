package io.nexus.contracts.state;

import java.util.EnumSet;

public enum MachineStatus {
  IDLE, SETUP, RUNNING, BLOCKED, STOPPED, FAULT, REPAIR, MAINTENANCE;

  public static final Transitions<MachineStatus> RULES = Transitions.of(MachineStatus.class)
      .allow(IDLE, SETUP, RUNNING, STOPPED, MAINTENANCE)
      .allow(SETUP, RUNNING, STOPPED)
      .allow(RUNNING, IDLE, BLOCKED, STOPPED)
      .allow(BLOCKED, IDLE, STOPPED)
      .allow(STOPPED, IDLE, SETUP, RUNNING, BLOCKED, MAINTENANCE)
      .allowFromAll(EnumSet.of(IDLE, SETUP, RUNNING, BLOCKED, STOPPED), FAULT)
      .allow(FAULT, REPAIR)
      .allow(REPAIR, IDLE, SETUP, RUNNING, BLOCKED, STOPPED)
      .allow(MAINTENANCE, IDLE, STOPPED)
      .build();
}
