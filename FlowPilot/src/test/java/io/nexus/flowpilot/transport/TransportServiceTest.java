package io.nexus.flowpilot.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.port.MaterialService.ReservationRequest;
import io.nexus.contracts.port.MaterialService.ReservationResult.Success;
import io.nexus.contracts.port.TransportService.TransportRequest;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.flowpilot.material.MaterialServiceImpl;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.flowpilot.store.memory.MemoryStockStore;
import io.nexus.flowpilot.store.memory.MemoryTransportStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransportServiceTest {
  final MemoryUnitOfWork work = new MemoryUnitOfWork();
  final MemoryEventLog log = new MemoryEventLog(work);
  final SimTime time = new SimTime();
  final EventIds eventIds = new EventIds(1);
  final EventBus bus = new EventBus(log, work, time, () -> eventIds);
  final MemoryIdSource ids = new MemoryIdSource(work);
  final MemoryStockStore stock = new MemoryStockStore(work);
  final MemoryTransportStore store = new MemoryTransportStore(work);
  final MaterialServiceImpl materials = new MaterialServiceImpl(stock, bus, ids, work, time);
  final TransportServiceImpl transports = new TransportServiceImpl(store, stock, ids, bus, work, time);

  private String reserve(String orderId, String materialId, int quantity) {
    var ok = (Success) materials.reserve(new ReservationRequest("REQ-" + orderId, orderId,
        List.of(new Line(materialId, quantity))));
    return ok.reservations().get(0).reservationId();
  }

  private TransportRequest supply(String requestId, String reservationId, int quantity) {
    return new TransportRequest(requestId, TransportKind.SUPPLY, "PO-0001", "RM-GH200", quantity,
        Place.storage("RAW-A"), Place.machineIn("CNC-01"), Priority.HOCH, reservationId);
  }

  private static void assertKind(Throwable t, DomainException.Kind kind) {
    assertThat(t).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(kind));
  }

  @Test
  void supplyRequestCreatesAnOrderWithResolvedNodes() {
    String res = reserve("PO-0001", "RM-GH200", 5);
    time.advance(1234);
    String id = transports.requestTransport(supply("TR-1", res, 5));
    assertThat(id).isEqualTo("TO-0001");
    TransportOrder t = store.byId(id).orElseThrow();
    assertThat(t.status()).isEqualTo(TransportStatus.CREATED);
    assertThat(t.fromNode()).isEqualTo("D_RAW");
    assertThat(t.toNode()).isEqualTo("D_CNC01");
    assertThat(t.reservationId()).isEqualTo(res);
    assertThat(t.priority()).isEqualTo(Priority.HOCH);
    assertThat(t.createdAtMs()).isEqualTo(1234);
    assertThat(t.agvId()).isNull();
    NexusEvent last = log.all().get(log.all().size() - 1);
    assertThat(last.type()).isEqualTo(EventTypes.TRANSPORT_CREATED);
    assertThat(last.correlationId()).isEqualTo("PO-0001");
    assertThat(last.payload()).isEqualTo(new Payloads.TransportCreated("TO-0001", TransportKind.SUPPLY, "PO-0001",
        "RM-GH200", 5, Place.storage("RAW-A"), Place.machineIn("CNC-01")));
  }

  @Test
  void sameRequestIdReturnsTheSameTransport() {
    String res = reserve("PO-0001", "RM-GH200", 5);
    String first = transports.requestTransport(supply("TR-1", res, 5));
    int events = log.all().size();
    assertThat(transports.requestTransport(supply("TR-1", res, 5))).isEqualTo(first);
    assertThat(store.all()).hasSize(1);
    assertThat(log.all()).hasSize(events);
  }

  @Test
  void supplyWithoutMatchingReservationIsInvalid() {
    String res = reserve("PO-0001", "RM-GH200", 5);
    String otherOrder = reserve("PO-0002", "RM-GH200", 5);
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> transports.requestTransport(supply("TR-1", null, 5))),
        DomainException.Kind.INVALID);
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> transports.requestTransport(supply("TR-2", res, 4))),
        DomainException.Kind.INVALID);
    assertKind(org.assertj.core.api.Assertions.catchThrowable(
        () -> transports.requestTransport(supply("TR-3", otherOrder, 5))), DomainException.Kind.INVALID);
    var wrongPlace = new TransportRequest("TR-4", TransportKind.SUPPLY, "PO-0001", "RM-GH200", 5,
        Place.storage("RAW-B"), Place.machineIn("CNC-01"), Priority.NORMAL, res);
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> transports.requestTransport(wrongPlace)),
        DomainException.Kind.INVALID);
    materials.release("REQ-PO-0001");
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> transports.requestTransport(supply("TR-5", res, 5))),
        DomainException.Kind.INVALID);
    assertThat(store.all()).isEmpty();
  }

  @Test
  void quantityMustBePositive() {
    var wip = new TransportRequest("TR-1", TransportKind.WIP, "PO-0001", null, 0,
        Place.machineOut("CNC-01"), Place.machineIn("CNC-02"), Priority.NORMAL, null);
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> transports.requestTransport(wip)),
        DomainException.Kind.INVALID);
  }

  @Test
  void machineOutOfAnAssemblyCellIsInvalid() {
    var wip = new TransportRequest("TR-1", TransportKind.WIP, "PO-0001", null, 5,
        Place.machineOut("ROB-01"), Place.machineIn("QC-01"), Priority.NORMAL, null);
    assertThatThrownBy(() -> transports.requestTransport(wip))
        .isInstanceOfSatisfying(DomainException.class, e -> {
          assertThat(e.kind()).isEqualTo(DomainException.Kind.INVALID);
          assertThat(e.getMessage()).contains("Band");
        });
  }

  @Test
  void putawayGoesIntoTheFinishedGoodsStore() {
    var toRaw = new TransportRequest("TR-1", TransportKind.PUTAWAY, "PO-0001", "FG-GH200", 5,
        Place.machineOut("QC-01"), Place.storage("RAW-A"), Priority.NORMAL, null);
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> transports.requestTransport(toRaw)),
        DomainException.Kind.INVALID);
    var withoutMaterial = new TransportRequest("TR-3", TransportKind.PUTAWAY, "PO-0001", null, 5,
        Place.machineOut("QC-01"), Place.storage("FG-A"), Priority.NORMAL, null);
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> transports.requestTransport(withoutMaterial)),
        DomainException.Kind.INVALID);
    var ok = new TransportRequest("TR-2", TransportKind.PUTAWAY, "PO-0001", "FG-GH200", 5,
        Place.machineOut("QC-01"), Place.storage("FG-A"), Priority.NORMAL, null);
    TransportOrder t = store.byId(transports.requestTransport(ok)).orElseThrow();
    assertThat(t.fromNode()).isEqualTo("D_CV04");
    assertThat(t.toNode()).isEqualTo("D_FG");
  }

  @Test
  void cancelBeforePickupWorks() {
    String id = transports.requestTransport(supply("TR-1", reserve("PO-0001", "RM-GH200", 5), 5));
    transports.cancel(id);
    assertThat(store.byId(id).orElseThrow().status()).isEqualTo(TransportStatus.CANCELLED);
    assertThat(log.all().get(log.all().size() - 1).payload())
        .isEqualTo(new Payloads.TransportStateChanged(id, TransportStatus.CREATED, TransportStatus.CANCELLED, null));
    // the reservation belongs to the order; SmartFactory releases it
    assertThat(stock.reservationsOfRequest("REQ-PO-0001").get(0).status())
        .isEqualTo(io.nexus.flowpilot.store.Reservation.Status.ACTIVE);
  }

  @Test
  void cancelAfterPickupIsAnIllegalTransition() {
    String id = transports.requestTransport(supply("TR-1", reserve("PO-0001", "RM-GH200", 5), 5));
    TransportOrder t = store.byId(id).orElseThrow();
    store.update(t.withStatus(TransportStatus.LOADING, "AGV-01"));
    assertThatThrownBy(() -> transports.cancel(id)).isInstanceOf(IllegalTransitionException.class);
    assertThat(store.byId(id).orElseThrow().status()).isEqualTo(TransportStatus.LOADING);
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> transports.cancel("TO-9999")),
        DomainException.Kind.NOT_FOUND);
  }

  @Test
  void redirectChangesTargetAndNode() {
    String id = transports.requestTransport(supply("TR-1", reserve("PO-0001", "RM-GH200", 5), 5));
    transports.redirect(id, Place.machineIn("CNC-04"));
    TransportOrder t = store.byId(id).orElseThrow();
    assertThat(t.to()).isEqualTo(Place.machineIn("CNC-04"));
    assertThat(t.toNode()).isEqualTo("D_CNC04");
    store.update(t.withStatus(TransportStatus.UNLOADING, "AGV-01"));
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> transports.redirect(id, Place.machineIn("CNC-02"))),
        DomainException.Kind.CONFLICT);
  }

  @Test
  void unknownPlaceIsNotFound() {
    var wip = new TransportRequest("TR-1", TransportKind.WIP, "PO-0001", null, 5,
        Place.machineOut("CNC-01"), Place.machineIn("CNC-99"), Priority.NORMAL, null);
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> transports.requestTransport(wip)),
        DomainException.Kind.NOT_FOUND);
    var resolver = new PlaceResolver();
    assertKind(org.assertj.core.api.Assertions.catchThrowable(() -> resolver.node(Place.storage("NOPE"))),
        DomainException.Kind.NOT_FOUND);
    assertThat(resolver.node(new Place(Place.Kind.CONVEYOR, "CV-03"))).isEqualTo("D_CV03");
    assertThat(resolver.node(new Place(Place.Kind.CHARGER, "CH-2"))).isEqualTo("D_CH2");
    assertThat(resolver.node(Place.machineOut("QC-01"))).isEqualTo("D_CV04");
  }
}
