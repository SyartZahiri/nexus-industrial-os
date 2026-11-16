package io.nexus.routemind;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payload;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.ProductCatalog;
import io.nexus.contracts.port.ProductionEta;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.memory.MemoryRouteStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Fixture {
  static final long MIN = 60_000;

  final MemoryUnitOfWork work = new MemoryUnitOfWork();
  final MemoryEventLog log = new MemoryEventLog(work);
  final SimTime time = new SimTime();
  final EventIds eventIds = new EventIds(1);
  final EventBus bus = new EventBus(log, work, time, () -> eventIds);
  final MemoryIdSource ids = new MemoryIdSource(work);
  final List<TransportService.TransportRequest> shipments = new ArrayList<>();
  final Map<String, Long> etas = new HashMap<>();
  final RouteMind rm;

  Fixture(long seed) {
    MaterialService materials = new MaterialService() {
      @Override public ReservationResult reserve(ReservationRequest r) {
        var line = r.lines().get(0);
        return new ReservationResult.Success(r.requestId(),
            List.of(new Reserved("RES-" + r.requestId(), line.materialId(), "FG-A", line.quantity())));
      }
      @Override public void release(String requestId) {}
      @Override public List<Availability> availability(String materialId) { return List.of(); }
    };
    TransportService transports = new TransportService() {
      @Override public String requestTransport(TransportRequest r) {
        for (int i = 0; i < shipments.size(); i++) {
          if (shipments.get(i).requestId().equals(r.requestId())) return "TO-" + (i + 1);
        }
        shipments.add(r);
        return "TO-" + shipments.size();
      }
      @Override public void cancel(String transportId) {}
      @Override public void redirect(String transportId, Place newTo) {}
    };
    ProductionEta eta = orderId -> etas.getOrDefault(orderId, -1L);
    ProductCatalog catalog = new ProductCatalog() {
      @Override public List<MaterialDemand> demandFor(String productId, int quantity) { return List.of(); }
      @Override public String finishedMaterialId(String productId) { return "FG-" + productId; }
    };
    rm = new RouteMind(new MemoryRouteStore(work), bus, work, time, ids, materials, transports, eta, catalog,
        new SavingsHeuristic(), seed);
  }

  void emit(String type, String orderId, Payload p) {
    work.inNewTransaction(() -> bus.publish(type, "test", Severity.INFO, orderId, p));
  }

  /** A released production order. */
  void order(String orderId, String product, int qty, Priority priority, long dueAtMs) {
    emit(EventTypes.ORDER_CREATED, orderId, new Payloads.OrderCreated(orderId, product, qty, priority, dueAtMs));
    emit(EventTypes.ORDER_RELEASED, orderId,
        new Payloads.OrderStatusChanged(orderId, OrderStatus.MATERIAL_PENDING, OrderStatus.RELEASED, "frei"));
  }

  /** SmartFactory's putaway of the good parts, then the completion. */
  void putAway(String orderId, int good) {
    emit(EventTypes.TRANSPORT_DELIVERED, orderId, new Payloads.TransportDelivered("TP-" + orderId,
        TransportKind.PUTAWAY, orderId, "FG-x", good, Place.storage("FG-A")));
    emit(EventTypes.ORDER_COMPLETED, orderId,
        new Payloads.OrderStatusChanged(orderId, OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED, good + " Gutteile"));
  }

  /** FlowPilot reports the shipment at the loading zone. */
  void provide(String orderId) {
    Delivery d = rm.deliveryOfOrder(orderId);
    emit(EventTypes.TRANSPORT_DELIVERED, orderId, new Payloads.TransportDelivered(d.transportId(),
        TransportKind.SHIPMENT, orderId, "FG-x", d.storedQuantity(), Place.loading(RouteMind.LOADING_ZONE)));
  }

  /** Order, putaway, shipment request (within 10 s) and provision: the delivery is READY_TO_LOAD. */
  void ready(String orderId, String product, int qty, long dueAtMs) {
    order(orderId, product, qty, Priority.NORMAL, dueAtMs);
    putAway(orderId, qty);
    advance(10_000);
    provide(orderId);
  }

  void advance(long ms) {
    for (long t = 0; t < ms; t += 100) {
      time.advance(100);
      rm.step();
    }
  }

  void advanceTo(long atMs) {
    advance(atMs - time.nowMs());
  }

  List<NexusEvent> events(String type) {
    List<NexusEvent> out = new ArrayList<>();
    for (NexusEvent e : log.all()) if (e.type().equals(type)) out.add(e);
    return out;
  }

  /** An order id whose delivery goes to the given customer (RouteMind.customerOf). */
  static String orderFor(String customerId, int start) {
    for (int i = start; i < start + 10_000; i++) {
      String id = String.format("PO-%04d", i);
      if (RouteMind.customerOf(id).equals(customerId)) return id;
    }
    throw new IllegalStateException("kein Auftrag für " + customerId);
  }
}
