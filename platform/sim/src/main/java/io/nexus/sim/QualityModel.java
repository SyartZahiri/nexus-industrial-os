package io.nexus.sim;

import io.nexus.contracts.sim.SeededRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defect risk of an order, grown by the conditions of every machine that worked on it (engine.ts, completeJob):
 * wear, overheating and overdriven feed make later scrap at the inspection cell more likely.
 */
final class QualityModel {
  private final Map<String, Double> riskByOrder = new LinkedHashMap<>();

  /** A non-inspection machine finished a batch of the order under its current conditions. */
  void recordStep(String orderId, MachineSim machine) {
    double ov = machine.overridePercent / 100.0;
    double add = 0.003 + 0.05 * machine.wear * machine.wear
        + (machine.temperature > MachinePhysics.TEMP_WARN ? 0.07 : 0)
        + (ov > 1.1 ? (ov - 1.1) * 0.25 : 0);
    riskByOrder.put(orderId, riskByOrder.getOrDefault(orderId, 0.0) + add);
  }

  /** The inspection cell judges every part of the batch once; the order's history ends there. */
  int inspect(String orderId, int quantity, SeededRandom random) {
    double risk = riskByOrder.getOrDefault(orderId, 0.0);
    riskByOrder.remove(orderId);
    int scrap = 0;
    for (int i = 0; i < quantity; i++) if (random.nextDouble() < risk) scrap++;
    return scrap;
  }
}
