package io.nexus.sim;

/** Rules of the machine simulation, values as in FactoryTwin's DEFAULT_RULES. */
public record SimRules(boolean randomFaults, boolean autoRepair, boolean autoMaintenance, double maintenanceThreshold) {
  public static final SimRules DEFAULT = new SimRules(true, true, true, 0.85);

  public SimRules {
    if (maintenanceThreshold <= 0 || maintenanceThreshold > 1) {
      throw io.nexus.contracts.DomainException.invalid(
          "Wartungsschwelle muss zwischen 0 und 1 liegen: " + maintenanceThreshold);
    }
  }
}
