package io.nexus.routemind.store;

import java.util.List;
import java.util.Optional;

/** RouteMind's records. Writes take part in the caller's unit of work. */
public interface RouteStore {
  /** Fails with DomainException CONFLICT if the id or the order already has a delivery. */
  void insertDelivery(Delivery d);

  /** Fails with CONFLICT if the stored version differs; stores and returns version + 1. */
  Delivery updateDelivery(Delivery d);

  Optional<Delivery> delivery(String id);

  Optional<Delivery> deliveryOfOrder(String orderId);

  /** Ordered by id. */
  List<Delivery> deliveries();

  /** Fails with CONFLICT if the id exists. */
  void insertTour(Tour t);

  /** Fails with CONFLICT if the stored version differs; stores stops and returns version + 1. */
  Tour updateTour(Tour t);

  Optional<Tour> tour(String id);

  /** Ordered by id. */
  List<Tour> tours();

  /** Fails with CONFLICT if the stored version differs; returns version + 1. */
  VehicleRecord updateVehicle(VehicleRecord v);

  /** Ordered by id; a new store has every vehicle of the master data AVAILABLE at the depot. */
  List<VehicleRecord> vehicles();
}
