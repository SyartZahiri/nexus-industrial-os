package io.nexus.flowpilot.store.memory;

import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.store.Reservation;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.StockStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** StockStore in memory, seeded with the initial stock. Every write can be undone by the unit of work. */
public final class MemoryStockStore implements StockStore {
  private static final char SEP = '\u0000';

  private final MemoryUnitOfWork work;
  private final TreeMap<String, StockLine> lines = new TreeMap<>();          // material SEP location
  private final TreeMap<String, Reservation> reservations = new TreeMap<>(); // by id

  public MemoryStockStore(MemoryUnitOfWork work) {
    this.work = work;
    for (var s : FlowPilotMasterData.initialStock()) {
      lines.put(key(s.materialId(), s.locationId()), new StockLine(s.materialId(), s.locationId(), s.onHand(), 0));
    }
  }

  private static String key(String materialId, String locationId) {
    return materialId + SEP + locationId;
  }

  @Override
  public List<StockLine> lockMaterial(String materialId) {
    List<StockLine> out = new ArrayList<>();
    for (StockLine l : lines.values()) if (l.materialId().equals(materialId)) out.add(l);
    return out; // the key order puts locations in id order
  }

  @Override
  public void saveLine(StockLine line) {
    put(lines, key(line.materialId(), line.locationId()), line);
  }

  @Override
  public List<StockLine> lines() {
    return new ArrayList<>(lines.values());
  }

  @Override
  public List<Reservation> reservationsOfRequest(String requestId) {
    List<Reservation> out = new ArrayList<>();
    for (Reservation r : reservations.values()) if (r.requestId().equals(requestId)) out.add(r);
    out.sort(Comparator.comparing(Reservation::materialId));
    return out;
  }

  @Override
  public Optional<Reservation> reservation(String id) {
    return Optional.ofNullable(reservations.get(id));
  }

  @Override
  public void saveReservation(Reservation r) {
    put(reservations, r.id(), r);
  }

  @Override
  public List<Reservation> reservationsOfOrder(String orderId) {
    List<Reservation> out = new ArrayList<>();
    for (Reservation r : reservations.values()) if (r.orderId().equals(orderId)) out.add(r);
    return out;
  }

  private <V> void put(Map<String, V> map, String key, V value) {
    V before = map.put(key, value);
    work.onRollback(() -> {
      if (before == null) map.remove(key);
      else map.put(key, before);
    });
  }
}
