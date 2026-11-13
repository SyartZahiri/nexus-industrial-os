package io.nexus.flowpilot.material;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.StockStore;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Reorders raw material and kits below their reorder point and books deliveries after the lead time.
 * Deliveries on their way are motion data: they live in memory, a server restart starts a new run anyway.
 */
public final class Replenishment {
  public static final long LEAD_TIME_MS = 300_000;

  private static final class Delivery {
    final String materialId;
    final int quantity;
    final long arrivalMs;
    boolean warned;

    Delivery(String materialId, int quantity, long arrivalMs) {
      this.materialId = materialId;
      this.quantity = quantity;
      this.arrivalMs = arrivalMs;
    }
  }

  private final StockStore stock;
  private final MaterialServiceImpl materials;
  private final EventPublisher events;
  private final UnitOfWork work;
  private final List<Delivery> pending = new ArrayList<>(); // order placed

  public Replenishment(StockStore stock, MaterialServiceImpl materials, EventPublisher events, UnitOfWork work) {
    this.stock = stock;
    this.materials = materials;
    this.events = events;
    this.work = work;
  }

  public void step(long nowMs) {
    deliver(nowMs);
    for (var m : FlowPilotMasterData.materials()) {
      if (m.kind().equals("FINISHED") || m.reorderQty() <= 0) continue;
      int sum = 0;
      for (StockLine l : stock.lines()) if (l.materialId().equals(m.id())) sum += l.available();
      int available = sum;
      int onTheWay = inTransit(m.id());
      if (available + onTheWay >= m.reorderPoint()) continue;
      work.inNewTransaction(() -> events.publish(EventTypes.DECISION, MaterialServiceImpl.SOURCE, Severity.INFO, null,
          new Payloads.Decision(m.id(), "Nachbestellung " + m.reorderQty(), List.of(),
              "verfügbar " + available + " + unterwegs " + onTheWay + " < Meldebestand " + m.reorderPoint())));
      pending.add(new Delivery(m.id(), m.reorderQty(), nowMs + LEAD_TIME_MS));
    }
  }

  /** Quantity of the material ordered but not yet booked into stock. */
  public int inTransit(String materialId) {
    int sum = 0;
    for (Delivery d : pending) if (d.materialId.equals(materialId)) sum += d.quantity;
    return sum;
  }

  /** For a reset: deliveries under way belong to the old run. */
  public void reset() {
    pending.clear();
  }

  private void deliver(long nowMs) {
    for (Iterator<Delivery> it = pending.iterator(); it.hasNext(); ) {
      Delivery d = it.next();
      if (d.arrivalMs > nowMs) continue;
      String target = roomiest(d);
      if (target == null) {
        if (!d.warned) {
          d.warned = true;
          work.inNewTransaction(() -> events.publish(EventTypes.DECISION, MaterialServiceImpl.SOURCE,
              Severity.WARNING, null, new Payloads.Decision(d.materialId, "Lieferung wartet", List.of(),
                  "kein Platz für " + d.quantity + " im Rohlager")));
        }
        continue;
      }
      try {
        work.inNewTransaction(() -> materials.receive(d.materialId, target, d.quantity));
        it.remove();
      } catch (DomainException full) {
        // somebody else filled the location in the meantime; try again next step
      }
    }
  }

  /** The location of the raw zone with the most free room that takes the whole delivery, or null. */
  private String roomiest(Delivery d) {
    String best = null;
    int bestRoom = -1;
    for (var l : FlowPilotMasterData.locations()) {
      if (!l.zone().equals("RAW")) continue;
      int room = materials.freeRoom(l.id());
      if (room >= d.quantity && room > bestRoom) {
        best = l.id();
        bestRoom = room;
      }
    }
    return best;
  }
}
