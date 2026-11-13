package io.nexus.plant;

import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.store.StockLine;
import java.util.List;

/**
 * Finished goods leave the plant until RouteMind collects them: once a finished-goods location is more than 70 %
 * full, the largest free quantities are shipped until it is at 50 %. Checked every 10 s of simulation time.
 */
final class Shipping {
  static final long CHECK_EVERY_MS = 10_000;

  void step(long nowMs, PlantRules rules, FlowPilot flowPilot) {
    if (!rules.shipping() || nowMs % CHECK_EVERY_MS != 0) return;
    for (FlowPilotMasterData.StorageLocation location : FlowPilotMasterData.locations()) {
      if (!location.zone().equals("FINISHED")) continue;
      List<StockLine> lines = flowPilot.stock();
      int used = 0;
      for (StockLine l : lines) if (l.locationId().equals(location.id())) used += l.onHand();
      if (used * 10 <= location.capacity() * 7) continue;
      int target = location.capacity() / 2;
      while (used > target) {
        StockLine largest = null;
        for (StockLine l : flowPilot.stock()) {
          if (!l.locationId().equals(location.id()) || l.available() <= 0) continue;
          if (largest == null || l.available() > largest.available()) largest = l;
        }
        if (largest == null) break;                      // everything left is reserved
        int quantity = Math.min(largest.available(), used - target);
        flowPilot.issue(largest.materialId(), location.id(), quantity);
        used -= quantity;
      }
    }
  }
}
