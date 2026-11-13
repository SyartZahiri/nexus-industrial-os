package io.nexus.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * A conveyor that carries batches from assembly cells to the machine it feeds. Travel time is counted in whole
 * milliseconds, so the arrival step is exact on every runtime. Batches leave in the order they reach the end.
 */
final class ConveyorSim {
  static final class Item {
    final Simulation.ConveyorLink link;
    final String orderId;
    final int stepSeq;
    final int quantity;
    final long travelMs;
    long elapsedMs;

    Item(Simulation.ConveyorLink link, String orderId, int stepSeq, int quantity) {
      this.link = link;
      this.orderId = orderId;
      this.stepSeq = stepSeq;
      this.quantity = quantity;
      this.travelMs = (long) Math.ceil((link.takeAtM() - link.dropAtM()) / link.speedMps() * 1000);
    }

    double positionM() {
      return link.dropAtM() + link.speedMps() * elapsedMs / 1000;
    }
  }

  final String id;
  final List<Item> items = new ArrayList<>();

  ConveyorSim(String id) {
    this.id = id;
  }

  /** Moves every batch on; returns the batches that arrived in this step, in arrival order. */
  List<Item> step(long dtMs) {
    List<Item> arrived = new ArrayList<>();
    for (Item item : items) {
      item.elapsedMs += dtMs;
      if (item.elapsedMs >= item.travelMs) arrived.add(item);
    }
    items.removeAll(arrived);
    return arrived;
  }
}
