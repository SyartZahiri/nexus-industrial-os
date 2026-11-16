package io.nexus.routemind.store.memory;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.RouteStore;
import io.nexus.routemind.store.Tour;
import io.nexus.routemind.store.VehicleRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** RouteStore in memory. Every write can be undone by the unit of work. */
public final class MemoryRouteStore implements RouteStore {
  private final MemoryUnitOfWork work;
  private final TreeMap<String, Delivery> deliveries = new TreeMap<>();
  private final Map<String, String> byOrder = new HashMap<>(); // lookups only
  private final TreeMap<String, Tour> tours = new TreeMap<>();
  private final TreeMap<String, VehicleRecord> vehicles = new TreeMap<>();

  public MemoryRouteStore(MemoryUnitOfWork work) {
    this.work = work;
    for (var v : RouteMindMasterData.vehicles()) vehicles.put(v.id(), VehicleRecord.atDepot(v.id()));
  }

  @Override
  public void insertDelivery(Delivery d) {
    if (deliveries.containsKey(d.id()) || byOrder.containsKey(d.orderId())) {
      throw DomainException.conflict("Lieferauftrag für " + d.orderId() + " existiert bereits");
    }
    deliveries.put(d.id(), d);
    byOrder.put(d.orderId(), d.id());
    work.onRollback(() -> {
      deliveries.remove(d.id());
      byOrder.remove(d.orderId());
    });
  }

  @Override
  public Delivery updateDelivery(Delivery d) {
    Delivery before = deliveries.get(d.id());
    if (before == null) throw DomainException.notFound("Lieferauftrag", d.id());
    if (before.version() != d.version()) {
      throw DomainException.conflict("Lieferauftrag " + d.id() + " wurde zwischenzeitlich geändert");
    }
    Delivery saved = new Delivery(d.id(), d.orderId(), d.customerId(), d.productId(), d.quantity(),
        d.storedQuantity(), d.weightKg(), d.pallets(), d.priority(), d.earliestMs(), d.latestMs(), d.status(),
        d.tourId(), d.plannedArrivalMs(), d.deliveredAtMs(), d.reservationId(), d.transportId(), d.failures(),
        d.createdAtMs(), d.version() + 1);
    deliveries.put(d.id(), saved);
    work.onRollback(() -> deliveries.put(d.id(), before));
    return saved;
  }

  @Override
  public Optional<Delivery> delivery(String id) {
    return Optional.ofNullable(deliveries.get(id));
  }

  @Override
  public Optional<Delivery> deliveryOfOrder(String orderId) {
    String id = byOrder.get(orderId);
    return id == null ? Optional.empty() : delivery(id);
  }

  @Override
  public List<Delivery> deliveries() {
    return new ArrayList<>(deliveries.values());
  }

  @Override
  public void insertTour(Tour t) {
    if (tours.containsKey(t.id())) throw DomainException.conflict("Tour " + t.id() + " existiert bereits");
    tours.put(t.id(), t);
    work.onRollback(() -> tours.remove(t.id()));
  }

  @Override
  public Tour updateTour(Tour t) {
    Tour before = tours.get(t.id());
    if (before == null) throw DomainException.notFound("Tour", t.id());
    if (before.version() != t.version()) throw DomainException.conflict("Tour " + t.id() + " wurde zwischenzeitlich geändert");
    Tour saved = new Tour(t.id(), t.planId(), t.vehicleId(), t.departAtMs(), t.status(), t.stops(), t.km(), t.cost(),
        t.startedAtMs(), t.endedAtMs(), t.version() + 1);
    tours.put(t.id(), saved);
    work.onRollback(() -> tours.put(t.id(), before));
    return saved;
  }

  @Override
  public Optional<Tour> tour(String id) {
    return Optional.ofNullable(tours.get(id));
  }

  @Override
  public List<Tour> tours() {
    return new ArrayList<>(tours.values());
  }

  @Override
  public VehicleRecord updateVehicle(VehicleRecord v) {
    VehicleRecord before = vehicles.get(v.id());
    if (before == null) throw DomainException.notFound("Lieferfahrzeug", v.id());
    if (before.version() != v.version()) {
      throw DomainException.conflict("Lieferfahrzeug " + v.id() + " wurde zwischenzeitlich geändert");
    }
    VehicleRecord saved = new VehicleRecord(v.id(), v.status(), v.tourId(), v.leg(), v.legStartMs(), v.legEndMs(),
        v.breakdownAtMs(), v.readyAtMs(), v.version() + 1);
    vehicles.put(v.id(), saved);
    work.onRollback(() -> vehicles.put(v.id(), before));
    return saved;
  }

  @Override
  public List<VehicleRecord> vehicles() {
    return new ArrayList<>(vehicles.values());
  }
}
