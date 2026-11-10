package io.nexus.contracts.state;

import static io.nexus.contracts.state.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class TransitionsTest {
  @Test
  void orderHappyPath() {
    OrderStatus s = CREATED;
    for (OrderStatus next : new OrderStatus[] {MATERIAL_PENDING, RELEASED, IN_PROGRESS, COMPLETED}) {
      s = OrderStatus.RULES.move("PO-1", s, next);
    }
    assertThat(s).isEqualTo(COMPLETED);
  }

  @Test
  void orderShortageLoopsBackToMaterialPending() {
    assertThat(OrderStatus.RULES.can(MATERIAL_PENDING, SHORTAGE)).isTrue();
    assertThat(OrderStatus.RULES.can(SHORTAGE, MATERIAL_PENDING)).isTrue();
    assertThat(OrderStatus.RULES.can(SHORTAGE, RELEASED)).isFalse();
  }

  @Test
  void everyNonTerminalOrderStateCanBeCancelledAndTerminalStatesAreDeadEnds() {
    for (OrderStatus s : OrderStatus.values()) {
      assertThat(OrderStatus.RULES.can(s, CANCELLED)).as(s.name()).isEqualTo(!s.terminal());
      if (s.terminal()) {
        for (OrderStatus t : OrderStatus.values()) assertThat(OrderStatus.RULES.can(s, t)).isFalse();
      }
    }
  }

  @Test
  void illegalMoveNamesEntityAndBothStates() {
    assertThatThrownBy(() -> OrderStatus.RULES.move("PO-7", CREATED, COMPLETED))
        .isInstanceOf(IllegalTransitionException.class)
        .hasMessageContaining("PO-7").hasMessageContaining("CREATED").hasMessageContaining("COMPLETED");
  }

  @Test
  void transportFailsOnlyWhileLoadedAndIsReassignedFromFailed() {
    var r = TransportStatus.RULES;
    for (TransportStatus s : EnumSet.of(TransportStatus.LOADING, TransportStatus.IN_TRANSIT, TransportStatus.UNLOADING)) {
      assertThat(r.can(s, TransportStatus.FAILED)).isTrue();
      assertThat(r.can(s, TransportStatus.CANCELLED)).isFalse();
    }
    assertThat(r.can(TransportStatus.FAILED, TransportStatus.ASSIGNED)).isTrue();
    // an unloaded AGV failing puts the order back into the queue
    assertThat(r.can(TransportStatus.ASSIGNED, TransportStatus.CREATED)).isTrue();
    assertThat(r.can(TransportStatus.TO_PICKUP, TransportStatus.CREATED)).isTrue();
    assertThat(r.can(TransportStatus.IN_TRANSIT, TransportStatus.CREATED)).isFalse();
  }

  @Test
  void activeTransportStatesAreExactlyTheOnesHoldingAnAgv() {
    assertThat(TransportStatus.ACTIVE).containsExactlyInAnyOrder(
        TransportStatus.ASSIGNED, TransportStatus.TO_PICKUP, TransportStatus.LOADING,
        TransportStatus.IN_TRANSIT, TransportStatus.UNLOADING);
  }

  @Test
  void machineMustBeRepairedBeforeRunningAgain() {
    var r = MachineStatus.RULES;
    assertThat(r.can(MachineStatus.RUNNING, MachineStatus.FAULT)).isTrue();
    assertThat(r.can(MachineStatus.FAULT, MachineStatus.RUNNING)).isFalse();
    assertThat(r.can(MachineStatus.FAULT, MachineStatus.REPAIR)).isTrue();
    assertThat(r.can(MachineStatus.REPAIR, MachineStatus.RUNNING)).isTrue();
  }

  /** The simulation is the authority on machine state; these are transitions the former engine performs. */
  @Test
  void machineAllowsEveryTransitionTheEnginePerforms() {
    var r = MachineStatus.RULES;
    for (MachineStatus s : EnumSet.of(MachineStatus.RUNNING, MachineStatus.SETUP, MachineStatus.BLOCKED)) {
      assertThat(r.can(s, MachineStatus.MAINTENANCE)).as(s + " → MAINTENANCE").isTrue();
      assertThat(r.can(MachineStatus.MAINTENANCE, s)).as("MAINTENANCE → " + s).isTrue();
    }
    assertThat(r.can(MachineStatus.MAINTENANCE, MachineStatus.FAULT)).isTrue();
  }

  @Test
  void movingToTheSameStateIsANoOp() {
    assertThat(MachineStatus.RULES.move("CNC-01", MachineStatus.RUNNING, MachineStatus.RUNNING))
        .isEqualTo(MachineStatus.RUNNING);
    assertThat(OrderStatus.RULES.move("PO-1", COMPLETED, COMPLETED)).isEqualTo(COMPLETED);
  }

  @Test
  void agvFaultIsReachableFromEveryOperationalStateAndRecoversToIdle() {
    var r = AgvStatus.RULES;
    for (AgvStatus s : AgvStatus.values()) {
      if (s != AgvStatus.FAULT && s != AgvStatus.OFFLINE) assertThat(r.can(s, AgvStatus.FAULT)).as(s.name()).isTrue();
    }
    assertThat(r.can(AgvStatus.FAULT, AgvStatus.IDLE)).isTrue();
    assertThat(r.can(AgvStatus.FAULT, AgvStatus.TO_DROP)).isFalse();
  }

  /** The former engine dispatches vehicles that are charging or on their way to a charger. */
  @Test
  void chargingAgvCanBeDispatched() {
    assertThat(AgvStatus.RULES.can(AgvStatus.CHARGING, AgvStatus.TO_PICKUP)).isTrue();
    assertThat(AgvStatus.RULES.can(AgvStatus.TO_CHARGE, AgvStatus.TO_PICKUP)).isTrue();
  }
}
