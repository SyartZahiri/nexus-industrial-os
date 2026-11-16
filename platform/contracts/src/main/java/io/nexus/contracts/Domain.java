package io.nexus.contracts;

/** Shared vocabulary of all modules. Names are persisted as text; do not rename. */
public final class Domain {
  private Domain() {}

  public enum ProcessType { DREHEN, FRAESEN, MONTAGE, PRUEFEN }
  public enum Priority { NORMAL, HOCH, EILIG }
  /** SHIPMENT: finished goods from the finished-goods store to the loading zone (RouteMind). */
  public enum TransportKind { SUPPLY, WIP, PUTAWAY, SHIPMENT }
  public enum MachineKind { CNC, ROBOT, QC }
  public enum Severity { INFO, SUCCESS, WARNING, ERROR }
}
