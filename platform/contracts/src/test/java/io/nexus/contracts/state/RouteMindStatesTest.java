package io.nexus.contracts.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RouteMindStatesTest {
  @Test
  void deliveryFollowsSpec15a() {
    var r = DeliveryStatus.RULES;
    assertThat(r.can(DeliveryStatus.CREATED, DeliveryStatus.AWAITING_GOODS)).isTrue();
    assertThat(r.can(DeliveryStatus.AWAITING_GOODS, DeliveryStatus.READY_TO_LOAD)).isTrue();
    assertThat(r.can(DeliveryStatus.READY_TO_LOAD, DeliveryStatus.LOADED)).isTrue();
    assertThat(r.can(DeliveryStatus.LOADED, DeliveryStatus.IN_TRANSIT)).isTrue();
    assertThat(r.can(DeliveryStatus.IN_TRANSIT, DeliveryStatus.DELIVERED)).isTrue();
    assertThat(r.can(DeliveryStatus.IN_TRANSIT, DeliveryStatus.FAILED)).isTrue();
    assertThat(r.can(DeliveryStatus.FAILED, DeliveryStatus.READY_TO_LOAD)).isTrue();
    // cancelling is possible before LOADED only
    assertThat(r.can(DeliveryStatus.READY_TO_LOAD, DeliveryStatus.CANCELLED)).isTrue();
    assertThat(r.can(DeliveryStatus.LOADED, DeliveryStatus.CANCELLED)).isFalse();
    assertThat(r.can(DeliveryStatus.IN_TRANSIT, DeliveryStatus.CANCELLED)).isFalse();
    assertThat(r.can(DeliveryStatus.LOADED, DeliveryStatus.READY_TO_LOAD)).isFalse();
    assertThat(DeliveryStatus.DELIVERED.terminal()).isTrue();
    assertThat(DeliveryStatus.FAILED.terminal()).isFalse();
  }

  @Test
  void tourAndVehicle() {
    assertThat(TourStatus.RULES.can(TourStatus.PLANNED, TourStatus.REPLANNED)).isTrue();
    assertThat(TourStatus.RULES.can(TourStatus.LOADING, TourStatus.REPLANNED)).isTrue();
    assertThat(TourStatus.RULES.can(TourStatus.EN_ROUTE, TourStatus.REPLANNED)).isFalse();
    assertThat(TourStatus.RULES.can(TourStatus.EN_ROUTE, TourStatus.INTERRUPTED)).isTrue();
    assertThat(TourStatus.COMPLETED.terminal()).isTrue();
    assertThat(VehicleStatus.RULES.can(VehicleStatus.LOADING, VehicleStatus.BROKEN_DOWN)).isFalse();
    assertThat(VehicleStatus.RULES.can(VehicleStatus.EN_ROUTE, VehicleStatus.BROKEN_DOWN)).isTrue();
    assertThat(VehicleStatus.RULES.can(VehicleStatus.BROKEN_DOWN, VehicleStatus.AVAILABLE)).isTrue();
    assertThatThrownBy(() -> VehicleStatus.RULES.move("LKW-1", VehicleStatus.MAINTENANCE, VehicleStatus.LOADING))
        .isInstanceOf(IllegalTransitionException.class);
  }
}
