package io.nexus.smartfactory.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.smartfactory.Harness;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.ProductionOrder;
import io.nexus.smartfactory.testkit.FakeTransportService.FakeTransport;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderServiceTest {
  private static final long HOUR = 3_600_000;
  private final Harness h = new Harness();

  private String create(String product, int qty) {
    return h.factory.createOrder(new NewOrder(product, qty, Priority.NORMAL, 8 * HOUR));
  }

  private ProductionOrder order(String id) {
    return h.factory.orders().stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
  }

  private static void assertKind(Runnable call, DomainException.Kind kind) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(kind));
  }

  @Test
  void createdOrderReservesAndRequestsOneTransportPerMaterial() {
    String id = create("GH200", 5);

    assertThat(id).isEqualTo("PO-0001");
    assertThat(order(id).status()).isEqualTo(OrderStatus.MATERIAL_PENDING);
    assertThat(h.materials.requests()).singleElement().satisfies(r -> {
      assertThat(r.requestId()).isEqualTo("PO-0001/RES");
      assertThat(r.orderId()).isEqualTo(id);
      assertThat(r.lines()).containsExactly(new Line("RM-GH200", 5), new Line("KIT-GH200", 5));
    });
    List<FakeTransport> transports = h.transports.transports();
    assertThat(transports).hasSize(2);
    assertThat(transports).allSatisfy(t -> {
      assertThat(t.request().kind()).isEqualTo(TransportKind.SUPPLY);
      assertThat(t.request().orderId()).isEqualTo(id);
      assertThat(t.request().quantity()).isEqualTo(5);
      assertThat(t.request().reservationId()).isEqualTo("PO-0001/RES/" + t.request().materialId());
    });
    assertThat(transports.get(0).request().requestId()).isEqualTo("PO-0001/S1/RM-GH200");
    assertThat(transports.get(0).request().from()).isEqualTo(Place.storage("RAW-A"));
    assertThat(transports.get(0).to()).isEqualTo(Place.machineIn("CNC-01"));
    assertThat(transports.get(1).request().requestId()).isEqualTo("PO-0001/S2/KIT-GH200");
    assertThat(transports.get(1).request().from()).isEqualTo(Place.storage("RAW-B"));
    assertThat(transports.get(1).to()).isEqualTo(Place.machineIn("ROB-01"));

    assertThat(h.factory.operations(id)).extracting(Operation::status).containsExactly(
        Operation.Status.WAITING_MATERIAL, Operation.Status.WAITING_MATERIAL, Operation.Status.PLANNED);
    assertThat(h.factory.operations(id)).extracting(Operation::machineId).containsExactly("CNC-01", "ROB-01", "QC-01");
    assertThat(h.factory.plan().ops()).hasSize(3);
  }

  @Test
  void shortageWaitsAndRetriesOnStockChange() {
    h.materials.setStock("RM-GH200", "RAW-A", 2);
    String id = create("GH200", 5);

    assertThat(order(id).status()).isEqualTo(OrderStatus.SHORTAGE);
    assertThat(h.transports.transports()).isEmpty();
    var shortage = (Payloads.OrderStatusChanged) h.events(EventTypes.ORDER_SHORTAGE).getFirst().payload();
    assertThat(shortage.reason()).contains("RM-GH200").contains("5").contains("2");

    stockChanged("RM-FP12");
    assertThat(order(id).status()).as("an unrelated receipt").isEqualTo(OrderStatus.SHORTAGE);
    assertThat(h.materials.requests()).hasSize(1);

    h.materials.setStock("RM-GH200", "RAW-A", 30);
    stockChanged("RM-GH200");
    assertThat(order(id).status()).isEqualTo(OrderStatus.MATERIAL_PENDING);
    assertThat(h.transports.transports()).hasSize(2);
    assertThat(h.types(id)).containsSubsequence(EventTypes.ORDER_MATERIAL_PENDING, EventTypes.ORDER_SHORTAGE,
        EventTypes.ORDER_MATERIAL_PENDING);
  }

  private void stockChanged(String material) {
    h.bus.publish(EventTypes.STOCK_CHANGED, "flowpilot", Severity.INFO, null,
        new Payloads.StockChanged(material, "RAW-A", 30, 0));
  }

  @Test
  void invalidInputIsRejectedWithoutStoring() {
    assertKind(() -> create("GH200", 0), DomainException.Kind.INVALID);
    assertKind(() -> create("GH200", -1), DomainException.Kind.INVALID);
    assertKind(() -> create("GH200", 101), DomainException.Kind.INVALID);
    assertKind(() -> create("XX-1", 5), DomainException.Kind.NOT_FOUND);
    assertKind(() -> h.factory.createOrder(new NewOrder("GH200", 5, Priority.NORMAL, 0)), DomainException.Kind.INVALID);
    assertKind(() -> h.factory.createOrder(new NewOrder("GH200", 5, null, HOUR)), DomainException.Kind.INVALID);

    assertThat(h.factory.orders()).isEmpty();
    assertThat(h.events()).isEmpty();
    assertThat(h.materials.requests()).isEmpty();
    assertThat(create("GH200", 100)).as("no number was used up").isEqualTo("PO-0001");
  }

  @Test
  void priorityChangeReplans() {
    String first = create("FP12", 5);
    String second = create("FP12", 5);
    assertThat(h.factory.plan().op(first, 2).startMs()).isLessThan(h.factory.plan().op(second, 2).startMs());

    h.factory.changeOrder(second, new OrderChange(Priority.EILIG, null, null));

    assertThat(order(second).priority()).isEqualTo(Priority.EILIG);
    assertThat(h.factory.plan().op(second, 2).startMs()).isLessThan(h.factory.plan().op(first, 2).startMs());
    assertThat(h.factory.operations(second).get(1).plannedStartMs()).isEqualTo(h.factory.plan().op(second, 2).startMs());
    NexusEvent decision = h.events(EventTypes.DECISION).getLast();
    assertThat(decision.correlationId()).isEqualTo(second);
    assertThat(((Payloads.Decision) decision.payload()).chosen()).contains("EILIG");
  }

  @Test
  void quantityChangeOutsideShortageIsAConflict() {
    String id = create("GH200", 5);
    assertKind(() -> h.factory.changeOrder(id, new OrderChange(null, 3, null)), DomainException.Kind.CONFLICT);
    assertKind(() -> h.factory.changeOrder(id, new OrderChange(null, null, HOUR)), DomainException.Kind.CONFLICT);
    assertThat(order(id).quantity()).isEqualTo(5);
    assertKind(() -> h.factory.changeOrder("PO-9999", new OrderChange(Priority.HOCH, null, null)),
        DomainException.Kind.NOT_FOUND);

    h.materials.setStock("RM-MT7", "RAW-A", 3);
    String waiting = create("MT7", 5);
    assertThat(order(waiting).status()).isEqualTo(OrderStatus.SHORTAGE);
    assertKind(() -> h.factory.changeOrder(waiting, new OrderChange(null, 0, null)), DomainException.Kind.INVALID);
    h.factory.changeOrder(waiting, new OrderChange(null, 3, 2 * HOUR));
    assertThat(order(waiting).quantity()).isEqualTo(3);
    assertThat(order(waiting).dueAtMs()).isEqualTo(2 * HOUR);
    assertThat(order(waiting).status()).as("smaller quantity fits the stock").isEqualTo(OrderStatus.MATERIAL_PENDING);

    h.factory.cancelOrder(id);
    assertKind(() -> h.factory.changeOrder(id, new OrderChange(Priority.HOCH, null, null)), DomainException.Kind.CONFLICT);
  }

  @Test
  void cancelReleasesReservationAndCancelsTransportsNotPickedUp() {
    String id = create("GH200", 5);
    String pickedUp = h.transports.transports().get(0).id();
    String waiting = h.transports.transports().get(1).id();
    h.transports.pickUp(pickedUp);

    h.factory.cancelOrder(id);

    assertThat(order(id).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(h.materials.released()).containsExactly("PO-0001/RES");
    assertThat(h.transports.transport(waiting).status()).isEqualTo("CANCELLED");
    assertThat(h.transports.transport(pickedUp).status()).isEqualTo("PICKED_UP");
    assertThat(h.factory.operations(id)).extracting(Operation::status).containsOnly(Operation.Status.CANCELLED);
    assertThat(h.types(id)).endsWith(EventTypes.DECISION, EventTypes.ORDER_CANCELLED);
    var decision = (Payloads.Decision) h.events(EventTypes.DECISION).getLast().payload();
    assertThat(decision.subject()).isEqualTo(pickedUp);
    assertThat(h.factory.plan().ops()).noneMatch(p -> p.orderId().equals(id));

    h.factory.cancelOrder(id); // cancelling twice changes nothing
    assertThat(h.events(EventTypes.ORDER_CANCELLED)).hasSize(1);
    assertKind(() -> h.factory.cancelOrder("PO-9999"), DomainException.Kind.NOT_FOUND);
  }

  @Test
  void eventsCarryTypedPayloadsAndTheOrderIdAsCorrelation() {
    h.time.advance(1_000);
    String id = create("FP12", 2);

    List<NexusEvent> own = h.events().stream().filter(e -> e.source().equals("smartfactory")).toList();
    assertThat(own).isNotEmpty().allSatisfy(e -> assertThat(e.correlationId()).isEqualTo(id));
    assertThat(own.get(0).payload()).isEqualTo(new Payloads.OrderCreated(id, "FP12", 2, Priority.NORMAL, 1_000 + 8 * HOUR));
    assertThat(own.get(1).type()).isEqualTo(EventTypes.ORDER_MATERIAL_PENDING);
    assertThat(own.get(1).payload()).isInstanceOfSatisfying(Payloads.OrderStatusChanged.class, p -> {
      assertThat(p.orderId()).isEqualTo(id);
      assertThat(p.from()).isEqualTo(OrderStatus.CREATED);
      assertThat(p.to()).isEqualTo(OrderStatus.MATERIAL_PENDING);
    });
  }
}
