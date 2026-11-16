package io.nexus.routemind.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.TourStatus;
import io.nexus.contracts.state.VehicleStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behaviour every RouteMind store must show. Runs against memory here and PostgreSQL in platform/app. */
public abstract class RouteStoreContract {
  protected abstract RouteStore store();

  protected abstract UnitOfWork work();

  public static Delivery delivery(String id, String orderId) {
    return new Delivery(id, orderId, "K01", "GH200", 4, 0, 72, 1, Priority.NORMAL, 3_600_000, 18_000_000,
        DeliveryStatus.CREATED, null, null, null, null, null, 0, 1_000, 0);
  }

  private static void assertConflict(Throwable t) {
    assertThat(t).isInstanceOfSatisfying(DomainException.class,
        e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.CONFLICT));
  }

  @Test
  void deliveryIsStoredAndReadBackByIdAndOrder() {
    work().inNewTransaction(() -> store().insertDelivery(delivery("DL-0001", "PO-0001")));
    assertThat(store().delivery("DL-0001")).contains(delivery("DL-0001", "PO-0001"));
    assertThat(store().deliveryOfOrder("PO-0001")).contains(delivery("DL-0001", "PO-0001"));
    assertThat(store().deliveryOfOrder("PO-0002")).isEmpty();
  }

  @Test
  void secondDeliveryForTheSameOrderIsAConflict() {
    work().inNewTransaction(() -> store().insertDelivery(delivery("DL-0001", "PO-0001")));
    assertThatThrownBy(() -> work().inNewTransaction(() -> store().insertDelivery(delivery("DL-0002", "PO-0001"))))
        .satisfies(RouteStoreContract::assertConflict);
  }

  @Test
  void updateChecksTheVersionAndKeepsEveryField() {
    work().inNewTransaction(() -> store().insertDelivery(delivery("DL-0001", "PO-0001")));
    Delivery changed = delivery("DL-0001", "PO-0001").withStatus(DeliveryStatus.AWAITING_GOODS)
        .withGoods(3, 54, 1).withShipment("DL-0001/R", "TO-0007").withTour("TR-0001", 7_200_000L).withFailure()
        .withDelivered(9_000_000L);
    Delivery[] saved = new Delivery[1];
    work().inNewTransaction(() -> saved[0] = store().updateDelivery(changed));
    assertThat(saved[0].version()).isEqualTo(1);
    assertThat(store().delivery("DL-0001")).contains(saved[0]);
    assertThatThrownBy(() -> work().inNewTransaction(() -> store().updateDelivery(changed)))
        .satisfies(RouteStoreContract::assertConflict);
  }

  @Test
  void deliveriesAreOrderedById() {
    work().inNewTransaction(() -> {
      store().insertDelivery(delivery("DL-0002", "PO-0002"));
      store().insertDelivery(delivery("DL-0001", "PO-0001"));
    });
    assertThat(store().deliveries()).extracting(Delivery::id).containsExactly("DL-0001", "DL-0002");
  }

  @Test
  void tourWithStopsRoundTripsAndStopsAreReplacedOnUpdate() {
    var stops = List.of(new Tour.Stop(1, "DL-0001", "K01", 2_000_000, null),
        new Tour.Stop(2, "DL-0002", "K05", 3_000_000, null));
    var tour = new Tour("TR-0001", "RP-0001", "LKW-1", 1_800_000, TourStatus.PLANNED, stops, 42.5, 118.25, null, null, 0);
    work().inNewTransaction(() -> store().insertTour(tour));
    assertThat(store().tour("TR-0001")).contains(tour);
    Tour moved = tour.withStatus(TourStatus.LOADING).withStarted(1_800_000).withArrival(1, 2_100_000)
        .withStops(List.of(new Tour.Stop(1, "DL-0001", "K01", 2_000_000, 2_100_000L)), 20.0, 60.0);
    Tour[] saved = new Tour[1];
    work().inNewTransaction(() -> saved[0] = store().updateTour(moved));
    assertThat(saved[0].version()).isEqualTo(1);
    assertThat(store().tour("TR-0001")).contains(saved[0]);
    assertThat(store().tours()).extracting(Tour::id).containsExactly("TR-0001");
  }

  @Test
  void vehiclesStartAvailableAtTheDepotInIdOrder() {
    assertThat(store().vehicles()).extracting(VehicleRecord::id).containsExactly("LKW-1", "LKW-2", "LW-1", "LW-2");
    assertThat(store().vehicles()).allSatisfy(v -> {
      assertThat(v.status()).isEqualTo(VehicleStatus.AVAILABLE);
      assertThat(v.tourId()).isNull();
      assertThat(v.version()).isZero();
    });
    VehicleRecord moved = store().vehicles().get(0).withStatus(VehicleStatus.LOADING).withTour("TR-0001")
        .withLeg(1, 10, 20, 15L);
    VehicleRecord[] saved = new VehicleRecord[1];
    work().inNewTransaction(() -> saved[0] = store().updateVehicle(moved));
    assertThat(store().vehicles().get(0)).isEqualTo(saved[0]);
    assertThatThrownBy(() -> work().inNewTransaction(() -> store().updateVehicle(moved)))
        .satisfies(RouteStoreContract::assertConflict);
  }

  @Test
  void rolledBackUnitLeavesNothing() {
    assertThatThrownBy(() -> work().inNewTransaction(() -> {
      store().insertDelivery(delivery("DL-0001", "PO-0001"));
      store().updateVehicle(store().vehicles().get(0).withStatus(VehicleStatus.MAINTENANCE));
      throw new IllegalStateException("abort");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(store().deliveries()).isEmpty();
    assertThat(store().vehicles().get(0).status()).isEqualTo(VehicleStatus.AVAILABLE);
  }
}
