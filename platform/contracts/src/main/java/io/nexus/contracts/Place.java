package io.nexus.contracts;

/**
 * A place a transport starts or ends, named by its business id. Only FlowPilot knows the path graph
 * and resolves a place to a node.
 */
public record Place(Kind kind, String id) {
  public enum Kind {
    /** A storage location, e.g. RAW-A. */
    STORAGE,
    /** Input side of a machine, e.g. CNC-01. */
    MACHINE_IN,
    /** Pick-up point for finished parts of a machine. */
    MACHINE_OUT,
    CONVEYOR,
    CHARGER
  }

  public static Place storage(String locationId) {
    return new Place(Kind.STORAGE, locationId);
  }

  public static Place machineIn(String machineId) {
    return new Place(Kind.MACHINE_IN, machineId);
  }

  public static Place machineOut(String machineId) {
    return new Place(Kind.MACHINE_OUT, machineId);
  }
}
