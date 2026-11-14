package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.state.AgvStatus;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.flowpilot.FlowPilot.VehicleView;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.smartfactory.store.ProductionOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Four hours with random faults, automatic orders and shipping: nothing breaks and nothing gets stuck. */
class PlantLongRunTest {
  static final double SAFETY_DISTANCE = 1.75;

  @ParameterizedTest
  @ValueSource(longs = {1, 2, 3, 4})
  void fourHoursWithRandomFaultsKeepEveryInvariantAndNoOrderHangs(long seed) {
    Plant plant = Plant.inMemory(seed, PlantRules.DEFAULT);
    Map<String, String> lastState = new HashMap<>();
    Map<String, Long> lastChange = new HashMap<>();
    for (long t = 0; t < 4 * 3_600_000L; t += Plant.STEP_MS) {
      plant.advance(Plant.STEP_MS);
      checkFleet(plant.flowPilot().vehicles(), plant.flowPilot().openTransports());
      checkStock(plant.flowPilot().stock());
      checkMachinesAgree(plant);
      if (t % 10_000 == 0) {
        for (ProductionOrder o : plant.smartFactory().orders()) {
          if (o.status().terminal()) continue;
          long done = plant.smartFactory().operations(o.id()).stream()
              .filter(op -> op.status() == io.nexus.smartfactory.store.Operation.Status.DONE).count();
          String state = o.status() + "/" + done + "/" + o.priority();
          if (!state.equals(lastState.get(o.id()))) {
            lastState.put(o.id(), state);
            lastChange.put(o.id(), plant.nowMs());
          }
          if (o.status() != OrderStatus.SHORTAGE) {
            assertThat(plant.nowMs() - lastChange.get(o.id())).as(o.id() + " stuck in " + state).isLessThan(3_600_000);
          }
        }
      }
    }
    long completed = plant.smartFactory().orders().stream().filter(o -> o.status() == OrderStatus.COMPLETED).count();
    assertThat(completed).as("orders completed in four hours").isGreaterThanOrEqualTo(15);
  }

  private static void checkFleet(List<VehicleView> vehicles, List<TransportOrder> open) {
    for (VehicleView a : vehicles) {
      assertThat(a.battery()).isBetween(0.0, 100.0);
      long active = open.stream().filter(t -> a.id().equals(t.agvId())
          && io.nexus.contracts.state.TransportStatus.ACTIVE.contains(t.status())).count();
      assertThat(active).as(a.id() + " active transports").isLessThanOrEqualTo(1);
      // same check as FlowPilot's FleetInvariantTest: every vehicle on its way, heading measured from +z
      if (a.status() != AgvStatus.TO_PICKUP && a.status() != AgvStatus.TO_DROP && a.status() != AgvStatus.TO_CHARGE) continue;
      double hx = Math.sin(a.heading());
      double hz = Math.cos(a.heading());
      for (VehicleView b : vehicles) {
        if (a == b) continue;
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double ahead = dx * hx + dz * hz;
        double side = Math.abs(-dx * hz + dz * hx);
        assertThat(ahead > 0 && ahead < SAFETY_DISTANCE - 0.05 && side < 0.6)
            .as(a.id() + " too close to " + b.id() + " (" + ahead + " m)").isFalse();
      }
    }
  }

  /**
   * The simulation and SmartFactory agree on each machine: the same status, and an operation is RUNNING there
   * exactly when the machine holds that order's batch (also while paused by a fault or a stop).
   */
  static void checkMachinesAgree(Plant plant) {
    var records = plant.smartFactory().machines();
    for (var m : plant.machines().views()) {
      var record = records.stream().filter(r -> r.id().equals(m.id())).findFirst().orElseThrow();
      assertThat(record.status()).as(m.id() + " status at " + plant.nowMs()).isEqualTo(m.status());
      var running = plant.smartFactory().runningOn(m.id());
      assertThat(running == null ? null : running.orderId()).as(m.id() + " batch at " + plant.nowMs()).isEqualTo(m.orderId());
    }
  }

  private static void checkStock(List<StockLine> stock) {
    for (StockLine l : stock) {
      assertThat(l.onHand()).isGreaterThanOrEqualTo(0);
      assertThat(l.reserved()).isBetween(0, l.onHand());
    }
  }
}
