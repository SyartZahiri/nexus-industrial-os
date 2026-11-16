package io.nexus.app.routemind;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.TourStatus;
import io.nexus.contracts.state.VehicleStatus;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.RouteStore;
import io.nexus.routemind.store.Tour;
import io.nexus.routemind.store.VehicleRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** RouteStore on PostgreSQL with optimistic locking on version. Stops are rewritten with their tour. */
@Component
class JdbcRouteStore implements RouteStore {
  private static final String DELIVERY = """
      select id, order_id, customer_id, product_id, quantity, stored_quantity, weight_kg, pallets, priority,
             earliest_ms, latest_ms, status, tour_id, planned_arrival_ms, delivered_at_ms, reservation_id,
             transport_id, failures, created_at_ms, version
      from delivery_order
      """;
  private static final String TOUR = """
      select id, plan_id, vehicle_id, depart_at_ms, status, km, cost, started_at_ms, ended_at_ms, version from tour
      """;
  private static final String VEHICLE = """
      select id, status, tour_id, leg, leg_start_ms, leg_end_ms, breakdown_at_ms, ready_at_ms, version
      from delivery_vehicle
      """;

  private final JdbcClient jdbc;

  JdbcRouteStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insertDelivery(Delivery d) {
    try {
      jdbc.sql("""
          insert into delivery_order (id, order_id, customer_id, product_id, quantity, stored_quantity, weight_kg,
                                      pallets, priority, earliest_ms, latest_ms, status, tour_id, planned_arrival_ms,
                                      delivered_at_ms, reservation_id, transport_id, failures, created_at_ms, version)
          values (:id, :ord, :cust, :prod, :qty, :stored, :kg, :pal, :prio, :earliest, :latest, :status, :tour,
                  :planned, :delivered, :res, :transport, :failures, :created, :version)
          """).params(deliveryParams(d)).param("created", d.createdAtMs()).param("version", d.version()).update();
    } catch (DuplicateKeyException e) {
      throw DomainException.conflict("Lieferauftrag für " + d.orderId() + " existiert bereits");
    }
  }

  @Override
  public Delivery updateDelivery(Delivery d) {
    int rows = jdbc.sql("""
        update delivery_order
           set order_id = :ord, customer_id = :cust, product_id = :prod, quantity = :qty, stored_quantity = :stored,
               weight_kg = :kg, pallets = :pal, priority = :prio, earliest_ms = :earliest, latest_ms = :latest,
               status = :status, tour_id = :tour, planned_arrival_ms = :planned, delivered_at_ms = :delivered,
               reservation_id = :res, transport_id = :transport, failures = :failures, version = version + 1
         where id = :id and version = :version
        """).params(deliveryParams(d)).param("version", d.version()).update();
    if (rows == 0) {
      if (delivery(d.id()).isEmpty()) throw DomainException.notFound("Lieferauftrag", d.id());
      throw DomainException.conflict("Lieferauftrag " + d.id() + " wurde zwischenzeitlich geändert");
    }
    return new Delivery(d.id(), d.orderId(), d.customerId(), d.productId(), d.quantity(), d.storedQuantity(),
        d.weightKg(), d.pallets(), d.priority(), d.earliestMs(), d.latestMs(), d.status(), d.tourId(),
        d.plannedArrivalMs(), d.deliveredAtMs(), d.reservationId(), d.transportId(), d.failures(), d.createdAtMs(),
        d.version() + 1);
  }

  private static Map<String, Object> deliveryParams(Delivery d) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("id", d.id());
    p.put("ord", d.orderId());
    p.put("cust", d.customerId());
    p.put("prod", d.productId());
    p.put("qty", d.quantity());
    p.put("stored", d.storedQuantity());
    p.put("kg", d.weightKg());
    p.put("pal", d.pallets());
    p.put("prio", d.priority().name());
    p.put("earliest", d.earliestMs());
    p.put("latest", d.latestMs());
    p.put("status", d.status().name());
    p.put("tour", d.tourId());
    p.put("planned", d.plannedArrivalMs());
    p.put("delivered", d.deliveredAtMs());
    p.put("res", d.reservationId());
    p.put("transport", d.transportId());
    p.put("failures", d.failures());
    return p;
  }

  @Override
  public Optional<Delivery> delivery(String id) {
    return jdbc.sql(DELIVERY + " where id = :id").param("id", id).query((rs, i) -> delivery(rs)).optional();
  }

  @Override
  public Optional<Delivery> deliveryOfOrder(String orderId) {
    return jdbc.sql(DELIVERY + " where order_id = :o").param("o", orderId).query((rs, i) -> delivery(rs)).optional();
  }

  @Override
  public List<Delivery> deliveries() {
    return jdbc.sql(DELIVERY + " order by id collate \"C\"").query((rs, i) -> delivery(rs)).list();
  }

  @Override
  public void insertTour(Tour t) {
    try {
      jdbc.sql("""
          insert into tour (id, plan_id, vehicle_id, depart_at_ms, status, km, cost, started_at_ms, ended_at_ms, version)
          values (:id, :plan, :vehicle, :depart, :status, :km, :cost, :started, :ended, :version)
          """).param("id", t.id()).param("plan", t.planId()).param("vehicle", t.vehicleId())
          .param("depart", t.departAtMs()).param("status", t.status().name()).param("km", t.km())
          .param("cost", t.cost()).param("started", t.startedAtMs()).param("ended", t.endedAtMs())
          .param("version", t.version()).update();
    } catch (DuplicateKeyException e) {
      throw DomainException.conflict("Tour " + t.id() + " existiert bereits");
    }
    insertStops(t);
  }

  @Override
  public Tour updateTour(Tour t) {
    int rows = jdbc.sql("""
        update tour set status = :status, km = :km, cost = :cost, started_at_ms = :started, ended_at_ms = :ended,
                        version = version + 1
         where id = :id and version = :version
        """).param("id", t.id()).param("status", t.status().name()).param("km", t.km()).param("cost", t.cost())
        .param("started", t.startedAtMs()).param("ended", t.endedAtMs()).param("version", t.version()).update();
    if (rows == 0) {
      if (tour(t.id()).isEmpty()) throw DomainException.notFound("Tour", t.id());
      throw DomainException.conflict("Tour " + t.id() + " wurde zwischenzeitlich geändert");
    }
    jdbc.sql("delete from tour_stop where tour_id = :id").param("id", t.id()).update();
    insertStops(t);
    return new Tour(t.id(), t.planId(), t.vehicleId(), t.departAtMs(), t.status(), t.stops(), t.km(), t.cost(),
        t.startedAtMs(), t.endedAtMs(), t.version() + 1);
  }

  private void insertStops(Tour t) {
    for (Tour.Stop s : t.stops()) {
      jdbc.sql("""
          insert into tour_stop (tour_id, seq, delivery_id, customer_id, planned_arrival_ms, arrived_at_ms)
          values (:tour, :seq, :delivery, :customer, :planned, :arrived)
          """).param("tour", t.id()).param("seq", s.seq()).param("delivery", s.deliveryId())
          .param("customer", s.customerId()).param("planned", s.plannedArrivalMs()).param("arrived", s.arrivedAtMs())
          .update();
    }
  }

  @Override
  public Optional<Tour> tour(String id) {
    List<Tour> found = tours(TOUR + " where id = :id", id);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  @Override
  public List<Tour> tours() {
    return tours(TOUR + " order by id collate \"C\"", null);
  }

  private List<Tour> tours(String sql, String id) {
    var query = jdbc.sql(sql);
    if (id != null) query = query.param("id", id);
    record Row(String id, String plan, String vehicle, long depart, TourStatus status, double km, double cost,
               Long started, Long ended, int version) {}
    List<Row> rows = query.query((rs, i) -> new Row(rs.getString("id"), rs.getString("plan_id"),
        rs.getString("vehicle_id"), rs.getLong("depart_at_ms"), TourStatus.valueOf(rs.getString("status")),
        rs.getDouble("km"), rs.getDouble("cost"), rs.getObject("started_at_ms", Long.class),
        rs.getObject("ended_at_ms", Long.class), rs.getInt("version"))).list();
    Map<String, List<Tour.Stop>> stops = new LinkedHashMap<>();
    jdbc.sql("""
        select tour_id, seq, delivery_id, customer_id, planned_arrival_ms, arrived_at_ms from tour_stop
        order by tour_id collate "C", seq
        """).query((ResultSet rs) -> {
          stops.computeIfAbsent(rs.getString("tour_id"), k -> new ArrayList<>()).add(new Tour.Stop(rs.getInt("seq"),
              rs.getString("delivery_id"), rs.getString("customer_id"), rs.getLong("planned_arrival_ms"),
              rs.getObject("arrived_at_ms", Long.class)));
        });
    List<Tour> out = new ArrayList<>();
    for (Row r : rows) {
      out.add(new Tour(r.id(), r.plan(), r.vehicle(), r.depart(), r.status(), stops.getOrDefault(r.id(), List.of()),
          r.km(), r.cost(), r.started(), r.ended(), r.version()));
    }
    return out;
  }

  @Override
  public VehicleRecord updateVehicle(VehicleRecord v) {
    int rows = jdbc.sql("""
        update delivery_vehicle
           set status = :status, tour_id = :tour, leg = :leg, leg_start_ms = :start, leg_end_ms = :end,
               breakdown_at_ms = :breakdown, ready_at_ms = :ready, version = version + 1
         where id = :id and version = :version
        """).param("id", v.id()).param("status", v.status().name()).param("tour", v.tourId()).param("leg", v.leg())
        .param("start", v.legStartMs()).param("end", v.legEndMs()).param("breakdown", v.breakdownAtMs())
        .param("ready", v.readyAtMs()).param("version", v.version()).update();
    if (rows == 0) {
      boolean exists = jdbc.sql("select count(*) from delivery_vehicle where id = :id").param("id", v.id())
          .query(Integer.class).single() > 0;
      if (!exists) throw DomainException.notFound("Lieferfahrzeug", v.id());
      throw DomainException.conflict("Lieferfahrzeug " + v.id() + " wurde zwischenzeitlich geändert");
    }
    return new VehicleRecord(v.id(), v.status(), v.tourId(), v.leg(), v.legStartMs(), v.legEndMs(), v.breakdownAtMs(),
        v.readyAtMs(), v.version() + 1);
  }

  @Override
  public List<VehicleRecord> vehicles() {
    return jdbc.sql(VEHICLE + " order by id collate \"C\"").query((rs, i) -> new VehicleRecord(rs.getString("id"),
        VehicleStatus.valueOf(rs.getString("status")), rs.getString("tour_id"), rs.getInt("leg"),
        rs.getLong("leg_start_ms"), rs.getLong("leg_end_ms"), rs.getObject("breakdown_at_ms", Long.class),
        rs.getLong("ready_at_ms"), rs.getInt("version"))).list();
  }

  private static Delivery delivery(ResultSet rs) throws SQLException {
    return new Delivery(rs.getString("id"), rs.getString("order_id"), rs.getString("customer_id"),
        rs.getString("product_id"), rs.getInt("quantity"), rs.getInt("stored_quantity"), rs.getInt("weight_kg"),
        rs.getInt("pallets"), Priority.valueOf(rs.getString("priority")), rs.getLong("earliest_ms"),
        rs.getLong("latest_ms"), DeliveryStatus.valueOf(rs.getString("status")), rs.getString("tour_id"),
        rs.getObject("planned_arrival_ms", Long.class), rs.getObject("delivered_at_ms", Long.class),
        rs.getString("reservation_id"), rs.getString("transport_id"), rs.getInt("failures"),
        rs.getLong("created_at_ms"), rs.getInt("version"));
  }
}
