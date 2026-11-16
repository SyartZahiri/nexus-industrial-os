package io.nexus.routemind;

import static io.nexus.routemind.Fixture.MIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.contracts.state.TourStatus;
import io.nexus.contracts.state.VehicleStatus;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.Tour;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteMindTest {
  private static Tour tourOf(Fixture f, Delivery d) {
    return f.rm.tours().stream().filter(t -> t.id().equals(d.tourId())).findFirst().orElseThrow();
  }

  @Test
  void orderCreatesADeliveryForACustomerWithWindowAndFreight() {
    var f = new Fixture(1);
    f.order("PO-0001", "GH200", 4, Priority.NORMAL, 90 * MIN);
    Delivery d = f.rm.deliveryOfOrder("PO-0001");
    assertThat(d.status()).isEqualTo(DeliveryStatus.AWAITING_GOODS);
    assertThat(d.customerId()).isEqualTo(RouteMind.customerOf("PO-0001")).startsWith("K");
    assertThat(d.weightKg()).isEqualTo(72);
    assertThat(d.pallets()).isEqualTo(1);
    assertThat(d.earliestMs()).isEqualTo(30 * MIN);
    assertThat(d.latestMs()).isEqualTo(270 * MIN);
    NexusEvent created = f.events(EventTypes.DELIVERY_CREATED).get(0);
    assertThat(created.correlationId()).isEqualTo("PO-0001");
  }

  @Test
  void storedGoodsAreReservedAndSentToTheLoadingZone() {
    var f = new Fixture(1);
    f.order("PO-0001", "AW50", 6, Priority.HOCH, 90 * MIN);
    f.putAway("PO-0001", 5);
    f.advance(10_000);
    assertThat(f.shipments).hasSize(1);
    var r = f.shipments.get(0);
    assertThat(r.kind()).isEqualTo(TransportKind.SHIPMENT);
    assertThat(r.from()).isEqualTo(Place.storage("FG-A"));
    assertThat(r.to()).isEqualTo(Place.loading("LZ-1"));
    assertThat(r.quantity()).isEqualTo(5);
    assertThat(r.materialId()).isEqualTo("FG-AW50");
    assertThat(r.reservationId()).isEqualTo("RES-DL-0001/R");
    assertThat(f.rm.deliveryOfOrder("PO-0001").weightKg()).isEqualTo(45); // the 5 good parts, not the 6 ordered
    f.provide("PO-0001");
    assertThat(f.rm.deliveryOfOrder("PO-0001").status()).isEqualTo(DeliveryStatus.READY_TO_LOAD);
    f.advance(20_000);
    assertThat(f.shipments).hasSize(1); // requested once
  }

  @Test
  void waveIsPlannedLoadedStartedAndDelivered() {
    var f = new Fixture(1);
    f.rm.breakdownChance(0);
    f.ready("PO-0001", "GH200", 4, 60 * MIN);
    f.ready("PO-0002", "FP12", 10, 60 * MIN);
    f.advanceTo(30 * MIN + 100);
    Delivery d1 = f.rm.deliveryOfOrder("PO-0001");
    assertThat(d1.tourId()).isNotNull();
    assertThat(tourOf(f, d1).status()).isEqualTo(TourStatus.PLANNED);
    assertThat(tourOf(f, d1).departAtMs()).isEqualTo(60 * MIN);
    var plan = (Payloads.DeliveryPlanComputed) f.events(EventTypes.DELIVERY_PLAN).get(0).payload();
    assertThat(plan.solver()).isEqualTo(SavingsHeuristic.NAME);
    assertThat(plan.optimized().cost()).isLessThanOrEqualTo(plan.baseline().cost() + 1e-6);
    assertThat(plan.unassigned()).isEmpty();

    f.advanceTo(45 * MIN + 1_100);
    assertThat(tourOf(f, f.rm.deliveryOfOrder("PO-0001")).status()).isEqualTo(TourStatus.LOADING);
    assertThat(f.rm.deliveryOfOrder("PO-0001").status()).isEqualTo(DeliveryStatus.LOADED);
    assertThat(f.rm.deliveryOfOrder("PO-0002").status()).isEqualTo(DeliveryStatus.LOADED);

    f.advanceTo(60 * MIN + 100);
    assertThat(f.rm.deliveryOfOrder("PO-0001").status()).isEqualTo(DeliveryStatus.IN_TRANSIT);
    assertThat(tourOf(f, f.rm.deliveryOfOrder("PO-0001")).status()).isEqualTo(TourStatus.EN_ROUTE);

    f.advanceTo(6 * 60 * MIN);
    for (String o : List.of("PO-0001", "PO-0002")) {
      Delivery d = f.rm.deliveryOfOrder(o);
      assertThat(d.status()).isEqualTo(DeliveryStatus.DELIVERED);
      assertThat(d.deliveredAtMs()).isNotNull();
      assertThat(tourOf(f, d).status()).isEqualTo(TourStatus.COMPLETED);
    }
    assertThat(f.rm.trucks()).allSatisfy(t -> assertThat(t.status()).isEqualTo(VehicleStatus.AVAILABLE));
    assertThat(f.rm.kpis().delivered()).isEqualTo(2);
  }

  @Test
  void noTourStartsWithoutConfirmedLoading() {
    var f = new Fixture(1);
    f.rm.breakdownChance(0);
    f.ready("PO-0001", "GH200", 2, 60 * MIN);
    f.order("PO-0002", "GH200", 2, Priority.NORMAL, 60 * MIN);
    f.etas.put("PO-0002", 20 * MIN); // expected ready 30 min, before loading starts: planned, but never arrives
    f.advanceTo(30 * MIN + 100);
    assertThat(f.rm.deliveryOfOrder("PO-0002").tourId()).as("planned on its expected completion").isNotNull();
    f.advanceTo(60 * MIN + 100);
    Delivery late = f.rm.deliveryOfOrder("PO-0002");
    Delivery onBoard = f.rm.deliveryOfOrder("PO-0001");
    assertThat(late.status()).isEqualTo(DeliveryStatus.AWAITING_GOODS);
    assertThat(late.tourId()).as("not on the tour that left; perhaps planned into the next wave")
        .isNotEqualTo(onBoard.tourId());
    assertThat(onBoard.status()).isEqualTo(DeliveryStatus.IN_TRANSIT);
    assertThat(tourOf(f, onBoard).stops()).extracting(Tour.Stop::deliveryId).containsExactly(onBoard.id());
  }

  @Test
  void tourWithoutAnyGoodsIsDroppedAtDeparture() {
    var f = new Fixture(1);
    f.order("PO-0001", "GH200", 2, Priority.NORMAL, 60 * MIN);
    f.etas.put("PO-0001", 20 * MIN);
    f.advanceTo(60 * MIN + 100);
    Tour t = f.rm.tours().get(0);
    assertThat(t.status()).isEqualTo(TourStatus.REPLANNED);
    assertThat(f.rm.deliveryOfOrder("PO-0001").tourId()).as("planned into the next wave at most")
        .isNotEqualTo(t.id());
    assertThat(f.rm.trucks()).allSatisfy(v -> assertThat(v.status()).isEqualTo(VehicleStatus.AVAILABLE));
  }

  @Test
  void breakdownKeepsDeliveredStopsAndRetriesTheRest() {
    var f = new Fixture(1);
    f.rm.breakdownChance(0);
    // three customers close to the plant: one tour serves all of them
    String a = Fixture.orderFor("K06", 1);
    String b = Fixture.orderFor("K01", 1);
    String c = Fixture.orderFor("K30", 1);
    for (String o : List.of(a, b, c)) f.ready(o, "FP12", 4, 60 * MIN);
    f.advanceTo(60 * MIN + 100);
    Tour tour = tourOf(f, f.rm.deliveryOfOrder(a));
    assertThat(tour.stops()).as("one tour for the three").hasSize(3);
    while (f.rm.deliveries().stream().noneMatch(d -> d.status() == DeliveryStatus.DELIVERED)) f.advance(1_000);
    Delivery first = f.rm.deliveries().stream().filter(d -> d.status() == DeliveryStatus.DELIVERED).findFirst().orElseThrow();
    Long arrived = tourOf(f, first).stops().stream().filter(s -> s.deliveryId().equals(first.id())).findFirst()
        .orElseThrow().arrivedAtMs();

    f.rm.breakDown(tour.vehicleId());
    f.advance(1_000);
    assertThat(tourOf(f, first).status()).isEqualTo(TourStatus.INTERRUPTED);
    Delivery again = f.rm.deliveryOfOrder(first.orderId());
    assertThat(again.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(again.deliveredAtMs()).isEqualTo(first.deliveredAtMs());
    assertThat(tourOf(f, again).stops().stream().filter(s -> s.deliveryId().equals(first.id())).findFirst()
        .orElseThrow().arrivedAtMs()).isEqualTo(arrived);
    List<Delivery> rest = f.rm.deliveries().stream().filter(d -> d != null && !d.id().equals(first.id())).toList();
    assertThat(rest).allSatisfy(d -> {
      // the breakdown replans the open stops at once, so they may already be back in READY_TO_LOAD
      assertThat(d.status()).isIn(DeliveryStatus.FAILED, DeliveryStatus.READY_TO_LOAD);
      assertThat(d.failures()).isEqualTo(1);
    });
    assertThat(f.events(EventTypes.DELIVERY_STATE)).anySatisfy(e ->
        assertThat(((Payloads.DeliveryStateChanged) e.payload()).to()).isEqualTo(DeliveryStatus.FAILED));

    long nextPlan = (f.time.nowMs() / RouteMind.WAVE_MS + 1) * RouteMind.WAVE_MS;
    f.advanceTo(nextPlan + 100);
    for (Delivery d : rest) {
      Delivery now = f.rm.deliveryOfOrder(d.orderId());
      assertThat(now.status()).isIn(DeliveryStatus.READY_TO_LOAD, DeliveryStatus.LOADED, DeliveryStatus.IN_TRANSIT,
          DeliveryStatus.DELIVERED);
      assertThat(now.tourId()).as("planned again on a new tour").isNotNull().isNotEqualTo(tour.id());
    }
    assertThat(f.rm.deliveryOfOrder(first.orderId()).status()).isEqualTo(DeliveryStatus.DELIVERED);
  }

  @Test
  void deliveryIsGivenUpAfterThreeFailedAttempts() {
    var f = new Fixture(1);
    f.rm.breakdownChance(0);
    f.ready("PO-0001", "GH200", 2, 60 * MIN);
    for (int attempt = 1; attempt <= RouteMind.MAX_ATTEMPTS; attempt++) {
      while (f.rm.deliveryOfOrder("PO-0001").status() != DeliveryStatus.IN_TRANSIT) f.advance(10_000);
      String vehicle = tourOf(f, f.rm.deliveryOfOrder("PO-0001")).vehicleId();
      f.rm.breakDown(vehicle);
      f.rm.repair(vehicle);
      assertThat(f.rm.deliveryOfOrder("PO-0001").failures()).isEqualTo(attempt);
    }
    f.advance(2 * RouteMind.WAVE_MS);
    Delivery d = f.rm.deliveryOfOrder("PO-0001");
    assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(f.events(EventTypes.DECISION)).anySatisfy(e ->
        assertThat(((Payloads.Decision) e.payload()).chosen()).isEqualTo("aufgegeben"));
  }

  @Test
  void vehicleCommands() {
    var f = new Fixture(1);
    f.rm.setMaintenance("LW-1", true);
    assertThat(f.rm.trucks().stream().filter(t -> t.id().equals("LW-1")).findFirst().orElseThrow().status())
        .isEqualTo(VehicleStatus.MAINTENANCE);
    assertThatThrownBy(() -> f.rm.breakDown("LW-1")).isInstanceOf(IllegalTransitionException.class);
    f.rm.setMaintenance("LW-1", false);
    assertThatThrownBy(() -> f.rm.repair("LW-1")).isInstanceOf(IllegalTransitionException.class);
    assertThatThrownBy(() -> f.rm.breakDown("LKW-9")).isInstanceOf(DomainException.class);

    f.ready("PO-0001", "GH200", 2, 60 * MIN);
    f.advanceTo(45 * MIN + 1_100);
    String loading = tourOf(f, f.rm.deliveryOfOrder("PO-0001")).vehicleId();
    assertThatThrownBy(() -> f.rm.breakDown(loading)).isInstanceOf(IllegalTransitionException.class);
  }

  @Test
  void cancelledOrderAndOrderWithoutGoodPartsCancelTheirDelivery() {
    var f = new Fixture(1);
    f.order("PO-0001", "GH200", 2, Priority.NORMAL, 60 * MIN);
    f.emit(EventTypes.ORDER_CANCELLED, "PO-0001",
        new Payloads.OrderStatusChanged("PO-0001", OrderStatus.RELEASED, OrderStatus.CANCELLED, "storniert"));
    assertThat(f.rm.deliveryOfOrder("PO-0001").status()).isEqualTo(DeliveryStatus.CANCELLED);
    f.order("PO-0002", "GH200", 2, Priority.NORMAL, 60 * MIN);
    f.emit(EventTypes.ORDER_COMPLETED, "PO-0002",
        new Payloads.OrderStatusChanged("PO-0002", OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED, "0 Gutteile"));
    assertThat(f.rm.deliveryOfOrder("PO-0002").status()).isEqualTo(DeliveryStatus.CANCELLED);
  }

  @Test
  void hardRulesHoldOverALongRandomRun() {
    var f = new Fixture(42);
    f.rm.breakdownChance(0.2);
    Map<String, Long> deliveredAt = new HashMap<>();
    Map<String, Long> arrivedAt = new HashMap<>();
    randomRun(f, 42, 12 * 60 * MIN, () -> {
      for (Tour t : f.rm.tours()) {
        if (t.status() == TourStatus.REPLANNED) continue;
        var seed = RouteMindMasterData.vehicle(t.vehicleId());
        int kg = 0;
        int pallets = 0;
        for (Tour.Stop s : t.stops()) {
          Delivery d = f.rm.deliveries().stream().filter(x -> x.id().equals(s.deliveryId())).findFirst().orElseThrow();
          kg += d.weightKg();
          pallets += d.pallets();
          if (t.status() == TourStatus.EN_ROUTE) {
            assertThat(d.status()).as("on a running tour " + t.id()).isIn(DeliveryStatus.IN_TRANSIT, DeliveryStatus.DELIVERED);
          }
          if (s.arrivedAtMs() != null) {
            Long before = arrivedAt.putIfAbsent(t.id() + "/" + s.seq(), s.arrivedAtMs());
            assertThat(before == null || before.equals(s.arrivedAtMs())).as("visited stop unchanged").isTrue();
          }
        }
        assertThat(kg).as("weight on " + t.id()).isLessThanOrEqualTo(seed.maxKg());
        assertThat(pallets).as("pallets on " + t.id()).isLessThanOrEqualTo(seed.maxPallets());
      }
      for (Delivery d : f.rm.deliveries()) {
        if (deliveredAt.containsKey(d.id())) {
          assertThat(d.status()).as("delivered stays delivered").isEqualTo(DeliveryStatus.DELIVERED);
          assertThat(d.deliveredAtMs()).isEqualTo(deliveredAt.get(d.id()));
        } else if (d.status() == DeliveryStatus.DELIVERED) {
          deliveredAt.put(d.id(), d.deliveredAtMs());
        }
      }
    });
    assertThat(deliveredAt).as("deliveries happened").hasSizeGreaterThan(20);
    assertThat(f.events(EventTypes.TOUR_STATE)).as("breakdowns happened").anySatisfy(e ->
        assertThat(((Payloads.TourStateChanged) e.payload()).to()).isEqualTo(TourStatus.INTERRUPTED));
  }

  /** Across the night (no waves 22:00–06:00) no delivery starves: by the evening of day 2 none of day 1 still waits. */
  @Test
  void noDeliveryOfTheFirstDayIsLeftWaiting() {
    var f = new Fixture(11);
    // an order every 15 min, about what the plant produces (PlantLongRunTest), also through the night
    randomRun(f, 11, 38 * 60 * MIN, 15 * MIN, () -> {}); // 06:00 day 1 to 20:00 day 2
    for (Delivery d : f.rm.deliveries()) {
      if (d.createdAtMs() >= 24 * 60 * MIN) continue; // orders of day 2 may still be on their way
      boolean givenUp = d.status() == DeliveryStatus.FAILED && d.failures() >= RouteMind.MAX_ATTEMPTS;
      boolean underWay = d.status() == DeliveryStatus.LOADED || d.status() == DeliveryStatus.IN_TRANSIT;
      assertThat(d.status() == DeliveryStatus.DELIVERED || d.status() == DeliveryStatus.CANCELLED || givenUp || underWay)
          .as("no delivery of day 1 still waits: " + d.id() + " created " + d.createdAtMs() / MIN + " min, " + d.status()).isTrue();
    }
  }

  @Test
  void sameSeedSameEvents() {
    List<String> first = new ArrayList<>();
    List<String> second = new ArrayList<>();
    for (List<String> out : List.of(first, second)) {
      var f = new Fixture(7);
      randomRun(f, 7, 4 * 60 * MIN, () -> {});
      for (NexusEvent e : f.log.all()) out.add(e.simTimeMs() + " " + e.type() + " " + e.payload());
    }
    assertThat(second).isEqualTo(first);
  }

  /** Orders every 6 min (or orderEveryMs); putaway after 20..50 min; FlowPilot provides 2 min after the request. */
  private static void randomRun(Fixture f, long seed, long untilMs, Runnable check) {
    randomRun(f, seed, untilMs, 6 * MIN, check);
  }

  private static void randomRun(Fixture f, long seed, long untilMs, long orderEveryMs, Runnable check) {
    var r = new SeededRandom(seed);
    String[] products = {"GH200", "AW50", "FP12", "MT7"};
    record Pending(long atMs, String orderId, int qty, boolean provide) {}
    List<Pending> pending = new ArrayList<>();
    int n = 0;
    while (f.time.nowMs() < untilMs) {
      long now = f.time.nowMs();
      if (now % orderEveryMs == 0) {
        String id = String.format("PO-%04d", ++n);
        int qty = 3 + r.nextInt(8);
        f.order(id, products[r.nextInt(4)], qty, Priority.NORMAL, now + (45 + r.nextInt(46)) * MIN);
        long done = now + (20 + r.nextInt(31)) * MIN;
        f.etas.put(id, done);
        pending.add(new Pending(done, id, qty, false));
      }
      List<Pending> due = new ArrayList<>();
      for (Pending p : pending) if (p.atMs() <= now) due.add(p);
      pending.removeAll(due);
      for (Pending p : due) {
        if (!p.provide()) {
          f.putAway(p.orderId(), p.qty());
          pending.add(new Pending(now + 2 * MIN, p.orderId(), p.qty(), true));
        } else if (f.rm.deliveryOfOrder(p.orderId()).transportId() != null) {
          f.provide(p.orderId());
        } else {
          pending.add(new Pending(now + MIN, p.orderId(), p.qty(), true));
        }
      }
      f.advance(100);
      if (now % 10_000 == 0) check.run();
    }
  }
}
