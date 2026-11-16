package io.nexus.plant;

import io.nexus.contracts.DomainException;
import io.nexus.sim.SimRules;
import io.nexus.smartfactory.planning.DispatchRule;

/**
 * Rules of the running plant.
 *
 * @param autoOrders          customers place orders by themselves, one every autoOrderIntervalMs
 * @param maxOpenOrders       no automatic order while this many are open
 * @param shipping            RouteMind delivers finished goods to the customers (Spec 15a) and goods no delivery needs leave a
 *                            store over 70 % full; off: everything stays in store
 */
public record PlantRules(SimRules machines, boolean autoOrders, long autoOrderIntervalMs, int maxOpenOrders,
                         boolean shipping, DispatchRule dispatchRule) {
  public static final PlantRules DEFAULT =
      new PlantRules(SimRules.DEFAULT, true, 360_000, 6, true, DispatchRule.PRIORITY);

  public PlantRules {
    if (machines == null || dispatchRule == null) throw DomainException.invalid("Regeln unvollständig");
    if (autoOrderIntervalMs < 10_000) throw DomainException.invalid("Auftragsabstand mindestens 10 s");
    if (maxOpenOrders < 1 || maxOpenOrders > 50) throw DomainException.invalid("Offene Aufträge 1 bis 50");
  }
}
