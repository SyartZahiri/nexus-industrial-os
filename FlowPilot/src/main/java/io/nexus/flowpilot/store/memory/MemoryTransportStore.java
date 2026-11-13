package io.nexus.flowpilot.store.memory;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.flowpilot.store.TransportStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** TransportStore in memory. Every write can be undone by the unit of work. */
public final class MemoryTransportStore implements TransportStore {
  /** Priority (EILIG first), creation time, id: the dispatch order. */
  public static final Comparator<TransportOrder> OPEN_ORDER = Comparator
      .comparingInt((TransportOrder t) -> -t.priority().ordinal())
      .thenComparingLong(TransportOrder::createdAtMs)
      .thenComparing(TransportOrder::id);

  private final MemoryUnitOfWork work;
  private final TreeMap<String, TransportOrder> byId = new TreeMap<>();
  private final Map<String, String> byRequest = new HashMap<>(); // lookups only

  public MemoryTransportStore(MemoryUnitOfWork work) {
    this.work = work;
  }

  @Override
  public void insert(TransportOrder t) {
    if (byRequest.containsKey(t.requestId()) || byId.containsKey(t.id())) {
      throw DomainException.conflict("Transportanforderung " + t.requestId() + " existiert bereits");
    }
    byId.put(t.id(), t);
    byRequest.put(t.requestId(), t.id());
    work.onRollback(() -> {
      byId.remove(t.id());
      byRequest.remove(t.requestId());
    });
  }

  @Override
  public TransportOrder update(TransportOrder t) {
    TransportOrder before = byId.get(t.id());
    if (before == null) throw DomainException.notFound("Transportauftrag", t.id());
    if (before.version() != t.version()) {
      throw DomainException.conflict("Transportauftrag " + t.id() + " wurde zwischenzeitlich geändert");
    }
    var saved = new TransportOrder(t.id(), t.requestId(), t.kind(), t.orderId(), t.materialId(), t.quantity(),
        t.from(), t.fromNode(), t.to(), t.toNode(), t.priority(), t.status(), t.agvId(), t.reservationId(),
        t.createdAtMs(), t.version() + 1);
    byId.put(t.id(), saved);
    work.onRollback(() -> byId.put(t.id(), before));
    return saved;
  }

  @Override
  public Optional<TransportOrder> byId(String id) {
    return Optional.ofNullable(byId.get(id));
  }

  @Override
  public Optional<TransportOrder> byRequest(String requestId) {
    String id = byRequest.get(requestId);
    return id == null ? Optional.empty() : byId(id);
  }

  @Override
  public List<TransportOrder> open() {
    List<TransportOrder> out = new ArrayList<>();
    for (TransportOrder t : byId.values()) if (!t.status().terminal()) out.add(t);
    out.sort(OPEN_ORDER);
    return out;
  }

  @Override
  public List<TransportOrder> all() {
    return new ArrayList<>(byId.values());
  }
}
