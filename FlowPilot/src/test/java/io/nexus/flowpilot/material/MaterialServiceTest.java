package io.nexus.flowpilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.DomainException;
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
import io.nexus.contracts.port.MaterialService.ReservationResult;
import io.nexus.contracts.port.MaterialService.ReservationResult.Shortage;
import io.nexus.contracts.port.MaterialService.ReservationResult.Success;
import io.nexus.contracts.port.MaterialService.Reserved;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.store.Reservation;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.memory.MemoryStockStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialServiceTest {
  final MemoryUnitOfWork work = new MemoryUnitOfWork();
  final MemoryEventLog log = new MemoryEventLog(work);
  final SimTime time = new SimTime();
  final EventIds eventIds = new EventIds(1);
  final EventBus bus = new EventBus(log, work, time, () -> eventIds);
  final MemoryStockStore stock = new MemoryStockStore(work);
  final MaterialServiceImpl materials = new MaterialServiceImpl(stock, bus, new MemoryIdSource(work), work, time);
  final Replenishment replenishment = new Replenishment(stock, materials, bus, work);

  private StockLine line(String materialId, String locationId) {
    return stock.lines().stream().filter(l -> l.materialId().equals(materialId) && l.locationId().equals(locationId))
        .findFirst().orElseThrow();
  }

  private static ReservationRequest request(String requestId, Line... lines) {
    return new ReservationRequest(requestId, "PO-0001", List.of(lines));
  }

  private List<String> types() {
    return log.all().stream().map(NexusEvent::type).toList();
  }

  @Test
  void issueTakesOnlyUnreservedStock() {
    materials.reserve(request("REQ-1", new Line("RM-FP12", 10)));
    materials.issue("RM-FP12", "RAW-A", 20);
    assertThat(line("RM-FP12", "RAW-A")).isEqualTo(new StockLine("RM-FP12", "RAW-A", 10, 10));
    assertThat(types()).endsWith("stock.changed");
  }

  @Test
  void issueOfMoreThanAvailableIsAConflict() {
    materials.reserve(request("REQ-1", new Line("RM-FP12", 10)));
    assertThatThrownBy(() -> materials.issue("RM-FP12", "RAW-A", 21)).isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).kind()).isEqualTo(DomainException.Kind.CONFLICT);
    assertThatThrownBy(() -> materials.issue("RM-FP12", "RAW-A", 0)).isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).kind()).isEqualTo(DomainException.Kind.INVALID);
    assertThat(line("RM-FP12", "RAW-A")).isEqualTo(new StockLine("RM-FP12", "RAW-A", 30, 10));
  }

  @Test
  void reservesAllLinesOrNothing() {
    var result = materials.reserve(request("REQ-1", new Line("RM-GH200", 5), new Line("KIT-GH200", 25)));
    assertThat(result).isEqualTo(new Shortage("KIT-GH200", 25, 24));
    assertThat(line("RM-GH200", "RAW-A")).isEqualTo(new StockLine("RM-GH200", "RAW-A", 30, 0));
    assertThat(line("KIT-GH200", "RAW-B").reserved()).isZero();

    var ok = materials.reserve(request("REQ-2", new Line("RM-GH200", 5), new Line("KIT-GH200", 5)));
    assertThat(ok).isInstanceOf(Success.class);
    assertThat(((Success) ok).reservations()).containsExactly(
        new Reserved("RES-0001", "KIT-GH200", "RAW-B", 5), new Reserved("RES-0002", "RM-GH200", "RAW-A", 5));
    assertThat(line("RM-GH200", "RAW-A").reserved()).isEqualTo(5);
  }

  @Test
  void sameRequestTwiceReservesOnce() {
    var first = materials.reserve(request("REQ-1", new Line("RM-GH200", 5)));
    int events = log.all().size();
    var second = materials.reserve(request("REQ-1", new Line("RM-GH200", 5)));
    assertThat(second).isEqualTo(first);
    assertThat(line("RM-GH200", "RAW-A").reserved()).isEqualTo(5);
    assertThat(log.all()).hasSize(events);
  }

  @Test
  void shortageIsNotStoredAndASecondCallRechecks() {
    assertThat(materials.reserve(request("REQ-1", new Line("RM-GH200", 40)))).isInstanceOf(Shortage.class);
    assertThat(stock.reservationsOfRequest("REQ-1")).isEmpty();
    work.inNewTransaction(() -> materials.receive("RM-GH200", "RAW-A", 10));
    assertThat(materials.reserve(request("REQ-1", new Line("RM-GH200", 40)))).isInstanceOf(Success.class);
  }

  @Test
  void aLineMustBeCoveredByOneLocation() {
    stock.saveLine(new StockLine("RM-GH200", "RAW-A", 20, 0));
    stock.saveLine(new StockLine("RM-GH200", "RAW-B", 20, 0));
    assertThat(materials.reserve(request("REQ-1", new Line("RM-GH200", 30)))).isEqualTo(new Shortage("RM-GH200", 30, 20));
    assertThat(stock.lockMaterial("RM-GH200")).allSatisfy(l -> assertThat(l.reserved()).isZero());
    assertThat(stock.reservationsOfRequest("REQ-1")).isEmpty();
    // the location with the most available stock is chosen
    stock.saveLine(new StockLine("RM-GH200", "RAW-B", 25, 0));
    var ok = (Success) materials.reserve(request("REQ-2", new Line("RM-GH200", 22)));
    assertThat(ok.reservations().get(0).locationId()).isEqualTo("RAW-B");
  }

  @Test
  void invalidRequestsAreRejected() {
    assertThatThrownBy(() -> materials.reserve(request("REQ-1"))).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> materials.reserve(request("REQ-1", new Line("RM-GH200", 0))))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> materials.reserve(request("REQ-1", new Line("RM-GH200", 1), new Line("RM-GH200", 2))))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> materials.reserve(request("REQ-1", new Line("NOPE", 1))))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.NOT_FOUND));
  }

  @Test
  void releaseFreesTheQuantityAndIsIdempotent() {
    materials.reserve(request("REQ-1", new Line("RM-GH200", 5), new Line("KIT-GH200", 2)));
    materials.release("REQ-1");
    assertThat(line("RM-GH200", "RAW-A").reserved()).isZero();
    assertThat(line("KIT-GH200", "RAW-B").reserved()).isZero();
    assertThat(stock.reservationsOfRequest("REQ-1")).allSatisfy(
        r -> assertThat(r.status()).isEqualTo(Reservation.Status.RELEASED));
    assertThat(types()).endsWith(EventTypes.STOCK_CHANGED, EventTypes.STOCK_CHANGED, EventTypes.MATERIAL_RELEASED);
    int events = log.all().size();
    materials.release("REQ-1");
    materials.release("REQ-UNKNOWN");
    assertThat(log.all()).hasSize(events);
  }

  @Test
  void consumeTakesStockOnceAndIsIdempotent() {
    var ok = (Success) materials.reserve(request("REQ-1", new Line("RM-GH200", 5)));
    String id = ok.reservations().get(0).reservationId();
    work.inNewTransaction(() -> materials.consume(id));
    work.inNewTransaction(() -> materials.consume(id));
    assertThat(line("RM-GH200", "RAW-A")).isEqualTo(new StockLine("RM-GH200", "RAW-A", 25, 0));
    assertThat(stock.reservation(id).orElseThrow().status()).isEqualTo(Reservation.Status.CONSUMED);
    // a consumed reservation cannot be released any more
    materials.release("REQ-1");
    assertThat(line("RM-GH200", "RAW-A")).isEqualTo(new StockLine("RM-GH200", "RAW-A", 25, 0));
  }

  @Test
  void receiveRespectsLocationCapacity() {
    // FG-A: capacity 160, 21 on hand
    work.inNewTransaction(() -> materials.receive("FG-GH200", "FG-A", 139));
    assertThat(line("FG-GH200", "FG-A").onHand()).isEqualTo(145);
    assertThatThrownBy(() -> work.inNewTransaction(() -> materials.receive("FG-AW50", "FG-A", 1)))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.CONFLICT));
    assertThatThrownBy(() -> materials.receive("FG-AW50", "FG-A", 0))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.INVALID));
    assertThatThrownBy(() -> materials.receive("NOPE", "FG-A", 1))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.NOT_FOUND));
    assertThatThrownBy(() -> materials.receive("RM-GH200", "FG-A", 1))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.INVALID));
    // a new line is created where the material had none
    work.inNewTransaction(() -> materials.receive("RM-GH200", "RAW-B", 3));
    assertThat(line("RM-GH200", "RAW-B").onHand()).isEqualTo(3);
  }

  @Test
  void replenishmentOrdersOnceBelowReorderPointAndDeliversAfterLeadTime() {
    materials.reserve(request("REQ-1", new Line("RM-GH200", 20))); // available 10 < reorder point 12
    replenishment.step(time.nowMs());
    time.advance(1000);
    replenishment.step(time.nowMs());
    assertThat(replenishment.inTransit("RM-GH200")).isEqualTo(40);
    assertThat(log.all().stream().filter(e -> e.type().equals(EventTypes.DECISION))).hasSize(1);

    time.advance(Replenishment.LEAD_TIME_MS - 1001);
    replenishment.step(time.nowMs());
    assertThat(line("RM-GH200", "RAW-A").onHand()).isEqualTo(30);
    time.advance(1);
    replenishment.step(time.nowMs());
    // RAW-A has the most free room (240 - 120 against 120 - 48)
    assertThat(line("RM-GH200", "RAW-A").onHand()).isEqualTo(70);
    assertThat(replenishment.inTransit("RM-GH200")).isZero();
    replenishment.step(time.nowMs());
    assertThat(replenishment.inTransit("RM-GH200")).isZero(); // 50 available again, no new order
  }

  @Test
  void deliveryWithoutRoomWaitsAndWarnsOnce() {
    stock.saveLine(new StockLine("KIT-GH200", "RAW-B", 10, 6)); // available 4 < 8
    stock.saveLine(new StockLine("RM-AW50", "RAW-A", 130, 0));  // RAW-A: 220 of 240, 20 free
    stock.saveLine(new StockLine("RM-AW50", "RAW-B", 80, 0));   // RAW-B: 80 + 24 + 10 = 114 of 120
    replenishment.step(time.nowMs());
    time.advance(Replenishment.LEAD_TIME_MS);
    replenishment.step(time.nowMs());
    replenishment.step(time.nowMs());
    assertThat(replenishment.inTransit("KIT-GH200")).isEqualTo(30);
    assertThat(log.all().stream().filter(e -> e.type().equals(EventTypes.DECISION)
        && ((Payloads.Decision) e.payload()).reason().contains("kein Platz"))).hasSize(1);
    stock.saveLine(new StockLine("RM-AW50", "RAW-B", 40, 0));
    replenishment.step(time.nowMs());
    assertThat(replenishment.inTransit("KIT-GH200")).isZero();
    assertThat(line("KIT-GH200", "RAW-B").onHand()).isEqualTo(40);
  }

  @Test
  void eventsCarryTypedPayloads() {
    var ok = (Success) materials.reserve(request("REQ-1", new Line("RM-GH200", 5), new Line("KIT-GH200", 1)));
    assertThat(types()).containsExactly(EventTypes.STOCK_CHANGED, EventTypes.STOCK_CHANGED, EventTypes.MATERIAL_RESERVED);
    assertThat(log.all().get(0).payload()).isEqualTo(new Payloads.StockChanged("KIT-GH200", "RAW-B", 24, 1));
    assertThat(log.all().get(1).payload()).isEqualTo(new Payloads.StockChanged("RM-GH200", "RAW-A", 30, 5));
    assertThat(log.all().get(2).payload()).isEqualTo(
        new Payloads.MaterialReserved("PO-0001", "REQ-1", List.of("RES-0001", "RES-0002")));
    assertThat(log.all()).allSatisfy(e -> {
      assertThat(e.correlationId()).isEqualTo("PO-0001");
      assertThat(e.source()).isEqualTo("flowpilot");
    });
    assertThat(ok.requestId()).isEqualTo("REQ-1");

    materials.reserve(request("REQ-2", new Line("RM-GH200", 99)));
    assertThat(log.all().get(log.all().size() - 1).payload())
        .isEqualTo(new Payloads.MaterialShortage("PO-0001", "RM-GH200", 99, 25));
  }

  @Test
  void reservationNumbersHaveNoGapsAfterAShortage() {
    var first = (Success) materials.reserve(request("REQ-1", new Line("RM-GH200", 5)));
    assertThat(materials.reserve(request("REQ-2", new Line("RM-GH200", 1), new Line("KIT-GH200", 99))))
        .isInstanceOf(Shortage.class);
    var second = (Success) materials.reserve(request("REQ-3", new Line("RM-GH200", 5)));
    assertThat(first.reservations().get(0).reservationId()).isEqualTo("RES-0001");
    assertThat(second.reservations().get(0).reservationId()).isEqualTo("RES-0002");
  }

  @Test
  void availabilityIsPerLocation() {
    stock.saveLine(new StockLine("RM-GH200", "RAW-B", 4, 1));
    assertThat(materials.availability("RM-GH200")).extracting(a -> a.locationId() + ":" + a.available())
        .containsExactly("RAW-A:30", "RAW-B:3");
  }
}
