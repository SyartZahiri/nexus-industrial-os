package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.planning.Plan;
import org.junit.jupiter.api.Test;

class ProductionEtaTest {
  @Test
  void etaIsTheEndOfTheLastPlannedOperation() {
    Plant plant = Plant.inMemory(4711, Scenario1h.rules());
    String id = plant.smartFactory().createOrder(new NewOrder("GH200", 3, Priority.NORMAL, 90 * 60_000L));
    plant.advance(5_000);
    long expected = -1;
    for (Plan.PlannedOp op : plant.smartFactory().plan().ops()) {
      if (op.orderId().equals(id)) expected = Math.max(expected, op.endMs());
    }
    assertThat(expected).isPositive();
    assertThat(plant.smartFactory().eta().plannedFinishMs(id)).isEqualTo(expected);
    assertThat(plant.smartFactory().eta().plannedFinishMs("PO-9999")).isEqualTo(-1);
  }
}
