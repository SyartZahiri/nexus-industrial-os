package io.nexus.plant;

import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.store.StockLine;
import java.util.List;
import java.util.Map;

/**
 * Overflow of the finished-goods store: RouteMind ships the goods of every order, but goods no delivery belongs to
 * (initial stock, goods receipts) would fill the store for good and block every putaway. Once a finished-goods
 * location is more than 70 % full, the largest free quantities that no waiting delivery needs leave the plant until
 * it is at 50 %. Checked every 10 s of simulation time.
 */
final class Shipping {
  static final long CHECK_EVERY_MS = 10_000;

  /** @param keep units per finished material that deliveries are about to reserve; never shipped */
  void step(long nowMs, PlantRules rules, FlowPilot flowPilot, Map<String, Integer> keep) {
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
        int largestFree = 0;
        for (StockLine l : flowPilot.stock()) {
          if (!l.locationId().equals(location.id())) continue;
          int free = l.available() - keep.getOrDefault(l.materialId(), 0);
          if (free > largestFree) {
            largest = l;
            largestFree = free;
          }
        }
        if (largest == null) break;                      // everything left is reserved or awaited
        int quantity = Math.min(largestFree, used - target);
        flowPilot.issue(largest.materialId(), location.id(), quantity);
        used -= quantity;
      }
    }
  }
}
