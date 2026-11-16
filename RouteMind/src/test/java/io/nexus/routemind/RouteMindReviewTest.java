package io.nexus.routemind;

import static io.nexus.routemind.Fixture.MIN;
import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.TourStatus;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.optimize.Problem;
import io.nexus.routemind.optimize.Route;
import io.nexus.routemind.optimize.Solution;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.RouteStore;
import io.nexus.routemind.store.Tour;
import io.nexus.routemind.store.VehicleRecord;
import io.nexus.routemind.store.memory.MemoryRouteStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Findings of the final review: every delivery gets an outcome, hard rules in the core, waves that heal. */
class RouteMindReviewTest {
  private static boolean planMentions(Fixture f, String text) {
    for (NexusEvent e : f.events(EventTypes.DELIVERY_PLAN)) {
      for (String u : ((Payloads.DeliveryPlanComputed) e.payload()).unassigned()) if (u.contains(text)) return true;
    }
    return false;
  }

  /** Goods ready after the customer's window closed are delivered late, not left waiting for good. */
  @Test
  void goodsPastTheirWindowAreDeliveredLate() {
    var f = new Fixture(1);
    f.rm.breakdownChance(0);
    f.order("PO-0001", "GH200", 2, Priority.NORMAL, 0); // window -1 h .. +3 h
    f.advanceTo(4 * 60 * MIN);
    f.putAway("PO-0001", 2);
    f.advance(10_000);
    f.provide("PO-0001");
    f.advanceTo(8 * 60 * MIN);
    Delivery d = f.rm.deliveryOfOrder("PO-0001");
    assertThat(d.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(d.deliveredAtMs()).isGreaterThan(d.latestMs());
    assertThat(planMentions(f, "Zeitfenster nicht erreichbar")).as("the plan says why it is late").isTrue();
  }

  /** The largest order SmartFactory accepts (100 units) fits the largest vehicle for every product. */
  @Test
  void theLargestOrderOfEveryProductFitsTheLargestVehicle() {
    int maxKg = 0;
    int maxPallets = 0;
    for (var v : RouteMindMasterData.vehicles()) {
      maxKg = Math.max(maxKg, v.maxKg());
      maxPallets = Math.max(maxPallets, v.maxPallets());
    }
    for (String product : List.of("GH200", "AW50", "FP12", "MT7")) {
      var freight = RouteMindMasterData.freight(product);
      assertThat(100 * freight.kgPerUnit()).as(product + " kg").isLessThanOrEqualTo(maxKg);
      assertThat(RouteMindMasterData.pallets(freight, 100)).as(product + " pallets").isLessThanOrEqualTo(maxPallets);
    }
  }

  /** A solver whose route breaks capacity does not get it into the plan: the core checks every route. */
  @Test
  void routesThatBreakAHardRuleAreNotPlanned() {
    var rogue = new io.nexus.routemind.optimize.RouteOptimizer() {
      @Override public Solution solve(Problem p) {
        List<String> all = new java.util.ArrayList<>();
        for (var j : p.jobs()) all.add(j.id());
        return new Solution("rogue", List.of(new Route("LW-1", all)), List.of());
      }
    };
    var f = new Fixture(1, rogue, MemoryRouteStore::new);
    f.ready("PO-0001", "GH200", 30, 60 * MIN); // 540 kg, 4 pallets each: together over a Lieferwagen
    f.ready("PO-0002", "GH200", 30, 60 * MIN);
    f.advanceTo(30 * MIN + 100);
    assertThat(f.rm.tours()).as("no tour from the broken route").isEmpty();
    assertThat(f.rm.deliveryOfOrder("PO-0001").tourId()).isNull();
    assertThat(planMentions(f, "Kapazität reicht nicht")).isTrue();
    assertThat(f.events(EventTypes.DECISION)).isNotEmpty();
  }

  /** A wave whose departure failed once (database hiccup) still departs a moment later. */
  @Test
  void aFailedDepartureIsRetried() {
    FlakyStore[] flaky = new FlakyStore[1];
    var f = new Fixture(1, new SavingsHeuristic(), work -> flaky[0] = new FlakyStore(new MemoryRouteStore(work)));
    f.rm.breakdownChance(0);
    f.ready("PO-0001", "GH200", 2, 60 * MIN);
    f.advanceTo(60 * MIN - 100);
    flaky[0].failNextTourUpdate = true;
    for (int i = 0; i < 30; i++) { // like Plant.phase: a failing step is reported, the clock goes on
      f.time.advance(100);
      try {
        f.rm.step();
      } catch (RuntimeException expected) {
        // the injected failure
      }
    }
    Tour t = f.rm.tours().get(0);
    assertThat(t.status()).isEqualTo(TourStatus.EN_ROUTE);
    assertThat(f.rm.deliveryOfOrder("PO-0001").status()).isEqualTo(DeliveryStatus.IN_TRANSIT);
  }

  /** A breakdown under way replans the open stops at once, not only with the next regular wave. */
  @Test
  void aBreakdownReplansTheOpenStopsAtOnce() {
    var f = new Fixture(1);
    f.rm.breakdownChance(0);
    f.ready("PO-0001", "GH200", 2, 60 * MIN);
    f.advanceTo(61 * MIN);
    String vehicle = f.rm.tours().get(0).vehicleId();
    f.rm.breakDown(vehicle);
    f.advance(MIN);
    Delivery d = f.rm.deliveryOfOrder("PO-0001");
    assertThat(d.status()).isEqualTo(DeliveryStatus.READY_TO_LOAD);
    assertThat(d.tourId()).isNotNull();
    boolean replanned = false;
    for (NexusEvent e : f.events(EventTypes.DELIVERY_PLAN)) {
      if (((Payloads.DeliveryPlanComputed) e.payload()).trigger().contains("Ausfall")) replanned = true;
    }
    assertThat(replanned).as("plan triggered by the breakdown").isTrue();
  }

  /** Throws once on the next tour update, otherwise a plain memory store. */
  static final class FlakyStore implements RouteStore {
    private final RouteStore inner;
    boolean failNextTourUpdate;

    FlakyStore(RouteStore inner) {
      this.inner = inner;
    }

    @Override public void insertDelivery(Delivery d) { inner.insertDelivery(d); }
    @Override public Delivery updateDelivery(Delivery d) { return inner.updateDelivery(d); }
    @Override public Optional<Delivery> delivery(String id) { return inner.delivery(id); }
    @Override public Optional<Delivery> deliveryOfOrder(String orderId) { return inner.deliveryOfOrder(orderId); }
    @Override public List<Delivery> deliveries() { return inner.deliveries(); }
    @Override public void insertTour(Tour t) { inner.insertTour(t); }
    @Override public Tour updateTour(Tour t) {
      if (failNextTourUpdate) {
        failNextTourUpdate = false;
        throw new IllegalStateException("Datenbank nicht erreichbar");
      }
      return inner.updateTour(t);
    }
    @Override public Optional<Tour> tour(String id) { return inner.tour(id); }
    @Override public List<Tour> tours() { return inner.tours(); }
    @Override public VehicleRecord updateVehicle(VehicleRecord v) { return inner.updateVehicle(v); }
    @Override public List<VehicleRecord> vehicles() { return inner.vehicles(); }
  }

}
