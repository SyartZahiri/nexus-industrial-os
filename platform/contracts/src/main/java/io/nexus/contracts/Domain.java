package io.nexus.contracts;

/** Shared vocabulary of all modules. Names are persisted as text; do not rename. */
public final class Domain {
  private Domain() {}

  public enum ProcessType { DREHEN, FRAESEN, MONTAGE, PRUEFEN }
  public enum Priority { NORMAL, HOCH, EILIG }
  public enum TransportKind { SUPPLY, WIP, PUTAWAY }
  public enum MachineKind { CNC, ROBOT, QC }
  public enum Severity { INFO, SUCCESS, WARNING, ERROR }
}
