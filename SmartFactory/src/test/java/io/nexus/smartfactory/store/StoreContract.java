package io.nexus.smartfactory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.ProcessType;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.contracts.state.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every OrderStore/MachineStore pair must show. Runs against memory here and against PostgreSQL
 * in platform/app, so both runtimes behave the same. Expects the seeded master data and no orders.
 */
public abstract class StoreContract {
  protected abstract OrderStore orders();

  protected abstract MachineStore machines();

  protected abstract UnitOfWork work();

  protected static ProductionOrder order(String id, OrderStatus status) {
    return new ProductionOrder(id, "GH200", 5, Priority.NORMAL, status, 1_000_000, 0, null, 0, 0, 0);
  }

  protected static Operation op(String orderId, int seq, String machineId, Operation.Status status) {
    return new Operation(orderId + "/" + seq, orderId, seq, ProcessType.FRAESEN, machineId, status,
        10L * seq, 20L * seq, null, null, 0, 0);
  }

  private void insert(String id, OrderStatus status) {
    orders().insert(order(id, status), List.of(op(id, 1, "CNC-01", Operation.Status.PLANNED),
        op(id, 2, "CNC-02", Operation.Status.PLANNED)));
  }

  @Test
  void insertedOrderIsReadBackWithItsOperations() {
    insert("PO-0001", OrderStatus.CREATED);
    assertThat(orders().order("PO-0001")).contains(order("PO-0001", OrderStatus.CREATED));
    assertThat(orders().operations("PO-0001")).containsExactly(
        op("PO-0001", 1, "CNC-01", Operation.Status.PLANNED), op("PO-0001", 2, "CNC-02", Operation.Status.PLANNED));
    assertThat(orders().order("PO-9999")).isEmpty();
  }

  @Test
  void updateBumpsTheVersionAndAStaleVersionIsAConflict() {
    insert("PO-0001", OrderStatus.CREATED);
    ProductionOrder stale = orders().order("PO-0001").orElseThrow();
    ProductionOrder updated = orders().update(
        new ProductionOrder("PO-0001", "GH200", 5, Priority.EILIG, OrderStatus.COMPLETED, 1_000_000, 0, 500L, 4, 1, 0));
    assertThat(updated.version()).isEqualTo(1);
    assertThat(orders().order("PO-0001")).contains(updated);
    assertThatThrownBy(() -> orders().update(stale))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.CONFLICT));
  }

  @Test
  void ordersAreSortedByIdAndOpenLeavesOutFinishedOnes() {
    insert("PO-0003", OrderStatus.IN_PROGRESS);
    insert("PO-0001", OrderStatus.COMPLETED);
    insert("PO-0002", OrderStatus.CANCELLED);
    insert("PO-0004", OrderStatus.SHORTAGE);
    assertThat(orders().orders()).extracting(ProductionOrder::id).containsExactly("PO-0001", "PO-0002", "PO-0003", "PO-0004");
    assertThat(orders().open()).extracting(ProductionOrder::id).containsExactly("PO-0003", "PO-0004");
  }

  @Test
  void savedOperationReplacesTheOldOneAndMachineQueueSkipsFinishedOnes() {
    insert("PO-0002", OrderStatus.IN_PROGRESS);
    insert("PO-0001", OrderStatus.IN_PROGRESS);
    var running = new Operation("PO-0002/1", "PO-0002", 1, ProcessType.FRAESEN, "CNC-01", Operation.Status.RUNNING,
        10L, 20L, 5L, null, 0, 0);
    orders().saveOperation(running);
    orders().saveOperation(new Operation("PO-0001/1", "PO-0001", 1, ProcessType.FRAESEN, "CNC-01",
        Operation.Status.DONE, 10L, 20L, 5L, 18L, 4, 1));
    assertThat(orders().operations("PO-0002").getFirst()).isEqualTo(running);
    assertThat(orders().operations("PO-0001").getFirst().good()).isEqualTo(4);
    assertThat(orders().operationsOnMachine("CNC-01")).containsExactly(running);
  }

  @Test
  void materialIsFoundByItsTransport() {
    insert("PO-0001", OrderStatus.MATERIAL_PENDING);
    var supply = new OperationMaterial("PO-0001/S1/RM-GH200", "PO-0001", 1, TransportKind.SUPPLY, "RM-GH200", 5,
        "TO-0001", false);
    var wip = new OperationMaterial("PO-0001/W2", "PO-0001", 2, TransportKind.WIP, null, 5, null, false);
    orders().saveMaterial(supply);
    orders().saveMaterial(wip);
    assertThat(orders().materialByTransport("TO-0001")).contains(supply);
    assertThat(orders().materialByTransport("TO-0404")).isEmpty();
    assertThat(orders().materials("PO-0001", 2)).containsExactly(wip);

    var delivered = new OperationMaterial("PO-0001/S1/RM-GH200", "PO-0001", 1, TransportKind.SUPPLY, "RM-GH200", 5,
        "TO-0001", true);
    orders().saveMaterial(delivered);
    assertThat(orders().materials("PO-0001", 1)).containsExactly(delivered);
  }

  @Test
  void machinesAreSeededIdleAndSavedOptimistically() {
    List<MachineRecord> all = machines().machines();
    assertThat(all).extracting(MachineRecord::id)
        .containsExactly("CNC-01", "CNC-02", "CNC-03", "CNC-04", "QC-01", "ROB-01", "ROB-02");
    MachineRecord cnc = all.getFirst();
    assertThat(cnc).isEqualTo(new MachineRecord("CNC-01", MachineStatus.IDLE, null, 0));
    MachineRecord saved = machines().save(new MachineRecord("CNC-01", MachineStatus.RUNNING, "GH200", 0));
    assertThat(saved.version()).isEqualTo(1);
    assertThat(machines().machines().getFirst()).isEqualTo(saved);
    assertThatThrownBy(() -> machines().save(cnc)).isInstanceOf(DomainException.class);
  }

  @Test
  void changeStateClosesTheOpenIntervalAndIntervalsAreClippedToTheWindow() {
    machines().changeState("CNC-01", MachineStatus.IDLE, 0);
    machines().changeState("CNC-01", MachineStatus.RUNNING, 100);
    machines().changeState("CNC-01", MachineStatus.FAULT, 300);
    machines().changeState("CNC-02", MachineStatus.RUNNING, 50);
    assertThat(machines().intervals("CNC-01", 50, 400)).containsExactly(
        new StateInterval("CNC-01", MachineStatus.IDLE, 50, 100L),
        new StateInterval("CNC-01", MachineStatus.RUNNING, 100, 300L),
        new StateInterval("CNC-01", MachineStatus.FAULT, 300, 400L));
    assertThat(machines().intervals("CNC-01", 120, 200)).containsExactly(
        new StateInterval("CNC-01", MachineStatus.RUNNING, 120, 200L));
    assertThat(machines().intervals("CNC-03", 0, 400)).isEmpty();
    assertThat(machines().maintenanceWindows("CNC-01")).isEmpty();
  }

  @Test
  void rolledBackUnitTakesEverythingBack() {
    insert("PO-0001", OrderStatus.CREATED);
    assertThatThrownBy(() -> work().inNewTransaction(() -> {
      insert("PO-0002", OrderStatus.CREATED);
      orders().update(order("PO-0001", OrderStatus.MATERIAL_PENDING));
      orders().saveOperation(op("PO-0001", 1, "CNC-04", Operation.Status.WAITING_MATERIAL));
      orders().saveMaterial(new OperationMaterial("PO-0001/S1/RM-GH200", "PO-0001", 1, TransportKind.SUPPLY,
          "RM-GH200", 5, "TO-0001", false));
      machines().save(new MachineRecord("CNC-01", MachineStatus.RUNNING, "GH200", 0));
      machines().changeState("CNC-01", MachineStatus.RUNNING, 10);
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(orders().orders()).extracting(ProductionOrder::id).containsExactly("PO-0001");
    assertThat(orders().order("PO-0001")).contains(order("PO-0001", OrderStatus.CREATED));
    assertThat(orders().operations("PO-0001").getFirst()).isEqualTo(op("PO-0001", 1, "CNC-01", Operation.Status.PLANNED));
    assertThat(orders().materials("PO-0001", 1)).isEmpty();
    assertThat(machines().machines().getFirst()).isEqualTo(new MachineRecord("CNC-01", MachineStatus.IDLE, null, 0));
    assertThat(machines().intervals("CNC-01", 0, 100)).isEmpty();
  }
}
