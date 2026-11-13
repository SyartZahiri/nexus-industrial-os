package io.nexus.flowpilot.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.state.AgvStatus;
import io.nexus.contracts.state.TransportStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every FlowPilot store must show. Runs against memory here and PostgreSQL in platform/app.
 * Each test starts from the master data (initial stock, six idle AGVs at their chargers, no blocked edge).
 */
public abstract class StoreContract {
  protected abstract StockStore stock();

  protected abstract TransportStore transports();

  protected abstract FleetStore fleet();

  protected abstract UnitOfWork work();

  private static void assertKind(Throwable t, DomainException.Kind kind) {
    assertThat(t).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(kind));
  }

  public static TransportOrder order(String id, Priority priority, long createdAtMs) {
    return new TransportOrder(id, "REQ-" + id, TransportKind.SUPPLY, "PO-0001", "RM-GH200", 5,
        Place.storage("RAW-A"), "D_RAW", Place.machineIn("CNC-01"), "D_CNC01", priority,
        TransportStatus.CREATED, null, null, createdAtMs, 0);
  }

  // stock -------------------------------------------------------------------------------------------

  @Test
  void initialStockIsRead() {
    List<StockLine> lines = stock().lines();
    assertThat(lines).hasSize(10);
    assertThat(lines).contains(new StockLine("RM-GH200", "RAW-A", 30, 0), new StockLine("FG-MT7", "FG-A", 3, 0));
    assertThat(lines.get(0).materialId()).isEqualTo("FG-AW50"); // ordered by material, location
  }

  @Test
  void savedLineIsReadBackAndNewLinesAreInsertedInLocationOrder() {
    stock().saveLine(new StockLine("RM-GH200", "RAW-A", 25, 3));
    stock().saveLine(new StockLine("RM-GH200", "RAW-B", 7, 0));
    assertThat(stock().lockMaterial("RM-GH200")).containsExactly(
        new StockLine("RM-GH200", "RAW-A", 25, 3), new StockLine("RM-GH200", "RAW-B", 7, 0));
    assertThat(stock().lockMaterial("RM-GH200").get(0).available()).isEqualTo(22);
  }

  @Test
  void rolledBackUnitUndoesStockAndReservationWrites() {
    assertThatThrownBy(() -> work().inNewTransaction(() -> {
      stock().saveLine(new StockLine("RM-GH200", "RAW-A", 30, 5));
      stock().saveLine(new StockLine("RM-GH200", "RAW-B", 4, 0));
      stock().saveReservation(new Reservation("RES-0001", "REQ-1", "PO-0001", "RM-GH200", "RAW-A", 5,
          Reservation.Status.ACTIVE, 0));
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(stock().lockMaterial("RM-GH200")).containsExactly(new StockLine("RM-GH200", "RAW-A", 30, 0));
    assertThat(stock().reservation("RES-0001")).isEmpty();
  }

  // reservations ------------------------------------------------------------------------------------

  @Test
  void reservationsAreFoundByRequestOrderAndIdAndChangeStatus() {
    var kit = new Reservation("RES-0002", "REQ-1", "PO-0001", "KIT-GH200", "RAW-B", 1, Reservation.Status.ACTIVE, 10);
    var raw = new Reservation("RES-0001", "REQ-1", "PO-0001", "RM-GH200", "RAW-A", 1, Reservation.Status.ACTIVE, 10);
    var other = new Reservation("RES-0003", "REQ-2", "PO-0002", "RM-GH200", "RAW-A", 2, Reservation.Status.ACTIVE, 20);
    stock().saveReservation(raw);
    stock().saveReservation(kit);
    stock().saveReservation(other);

    assertThat(stock().reservationsOfRequest("REQ-1")).containsExactly(kit, raw); // by material id
    assertThat(stock().reservationsOfOrder("PO-0002")).containsExactly(other);
    assertThat(stock().reservationsOfRequest("REQ-9")).isEmpty();

    var consumed = new Reservation("RES-0001", "REQ-1", "PO-0001", "RM-GH200", "RAW-A", 1,
        Reservation.Status.CONSUMED, 10);
    stock().saveReservation(consumed);
    assertThat(stock().reservation("RES-0001")).contains(consumed);
  }

  // transports --------------------------------------------------------------------------------------

  @Test
  void transportIsInsertedAndFoundByIdAndRequest() {
    var t = order("TO-0001", Priority.NORMAL, 100);
    transports().insert(t);
    assertThat(transports().byId("TO-0001")).contains(t);
    assertThat(transports().byRequest("REQ-TO-0001")).contains(t);
    assertThat(transports().byId("TO-9999")).isEmpty();
  }

  @Test
  void openTransportsAreOrderedByPriorityThenTimeThenId() {
    transports().insert(order("TO-0001", Priority.NORMAL, 100));
    transports().insert(order("TO-0002", Priority.HOCH, 300));
    transports().insert(order("TO-0003", Priority.EILIG, 500));
    transports().insert(order("TO-0004", Priority.HOCH, 200));
    transports().insert(order("TO-0006", Priority.NORMAL, 50));
    transports().insert(order("TO-0005", Priority.NORMAL, 50));
    transports().insert(order("TO-0007", Priority.EILIG, 0));
    transports().update(withStatus(transports().byId("TO-0007").orElseThrow(), TransportStatus.CANCELLED));

    assertThat(transports().open()).extracting(TransportOrder::id)
        .containsExactly("TO-0003", "TO-0004", "TO-0002", "TO-0005", "TO-0006", "TO-0001");
    assertThat(transports().all()).extracting(TransportOrder::id)
        .containsExactly("TO-0001", "TO-0002", "TO-0003", "TO-0004", "TO-0005", "TO-0006", "TO-0007");
  }

  private static TransportOrder withStatus(TransportOrder t, TransportStatus status) {
    return new TransportOrder(t.id(), t.requestId(), t.kind(), t.orderId(), t.materialId(), t.quantity(), t.from(),
        t.fromNode(), t.to(), t.toNode(), t.priority(), status, t.agvId(), t.reservationId(), t.createdAtMs(),
        t.version());
  }

  @Test
  void updateWithAStaleVersionIsAConflict() {
    transports().insert(order("TO-0001", Priority.NORMAL, 100));
    var first = transports().byId("TO-0001").orElseThrow();
    var updated = transports().update(withStatus(first, TransportStatus.ASSIGNED));
    assertThat(updated.version()).isEqualTo(1);
    assertThat(transports().byId("TO-0001")).contains(updated);
    assertKind(catchThrowable(() -> transports().update(withStatus(first, TransportStatus.CANCELLED))),
        DomainException.Kind.CONFLICT);
    assertThat(transports().byId("TO-0001").orElseThrow().status()).isEqualTo(TransportStatus.ASSIGNED);
  }

  @Test
  void secondInsertWithTheSameRequestIdFails() {
    transports().insert(order("TO-0001", Priority.NORMAL, 100));
    var again = new TransportOrder("TO-0002", "REQ-TO-0001", TransportKind.SUPPLY, "PO-0001", "RM-GH200", 5,
        Place.storage("RAW-A"), "D_RAW", Place.machineIn("CNC-01"), "D_CNC01", Priority.NORMAL,
        TransportStatus.CREATED, null, null, 100, 0);
    assertKind(catchThrowable(() -> transports().insert(again)), DomainException.Kind.CONFLICT);
    assertThat(transports().all()).hasSize(1);
  }

  @Test
  void rolledBackUnitUndoesTransportWrites() {
    transports().insert(order("TO-0001", Priority.NORMAL, 100));
    assertThatThrownBy(() -> work().inNewTransaction(() -> {
      transports().update(withStatus(transports().byId("TO-0001").orElseThrow(), TransportStatus.ASSIGNED));
      transports().insert(order("TO-0002", Priority.NORMAL, 100));
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(transports().all()).extracting(TransportOrder::id).containsExactly("TO-0001");
    assertThat(transports().byId("TO-0001").orElseThrow().version()).isZero();
    assertThat(transports().byRequest("REQ-TO-0002")).isEmpty();
  }

  // fleet -------------------------------------------------------------------------------------------

  @Test
  void sixIdleAgvsStartAtTheirChargers() {
    assertThat(fleet().agvs()).extracting(AgvRecord::id, AgvRecord::nodeId).containsExactly(
        org.assertj.core.groups.Tuple.tuple("AGV-01", "D_CH1"), org.assertj.core.groups.Tuple.tuple("AGV-02", "D_CH2"),
        org.assertj.core.groups.Tuple.tuple("AGV-03", "D_CH3"), org.assertj.core.groups.Tuple.tuple("AGV-04", "D_CH4"),
        org.assertj.core.groups.Tuple.tuple("AGV-05", "D_CH5"), org.assertj.core.groups.Tuple.tuple("AGV-06", "D_CH6"));
    assertThat(fleet().agvs()).allSatisfy(a -> {
      assertThat(a.status()).isEqualTo(AgvStatus.IDLE);
      assertThat(a.battery()).isEqualTo(100.0);
      assertThat(a.enabled()).isTrue();
      assertThat(a.version()).isZero();
    });
  }

  @Test
  void agvIsSavedWithVersionCheck() {
    AgvRecord first = fleet().agvs().get(0);
    var moved = new AgvRecord(first.id(), first.name(), AgvStatus.TO_PICKUP, "D_RAW", 87.5, true, first.version());
    AgvRecord saved = fleet().save(moved);
    assertThat(saved.version()).isEqualTo(1);
    assertThat(fleet().agvs().get(0)).isEqualTo(saved);
    assertKind(catchThrowable(() -> fleet().save(moved)), DomainException.Kind.CONFLICT);
    assertThatThrownBy(() -> work().inNewTransaction(() -> {
      fleet().save(saved);
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(fleet().agvs().get(0)).isEqualTo(saved);
  }

  @Test
  void edgesAreBlockedAndUnblocked() {
    fleet().setEdgeBlocked("LN_-2>LN_2", true);
    fleet().setEdgeBlocked("LN_-19>LN_-15", true);
    assertThat(fleet().blockedEdges()).containsExactly("LN_-19>LN_-15", "LN_-2>LN_2");
    fleet().setEdgeBlocked("LN_-2>LN_2", false);
    assertThat(fleet().blockedEdges()).containsExactly("LN_-19>LN_-15");
    assertKind(catchThrowable(() -> fleet().setEdgeBlocked("X>Y", true)), DomainException.Kind.NOT_FOUND);
  }

  private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
    return org.assertj.core.api.Assertions.catchThrowable(c);
  }
}
