package io.nexus.routemind;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.ProductCatalog;
import io.nexus.contracts.port.ProductionEta;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.TourStatus;
import io.nexus.contracts.state.VehicleStatus;
import io.nexus.routemind.master.Roads;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.optimize.Evaluation;
import io.nexus.routemind.optimize.Job;
import io.nexus.routemind.optimize.Matrix;
import io.nexus.routemind.optimize.NearestNeighbour;
import io.nexus.routemind.optimize.Problem;
import io.nexus.routemind.optimize.Route;
import io.nexus.routemind.optimize.RouteOptimizer;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.optimize.Solution;
import io.nexus.routemind.optimize.Truck;
import io.nexus.routemind.optimize.Unassigned;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.RouteStore;
import io.nexus.routemind.store.Tour;
import io.nexus.routemind.store.VehicleRecord;
import io.nexus.routemind.store.memory.MemoryRouteStore;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade of RouteMind (Spec 15a): delivery orders, vehicles, tours. Listens to order and transport events, asks
 * FlowPilot for the goods and SmartFactory for completion times, plans waves with the RouteOptimizer and moves the
 * vehicles on the simulation clock.
 *
 * <p>Waves: tours leave every WAVE_MS within the shift. Wave W is planned at W − WAVE_MS, loaded from
 * W − LOADING_LEAD_MS and started at W. A tour starts only with confirmed loading (hard rule); goods that missed
 * the departure stay for the next wave. A replanning touches PLANNED tours only, never a delivered delivery or a
 * visited stop.
 *
 * <p>Threading: every public method holds the event bus monitor, the platform lock. The consumer only records facts;
 * everything that calls other modules happens in step().
 *
 * <p>ponytail: tours() and deliveries() read all records; fine for a simulated day (~30 waves). An "open" query on
 * the store is the upgrade for long server runs.
 */
public final class RouteMind {
  public static final String CONSUMER = "routemind";
  public static final String SOURCE = "routemind";
  public static final String LOADING_ZONE = "LZ-1";
  public static final long MIN = RouteMindMasterData.MINUTE;
  public static final long WAVE_MS = 30 * MIN;
  public static final long LOADING_LEAD_MS = 15 * MIN;
  public static final long SERVICE_MS = 10 * MIN;
  public static final long READY_BUFFER_MS = 10 * MIN;
  /** Goods expected up to this long before departure still go into the wave: loading goes on until departure. */
  public static final long LAST_GOODS_MS = 5 * MIN;
  public static final long REPAIR_MS = 60 * MIN;
  /** The first attempt and at most two more (Spec 15a). */
  public static final int MAX_ATTEMPTS = 3;
  private static final long PROVISION_EVERY_MS = 10_000;
  private static final long LOAD_EVERY_MS = 1_000;
  private static final int KEEP_PLANS = 20;
  private static final Matrix ROADS = new Matrix() {
    @Override public int seconds(int from, int to) { return Roads.seconds(from, to); }
    @Override public int meters(int from, int to) { return Roads.meters(from, to); }
  };

  public record TruckView(String id, String name, VehicleStatus status, String tourId, String from, String to,
                          double progress) {}

  /** One planning run, kept for the dashboard; replaced = the optimised figures of the plan it superseded. */
  public record PlanRecord(String planId, long waveAtMs, long atMs, String trigger, String solver,
                           Payloads.PlanFigures baseline, Payloads.PlanFigures optimized, List<String> unassigned,
                           List<String> tourIds, Payloads.PlanFigures replaced) {}

  public record Kpis(int activeTours, double utilization, int delivered, int onTime, double avgDelayMinutes,
                     int failed, int open, double km, double cost) {}

  private final RouteStore store;
  private final EventBus bus;
  private final UnitOfWork work;
  private final SimTime time;
  private final IdSource ids;
  private final MaterialService materials;
  private final TransportService transports;
  private final ProductionEta eta;
  private final ProductCatalog catalog;
  private final RouteOptimizer optimizer;
  private final Object lock;
  private final List<PlanRecord> plans = new ArrayList<>(); // newest first
  private SeededRandom random;
  private double breakdownPerLeg = 0.01;
  private boolean replanNeeded;
  private String replanReason;

  public RouteMind(RouteStore store, EventBus bus, UnitOfWork work, SimTime time, IdSource ids,
                   MaterialService materials, TransportService transports, ProductionEta eta, ProductCatalog catalog,
                   RouteOptimizer optimizer, long seed) {
    this.store = store;
    this.bus = bus;
    this.work = work;
    this.time = time;
    this.ids = ids;
    this.materials = materials;
    this.transports = transports;
    this.eta = eta;
    this.catalog = catalog;
    this.optimizer = optimizer;
    this.lock = bus;
    this.random = new SeededRandom(seed).fork("routemind");
    bus.register(new EventConsumer() {
      @Override public String name() { return CONSUMER; }
      @Override public void on(NexusEvent e) { consume(e); }
    });
  }

  public static RouteMind inMemory(EventBus bus, MemoryUnitOfWork work, SimTime time, IdSource ids,
                                   MaterialService materials, TransportService transports, ProductionEta eta,
                                   ProductCatalog catalog, long seed) {
    return new RouteMind(new MemoryRouteStore(work), bus, work, time, ids, materials, transports, eta, catalog,
        new SavingsHeuristic(), seed);
  }

  /** The customer of an order's delivery: fixed per order id, spread over all customers. */
  public static String customerOf(String orderId) {
    List<Roads.Place> customers = Roads.customers();
    return customers.get(Math.floorMod(orderId.hashCode(), customers.size())).id();
  }

  /** Tests only: chance of a breakdown per leg (default 1 %). */
  void breakdownChance(double chance) {
    this.breakdownPerLeg = chance;
  }

  // ---- events: record facts only ---------------------------------------------------------------------------

  private void consume(NexusEvent e) {
    synchronized (lock) {
      switch (e.type()) {
        case EventTypes.ORDER_CREATED -> created((Payloads.OrderCreated) e.payload(), e.simTimeMs());
        case EventTypes.ORDER_RELEASED, EventTypes.ORDER_STARTED ->
            production(((Payloads.OrderStatusChanged) e.payload()).orderId());
        case EventTypes.ORDER_CANCELLED -> cancelled(((Payloads.OrderStatusChanged) e.payload()).orderId());
        case EventTypes.ORDER_COMPLETED -> completed(((Payloads.OrderStatusChanged) e.payload()).orderId());
        case EventTypes.ORDER_RESCHEDULED -> rescheduled(((Payloads.OrderRescheduled) e.payload()).orderId());
        case EventTypes.TRANSPORT_DELIVERED -> delivered((Payloads.TransportDelivered) e.payload());
        default -> { }
      }
    }
  }

  private void created(Payloads.OrderCreated o, long now) {
    if (store.deliveryOfOrder(o.orderId()).isPresent()) return;
    var freight = RouteMindMasterData.freight(o.productId());
    String customer = customerOf(o.orderId());
    long earliest = o.dueAtMs() - RouteMindMasterData.HOUR;  // window: one hour before due to three hours after
    long latest = o.dueAtMs() + 3 * RouteMindMasterData.HOUR;
    var d = new Delivery(ids.next("DL"), o.orderId(), customer, o.productId(), o.quantity(), 0,
        o.quantity() * freight.kgPerUnit(), RouteMindMasterData.pallets(freight, o.quantity()), o.priority(),
        earliest, latest, DeliveryStatus.CREATED, null, null, null, null, null, 0, now, 0);
    store.insertDelivery(d);
    bus.publish(EventTypes.DELIVERY_CREATED, SOURCE, Severity.INFO, o.orderId(), new Payloads.DeliveryCreated(d.id(),
        d.orderId(), customer, d.quantity(), d.weightKg(), d.pallets(), earliest, latest));
    if (o.priority() == Priority.EILIG) requestReplan("Expressauftrag " + o.orderId());
  }

  private void production(String orderId) {
    Delivery d = store.deliveryOfOrder(orderId).orElse(null);
    if (d != null && d.status() == DeliveryStatus.CREATED) move(d, DeliveryStatus.AWAITING_GOODS, "Produktion läuft");
  }

  private void cancelled(String orderId) {
    Delivery d = store.deliveryOfOrder(orderId).orElse(null);
    if (d == null || !DeliveryStatus.RULES.can(d.status(), DeliveryStatus.CANCELLED)) return;
    move(d, DeliveryStatus.CANCELLED, "Auftrag storniert");
    if (d.tourId() != null) requestReplan("Lieferung " + d.id() + " storniert");
  }

  /** SmartFactory completes an order with its putaway; without a good part there was none and nothing to ship. */
  private void completed(String orderId) {
    Delivery d = store.deliveryOfOrder(orderId).orElse(null);
    if (d == null || d.storedQuantity() > 0 || !DeliveryStatus.RULES.can(d.status(), DeliveryStatus.CANCELLED)) return;
    move(d, DeliveryStatus.CANCELLED, "Kein Gutteil, nichts zu liefern");
  }

  private void rescheduled(String orderId) {
    Delivery d = store.deliveryOfOrder(orderId).orElse(null);
    if (d == null || d.tourId() == null) return;
    Tour t = store.tour(d.tourId()).orElse(null);
    if (t != null && t.status() == TourStatus.PLANNED) requestReplan("Produktion von " + orderId + " verschoben");
  }

  private void delivered(Payloads.TransportDelivered t) {
    if (t.orderId() == null) return;
    Delivery d = store.deliveryOfOrder(t.orderId()).orElse(null);
    if (d == null) return;
    if (t.kind() == TransportKind.PUTAWAY && d.storedQuantity() == 0) {
      var f = RouteMindMasterData.freight(d.productId());
      store.updateDelivery(d.withGoods(t.quantity(), t.quantity() * f.kgPerUnit(),
          RouteMindMasterData.pallets(f, t.quantity())));
    } else if (t.kind() == TransportKind.SHIPMENT && t.transportId().equals(d.transportId())
        && d.status() == DeliveryStatus.AWAITING_GOODS) {
      move(d, DeliveryStatus.READY_TO_LOAD, "Bereitstellung an der Verladezone bestätigt");
    }
  }

  private void requestReplan(String reason) {
    replanNeeded = true;
    if (replanReason == null) replanReason = reason;
  }

  // ---- simulation step -------------------------------------------------------------------------------------

  /** One plant step: goods to the loading zone, waves (plan, load, depart), driving, repairs. */
  public void step() {
    synchronized (lock) {
      long now = time.nowMs();
      if (now % PROVISION_EVERY_MS == 0) provision();
      long intoWave = Math.floorMod(now, WAVE_MS);
      if (intoWave == 0) {
        depart(now);
        long next = now + WAVE_MS;
        if (inShift(next)) work.inNewTransaction(() -> plan(next, "Wellenplanung", null));
      } else if (intoWave == WAVE_MS - LOADING_LEAD_MS) {
        startLoading(now + LOADING_LEAD_MS);
      } else if (replanNeeded && intoWave < WAVE_MS - LOADING_LEAD_MS) {
        long wave = now - intoWave + WAVE_MS;
        String reason = replanReason;
        work.inNewTransaction(() -> replan(wave, reason));
      }
      if (intoWave >= WAVE_MS - LOADING_LEAD_MS && now % LOAD_EVERY_MS == 0) load();
      drive(now);
      repairs(now);
    }
  }

  private static boolean inShift(long wave) {
    return Math.floorMod(wave, RouteMindMasterData.DAY) < RouteMindMasterData.SHIFT_END;
  }

  /** Goods put away for a delivery: reserve them and order the transport to the loading zone (Integration 2). */
  private void provision() {
    for (Delivery d : store.deliveries()) {
      if (d.status() != DeliveryStatus.AWAITING_GOODS || d.storedQuantity() == 0 || d.transportId() != null) continue;
      work.inNewTransaction(() -> requestGoods(store.delivery(d.id()).orElseThrow()));
    }
  }

  private void requestGoods(Delivery d) {
    String material = catalog.finishedMaterialId(d.productId());
    var result = materials.reserve(new MaterialService.ReservationRequest(d.id() + "/R", d.orderId(),
        List.of(new MaterialService.Line(material, d.storedQuantity()))));
    if (!(result instanceof MaterialService.ReservationResult.Success ok)) return; // shortage: again in 10 s
    var r = ok.reservations().get(0);
    String transport = transports.requestTransport(new TransportService.TransportRequest(d.id() + "/V",
        TransportKind.SHIPMENT, d.orderId(), material, d.storedQuantity(), Place.storage(r.locationId()),
        Place.loading(LOADING_ZONE), d.priority(), r.reservationId()));
    store.updateDelivery(store.delivery(d.id()).orElseThrow().withShipment(d.id() + "/R", transport));
  }

  // ---- planning --------------------------------------------------------------------------------------------

  private void replan(long wave, String reason) {
    Payloads.PlanFigures before = null;
    for (PlanRecord p : plans) {
      if (p.waveAtMs() == wave) {
        before = p.optimized();
        break;
      }
    }
    for (Tour t : store.tours()) {
      if (t.departAtMs() != wave || t.status() != TourStatus.PLANNED) continue;
      moveTour(t, TourStatus.REPLANNED, reason);
      for (Tour.Stop s : t.stops()) release(s.deliveryId(), t.id());
    }
    plan(wave, "Neuplanung: " + reason, before);
  }

  /** Plans wave `wave`: ready goods and goods expected up to LAST_GOODS_MS before departure, on vehicles back by then. */
  private void plan(long wave, String trigger, Payloads.PlanFigures replaced) {
    replanNeeded = false;
    replanReason = null;
    long now = time.nowMs();
    for (Delivery d : store.deliveries()) {
      if (d.status() == DeliveryStatus.FAILED && d.failures() < MAX_ATTEMPTS) {
        Delivery back = move(d, DeliveryStatus.READY_TO_LOAD, "Ware zurück im Depot, Versuch " + (d.failures() + 1));
        store.updateDelivery(back.withTour(null, null));
      }
    }
    List<VehicleRecord> usable = new ArrayList<>();
    for (VehicleRecord v : store.vehicles()) {
      if (hasTourInWave(v.id(), wave)) continue;
      boolean back = v.status() == VehicleStatus.AVAILABLE
          || (v.status() == VehicleStatus.RETURNING && v.legEndMs() <= wave - LOADING_LEAD_MS);
      if (back) usable.add(v);
    }
    List<Delivery> candidates = new ArrayList<>();
    for (Delivery d : store.deliveries()) {
      if (d.tourId() != null && onOpenTour(d)) continue;
      if (d.status() == DeliveryStatus.READY_TO_LOAD
          || (d.status() == DeliveryStatus.AWAITING_GOODS && expectedReady(d, now) <= wave - LAST_GOODS_MS)) {
        candidates.add(d);
      }
    }
    if (candidates.isEmpty()) return;
    Problem p = problem(wave, usable, candidates);
    Solution base = new NearestNeighbour().solve(p);
    Solution best = optimizer.solve(p);
    String planId = ids.next("RP");
    List<String> tourIds = new ArrayList<>();
    for (Route r : best.routes()) {
      var f = Evaluation.route(p, p.truck(r.truckId()), Evaluation.jobs(p, r.jobIds()));
      String tourId = ids.next("TR");
      List<Tour.Stop> stops = new ArrayList<>();
      List<String> deliveryIds = new ArrayList<>();
      for (var st : f.stops()) {
        Delivery d = store.delivery(st.jobId()).orElseThrow();
        stops.add(new Tour.Stop(stops.size() + 1, d.id(), d.customerId(), st.startMs(), null));
        deliveryIds.add(d.id());
        store.updateDelivery(d.withTour(tourId, st.startMs()));
      }
      var tour = new Tour(tourId, planId, r.truckId(), wave, TourStatus.PLANNED, stops, f.meters() / 1000.0, f.cost(),
          null, null, 0);
      store.insertTour(tour);
      tourIds.add(tourId);
      bus.publish(EventTypes.TOUR_PLANNED, SOURCE, Severity.INFO, null,
          new Payloads.TourPlanned(tourId, r.truckId(), planId, wave, deliveryIds, tour.km(), tour.cost()));
    }
    List<String> open = new ArrayList<>();
    for (Unassigned u : best.unassigned()) open.add(u.jobId() + ": " + u.reason());
    var record = new PlanRecord(planId, wave, now, trigger, best.solver(), figures(Evaluation.of(p, base)),
        figures(Evaluation.of(p, best)), open, tourIds, replaced);
    plans.add(0, record);
    while (plans.size() > KEEP_PLANS) plans.remove(plans.size() - 1);
    bus.publish(EventTypes.DELIVERY_PLAN, SOURCE, open.isEmpty() ? Severity.INFO : Severity.WARNING, null,
        new Payloads.DeliveryPlanComputed(planId, best.solver(), wave, trigger, record.baseline(), record.optimized(),
            open));
  }

  private long expectedReady(Delivery d, long now) {
    if (d.storedQuantity() > 0) return now + READY_BUFFER_MS;
    long finish = eta.plannedFinishMs(d.orderId());
    return finish < 0 ? Long.MAX_VALUE : finish + READY_BUFFER_MS;
  }

  private Problem problem(long wave, List<VehicleRecord> vehicles, List<Delivery> deliveries) {
    long endMs = wave - Math.floorMod(wave, RouteMindMasterData.DAY) + RouteMindMasterData.SHIFT_END;
    List<Truck> trucks = new ArrayList<>();
    for (VehicleRecord v : vehicles) {
      var seed = RouteMindMasterData.vehicle(v.id());
      trucks.add(new Truck(v.id(), seed.maxKg(), seed.maxPallets(), seed.costPerKm(), seed.costPerHour(), endMs));
    }
    List<Job> jobs = new ArrayList<>();
    for (Delivery d : deliveries) {
      jobs.add(new Job(d.id(), Roads.place(d.customerId()).index(), d.weightKg(), d.pallets(), d.earliestMs(),
          d.latestMs(), SERVICE_MS));
    }
    return new Problem(wave, 0, trucks, jobs, ROADS);
  }

  private static Payloads.PlanFigures figures(Evaluation.Figures f) {
    return new Payloads.PlanFigures(f.km(), f.driveMinutes(), f.cost(), f.lateMinutes(), f.tours());
  }

  private boolean hasTourInWave(String vehicleId, long wave) {
    for (Tour t : store.tours()) {
      if (t.vehicleId().equals(vehicleId) && t.departAtMs() == wave && !t.status().terminal()) return true;
    }
    return false;
  }

  private boolean onOpenTour(Delivery d) {
    Tour t = store.tour(d.tourId()).orElse(null);
    return t != null && !t.status().terminal();
  }

  private void release(String deliveryId, String tourId) {
    Delivery d = store.delivery(deliveryId).orElseThrow();
    if (tourId.equals(d.tourId())) store.updateDelivery(d.withTour(null, null));
  }

  // ---- loading and departure -------------------------------------------------------------------------------

  private void startLoading(long wave) {
    for (Tour t : store.tours()) {
      if (t.departAtMs() != wave || t.status() != TourStatus.PLANNED) continue;
      VehicleRecord v = vehicle(t.vehicleId());
      if (v.status() != VehicleStatus.AVAILABLE) continue; // not back: the tour is dropped at departure
      work.inNewTransaction(() -> {
        moveTour(t, TourStatus.LOADING, "Verladung beginnt");
        moveVehicle(v, v.withTour(t.id()).withStatus(VehicleStatus.LOADING));
      });
    }
  }

  /** Confirms the loading of every delivery whose goods are at the loading zone (Integration 3). */
  private void load() {
    for (Tour t : store.tours()) {
      if (t.status() != TourStatus.LOADING) continue;
      for (Tour.Stop s : t.stops()) {
        Delivery d = store.delivery(s.deliveryId()).orElseThrow();
        if (d.status() == DeliveryStatus.READY_TO_LOAD && t.id().equals(d.tourId())) {
          work.inNewTransaction(() -> move(store.delivery(d.id()).orElseThrow(), DeliveryStatus.LOADED,
              "Verladung auf " + t.vehicleId() + " bestätigt"));
        }
      }
    }
  }

  private void depart(long wave) {
    for (Tour t : store.tours()) {
      if (t.departAtMs() != wave) continue;
      if (t.status() == TourStatus.PLANNED) {
        work.inNewTransaction(() -> drop(store.tour(t.id()).orElseThrow(),
            "Fahrzeug " + t.vehicleId() + " nicht rechtzeitig bereit"));
      } else if (t.status() == TourStatus.LOADING) {
        work.inNewTransaction(() -> start(store.tour(t.id()).orElseThrow()));
      }
    }
  }

  private void drop(Tour t, String reason) {
    moveTour(t, TourStatus.REPLANNED, reason);
    for (Tour.Stop s : t.stops()) release(s.deliveryId(), t.id());
    VehicleRecord v = vehicle(t.vehicleId());
    if (v.status() == VehicleStatus.LOADING && t.id().equals(v.tourId())) moveVehicle(v, v.back(VehicleStatus.AVAILABLE));
  }

  private void start(Tour t) {
    List<Tour.Stop> kept = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (Tour.Stop s : t.stops()) {
      Delivery d = store.delivery(s.deliveryId()).orElseThrow();
      if (d.status() == DeliveryStatus.LOADED && t.id().equals(d.tourId())) {
        kept.add(s);
      } else {
        missing.add(d.id());
        release(d.id(), t.id());
      }
    }
    if (kept.isEmpty()) {
      drop(t, "Keine Ware rechtzeitig an der Verladezone");
      return;
    }
    Tour tour = t;
    if (!missing.isEmpty()) {
      tour = store.updateTour(retimed(t, kept));
      bus.publish(EventTypes.DECISION, SOURCE, Severity.WARNING, null, new Payloads.Decision(t.id(),
          "ohne " + String.join(", ", missing), List.of(), "Ware nicht rechtzeitig verladen, die Tour fährt ohne sie"));
    }
    // hard rule (Spec 15a): a tour starts only when the loading of every one of its deliveries is confirmed
    for (Tour.Stop s : tour.stops()) {
      if (store.delivery(s.deliveryId()).orElseThrow().status() != DeliveryStatus.LOADED) {
        throw new IllegalStateException("Tour " + t.id() + " ohne bestätigte Verladung");
      }
    }
    long now = time.nowMs();
    tour = moveTour(tour.withStarted(now), TourStatus.EN_ROUTE, "Abfahrt");
    for (Tour.Stop s : tour.stops()) {
      move(store.delivery(s.deliveryId()).orElseThrow(), DeliveryStatus.IN_TRANSIT, "Unterwegs mit " + t.vehicleId());
    }
    VehicleRecord v = vehicle(t.vehicleId());
    moveVehicle(v, leg(v.withStatus(VehicleStatus.EN_ROUTE), tour, 0, now));
  }

  /** The same stops in the same order without the missing ones: new arrival times, distance and cost. */
  private Tour retimed(Tour t, List<Tour.Stop> kept) {
    List<Delivery> ds = new ArrayList<>();
    for (Tour.Stop s : kept) ds.add(store.delivery(s.deliveryId()).orElseThrow());
    Problem p = problem(t.departAtMs(), List.of(vehicle(t.vehicleId())), ds);
    var f = Evaluation.route(p, p.trucks().get(0), p.jobs());
    List<Tour.Stop> stops = new ArrayList<>();
    for (int i = 0; i < kept.size(); i++) {
      Tour.Stop s = kept.get(i);
      stops.add(new Tour.Stop(i + 1, s.deliveryId(), s.customerId(), f.stops().get(i).startMs(), null));
    }
    return t.withStops(stops, f.meters() / 1000.0, f.cost());
  }

  // ---- driving ---------------------------------------------------------------------------------------------

  /** Leg index of the tour from startMs: 0..30 % longer than OSRM for traffic, and perhaps a breakdown half-way. */
  private VehicleRecord leg(VehicleRecord v, Tour tour, int index, long startMs) {
    int from = index == 0 ? 0 : Roads.place(tour.stops().get(index - 1).customerId()).index();
    int to = index == tour.stops().size() ? 0 : Roads.place(tour.stops().get(index).customerId()).index();
    double traffic = 1.0 + 0.3 * random.nextDouble();
    long end = startMs + Math.round(Roads.seconds(from, to) * 1000.0 * traffic);
    Long breakdown = random.nextDouble() < breakdownPerLeg ? startMs + (end - startMs) / 2 : null;
    return v.withLeg(index, startMs, end, breakdown);
  }

  private void drive(long now) {
    for (VehicleRecord v : store.vehicles()) {
      if (v.status() != VehicleStatus.EN_ROUTE && v.status() != VehicleStatus.RETURNING) continue;
      if (v.breakdownAtMs() != null && now >= v.breakdownAtMs()) {
        work.inNewTransaction(() -> breakDown(vehicle(v.id()), "Panne unterwegs"));
      } else if (now >= v.legEndMs()) {
        work.inNewTransaction(() -> arrive(vehicle(v.id())));
      }
    }
  }

  private void arrive(VehicleRecord v) {
    Tour t = store.tour(v.tourId()).orElseThrow();
    long at = v.legEndMs();
    if (v.leg() == t.stops().size()) {
      moveTour(t.withEnded(at), TourStatus.COMPLETED, "Zurück im Depot");
      moveVehicle(v, v.back(VehicleStatus.AVAILABLE));
      return;
    }
    Tour.Stop s = t.stops().get(v.leg());
    Delivery d = store.delivery(s.deliveryId()).orElseThrow();
    long handover = Math.max(at, d.earliestMs());
    t = store.updateTour(t.withArrival(s.seq(), at));
    long lateMin = Math.max(0, handover - d.latestMs()) / MIN;
    Delivery done = move(d, DeliveryStatus.DELIVERED, lateMin > 0 ? "Zugestellt, " + lateMin + " min zu spät" : "Zugestellt");
    store.updateDelivery(done.withDelivered(handover));
    int next = v.leg() + 1;
    VehicleRecord moved = leg(v, t, next, handover + SERVICE_MS);
    moveVehicle(v, next == t.stops().size() ? moved.withStatus(VehicleStatus.RETURNING) : moved);
  }

  private void breakDown(VehicleRecord v, String reason) {
    long now = time.nowMs();
    moveVehicle(v, v.withBreakdown(now, now + REPAIR_MS));
    Tour running = v.tourId() == null ? null : store.tour(v.tourId()).orElse(null);
    if (running != null && running.status() == TourStatus.EN_ROUTE) {
      moveTour(running.withEnded(now), TourStatus.INTERRUPTED, reason + ": " + v.id());
      for (Tour.Stop s : running.stops()) {
        Delivery d = store.delivery(s.deliveryId()).orElseThrow();
        if (d.status() != DeliveryStatus.IN_TRANSIT) continue; // delivered stays delivered
        Delivery failed = move(d, DeliveryStatus.FAILED,
            reason + ", Zustellversuch " + (d.failures() + 1) + " gescheitert");
        failed = store.updateDelivery(failed.withFailure());
        if (failed.failures() >= MAX_ATTEMPTS) {
          bus.publish(EventTypes.DECISION, SOURCE, Severity.WARNING, d.orderId(), new Payloads.Decision(d.id(),
              "aufgegeben", List.of(), "Nach " + MAX_ATTEMPTS + " Versuchen keine weitere Zustellung"));
        }
      }
    }
    for (Tour t : store.tours()) {
      if (t.vehicleId().equals(v.id()) && t.status() == TourStatus.PLANNED) requestReplan("Ausfall " + v.id());
    }
  }

  private void repairs(long now) {
    for (VehicleRecord v : store.vehicles()) {
      if (v.status() == VehicleStatus.BROKEN_DOWN && v.readyAtMs() <= now) {
        work.inNewTransaction(() -> {
          VehicleRecord current = vehicle(v.id());
          moveVehicle(current, current.back(VehicleStatus.AVAILABLE));
        });
      }
    }
  }

  // ---- commands --------------------------------------------------------------------------------------------

  /** A breakdown now (Scenario Lab, UI); not while the vehicle is being loaded. */
  public void breakDown(String vehicleId) {
    synchronized (lock) {
      VehicleRecord v = vehicle(vehicleId);
      work.inNewTransaction(() -> breakDown(v, "Ausfall ausgelöst"));
    }
  }

  /** A broken-down vehicle is back in service at the depot at once. */
  public void repair(String vehicleId) {
    synchronized (lock) {
      VehicleRecord v = vehicle(vehicleId);
      if (v.status() != VehicleStatus.BROKEN_DOWN) {
        throw new IllegalTransitionException(v.id(), v.status(), VehicleStatus.AVAILABLE); // nothing to repair
      }
      work.inNewTransaction(() -> moveVehicle(v, v.back(VehicleStatus.AVAILABLE)));
    }
  }

  public void setMaintenance(String vehicleId, boolean on) {
    synchronized (lock) {
      VehicleRecord v = vehicle(vehicleId);
      work.inNewTransaction(() -> {
        if (on) {
          moveVehicle(v, v.withStatus(VehicleStatus.MAINTENANCE));
          for (Tour t : store.tours()) {
            if (t.vehicleId().equals(vehicleId) && t.status() == TourStatus.PLANNED) requestReplan("Wartung " + vehicleId);
          }
        } else {
          if (v.status() != VehicleStatus.MAINTENANCE) {
            throw new IllegalTransitionException(v.id(), v.status(), VehicleStatus.AVAILABLE); // not in maintenance
          }
          moveVehicle(v, v.back(VehicleStatus.AVAILABLE));
        }
      });
    }
  }

  /** Plans the next wave again in the next step, if its tours are still PLANNED. */
  public void replan() {
    synchronized (lock) {
      requestReplan("Neuplanung angefordert");
    }
  }

  /** After the server reset its tables: plans, random generator and pending replanning start afresh. */
  public void reset(long seed) {
    synchronized (lock) {
      plans.clear();
      replanNeeded = false;
      replanReason = null;
      random = new SeededRandom(seed).fork("routemind");
    }
  }

  // ---- queries ---------------------------------------------------------------------------------------------

  public List<Delivery> deliveries() {
    synchronized (lock) {
      return store.deliveries();
    }
  }

  public Delivery deliveryOfOrder(String orderId) {
    synchronized (lock) {
      return store.deliveryOfOrder(orderId).orElseThrow(() -> DomainException.notFound("Lieferauftrag zu", orderId));
    }
  }

  public List<Tour> tours() {
    synchronized (lock) {
      return store.tours();
    }
  }

  public List<PlanRecord> plans() {
    synchronized (lock) {
      return new ArrayList<>(plans);
    }
  }

  /** Deliveries whose goods wait at the loading zone or are loaded on a vehicle that has not left. */
  public List<Delivery> loadingZone() {
    synchronized (lock) {
      List<Delivery> out = new ArrayList<>();
      for (Delivery d : store.deliveries()) {
        if (d.status() == DeliveryStatus.READY_TO_LOAD || d.status() == DeliveryStatus.LOADED) out.add(d);
      }
      return out;
    }
  }

  public List<TruckView> trucks() {
    synchronized (lock) {
      long now = time.nowMs();
      List<TruckView> out = new ArrayList<>();
      for (VehicleRecord v : store.vehicles()) {
        String from = Roads.DEPOT;
        String to = Roads.DEPOT;
        double progress = 0;
        boolean onTheRoad = v.status() == VehicleStatus.EN_ROUTE || v.status() == VehicleStatus.RETURNING
            || (v.status() == VehicleStatus.BROKEN_DOWN && v.tourId() != null && v.legEndMs() > 0);
        if (onTheRoad) {
          Tour t = store.tour(v.tourId()).orElseThrow();
          from = v.leg() == 0 ? Roads.DEPOT : t.stops().get(v.leg() - 1).customerId();
          to = v.leg() == t.stops().size() ? Roads.DEPOT : t.stops().get(v.leg()).customerId();
          long at = v.status() == VehicleStatus.BROKEN_DOWN ? v.breakdownAtMs() : now;
          long span = v.legEndMs() - v.legStartMs();
          progress = span <= 0 ? 1 : Math.max(0, Math.min(1, (at - v.legStartMs()) / (double) span));
        }
        out.add(new TruckView(v.id(), RouteMindMasterData.vehicle(v.id()).name(), v.status(), v.tourId(), from, to,
            progress));
      }
      return out;
    }
  }

  public Kpis kpis() {
    synchronized (lock) {
      int active = 0;
      double km = 0;
      double cost = 0;
      for (Tour t : store.tours()) {
        if (t.status() == TourStatus.LOADING || t.status() == TourStatus.EN_ROUTE) active++;
        if (t.status() == TourStatus.EN_ROUTE || t.status() == TourStatus.COMPLETED
            || t.status() == TourStatus.INTERRUPTED) {
          km += t.km();
          cost += t.cost();
        }
      }
      int busy = 0;
      List<VehicleRecord> vehicles = store.vehicles();
      for (VehicleRecord v : vehicles) {
        if (v.status() == VehicleStatus.LOADING || v.status() == VehicleStatus.EN_ROUTE
            || v.status() == VehicleStatus.RETURNING) busy++;
      }
      int delivered = 0;
      int onTime = 0;
      int failed = 0;
      int open = 0;
      long delay = 0;
      for (Delivery d : store.deliveries()) {
        if (d.status() == DeliveryStatus.DELIVERED) {
          delivered++;
          long late = Math.max(0, d.deliveredAtMs() - d.latestMs());
          if (late == 0) onTime++;
          delay += late;
        } else if (d.status() == DeliveryStatus.FAILED) {
          failed++;
        } else if (!d.status().terminal()) {
          open++;
        }
      }
      return new Kpis(active, vehicles.isEmpty() ? 0 : busy / (double) vehicles.size(), delivered, onTime,
          delivered == 0 ? 0 : delay / 60_000.0 / delivered, failed, open, km, cost);
    }
  }

  // ---- state changes with their events ---------------------------------------------------------------------

  private Delivery move(Delivery d, DeliveryStatus to, String reason) {
    DeliveryStatus.RULES.move(d.id(), d.status(), to);
    Delivery saved = store.updateDelivery(d.withStatus(to));
    Severity severity = to == DeliveryStatus.DELIVERED ? Severity.SUCCESS
        : to == DeliveryStatus.FAILED ? Severity.WARNING : Severity.INFO;
    bus.publish(EventTypes.DELIVERY_STATE, SOURCE, severity, d.orderId(),
        new Payloads.DeliveryStateChanged(d.id(), d.status(), to, d.tourId(), reason));
    return saved;
  }

  private Tour moveTour(Tour t, TourStatus to, String reason) {
    TourStatus.RULES.move(t.id(), t.status(), to);
    Tour saved = store.updateTour(t.withStatus(to));
    Severity severity = to == TourStatus.INTERRUPTED ? Severity.WARNING
        : to == TourStatus.COMPLETED ? Severity.SUCCESS : Severity.INFO;
    bus.publish(EventTypes.TOUR_STATE, SOURCE, severity, null, new Payloads.TourStateChanged(t.id(), t.status(), to, reason));
    return saved;
  }

  /** Saves next (derived from current); a changed state is checked against the rules and reported. */
  private VehicleRecord moveVehicle(VehicleRecord current, VehicleRecord next) {
    if (current.status() != next.status()) VehicleStatus.RULES.move(current.id(), current.status(), next.status());
    VehicleRecord saved = store.updateVehicle(next);
    if (current.status() != next.status()) {
      bus.publish(EventTypes.VEHICLE_STATE, SOURCE,
          next.status() == VehicleStatus.BROKEN_DOWN ? Severity.WARNING : Severity.INFO, null,
          new Payloads.VehicleStateChanged(current.id(), current.status(), next.status()));
    }
    return saved;
  }

  private VehicleRecord vehicle(String id) {
    for (VehicleRecord v : store.vehicles()) if (v.id().equals(id)) return v;
    throw DomainException.notFound("Lieferfahrzeug", id);
  }
}
