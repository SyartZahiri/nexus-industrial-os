package io.nexus.smartfactory.production;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.smartfactory.Harness;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.ProductionOrder;
import io.nexus.smartfactory.testkit.FakeTransportService.FakeTransport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionFlowTest {
  private static final long HOUR = 3_600_000;
  private final Harness h = new Harness();
  private int finishedJobs;

  private String create(String product, int qty) {
    return h.factory.createOrder(new NewOrder(product, qty, Priority.NORMAL, 8 * HOUR));
  }

  private ProductionOrder order(String id) {
    return h.factory.orders().stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
  }

  private List<FakeTransport> transportsOf(String orderId) {
    return h.transports.transports().stream().filter(t -> orderId.equals(t.request().orderId())).toList();
  }

  private void deliverAll() {
    for (FakeTransport t : h.transports.open()) h.transports.deliver(t.id());
  }

  /** Plays FlowPilot and the machines until nothing moves any more; every job yields all parts good. */
  private void runToEnd() {
    boolean moved = true;
    while (moved) {
      moved = false;
      if (!h.transports.open().isEmpty()) {
        deliverAll();
        moved = true;
      }
      List<MachineJob> jobs = h.machines.jobs();
      while (finishedJobs < jobs.size()) {
        MachineJob job = jobs.get(finishedJobs++);
        finish(job, job.quantity(), 0);
        moved = true;
      }
    }
  }

  /** Reports the job finished; assembly cells then hand over on the conveyor to QC. */
  private void finish(MachineJob job, int good, int scrap) {
    h.factory.jobFinished(job.machineId(), job.orderId(), job.stepSeq(), good, scrap);
    if (job.machineId().startsWith("ROB") && good > 0) {
      h.factory.conveyorDelivered("QC-01", job.orderId(), job.stepSeq() + 1, good);
    }
  }

  private List<OrderStatus> statuses(String orderId) {
    List<OrderStatus> out = new ArrayList<>(List.of(OrderStatus.CREATED));
    for (NexusEvent e : h.events()) {
      if (e.payload() instanceof Payloads.OrderStatusChanged c && c.orderId().equals(orderId)) out.add(c.to());
    }
    return out;
  }

  private List<NexusEvent> warnings() {
    return h.events(EventTypes.DECISION).stream().filter(e -> e.severity() == Severity.WARNING).toList();
  }

  @Test
  void everyProductRunsFromOrderToStock() {
    List<String> ids = new ArrayList<>();
    for (String product : new String[] {"AW50", "FP12", "GH200", "MT7"}) ids.add(create(product, 3));

    runToEnd();

    for (String id : ids) {
      ProductionOrder o = order(id);
      assertThat(statuses(id)).as(id).containsExactly(OrderStatus.CREATED, OrderStatus.MATERIAL_PENDING,
          OrderStatus.RELEASED, OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED);
      assertThat(o.good()).isEqualTo(3);
      assertThat(o.completedAtMs()).isNotNull();
      assertThat(h.factory.operations(id)).allSatisfy(op -> {
        assertThat(op.status()).isEqualTo(Operation.Status.DONE);
        assertThat(op.good()).isEqualTo(3);
      });
      List<MachineJob> jobs = h.machines.jobs().stream().filter(j -> j.orderId().equals(id)).toList();
      assertThat(jobs).extracting(MachineJob::stepSeq).isSorted().doesNotHaveDuplicates()
          .hasSize(h.factory.operations(id).size());
      assertThat(jobs).extracting(MachineJob::machineId)
          .containsExactlyElementsOf(h.factory.operations(id).stream().map(Operation::machineId).toList());
      FakeTransport putaway = transportsOf(id).getLast();
      assertThat(putaway.request().kind()).isEqualTo(TransportKind.PUTAWAY);
      assertThat(putaway.request().from()).isEqualTo(Place.machineOut("QC-01"));
      assertThat(putaway.to()).isEqualTo(Place.storage("FG-A"));
      assertThat(putaway.request().materialId()).isEqualTo("FG-" + o.productId());
      assertThat(putaway.request().requestId()).isEqualTo(id + "/P");
    }
    // AW50: turning, then milling on another machine: one WIP transport between them, nothing for QC's conveyor
    String aw50 = ids.get(0);
    assertThat(transportsOf(aw50)).extracting(t -> t.request().kind())
        .containsExactly(TransportKind.SUPPLY, TransportKind.WIP, TransportKind.WIP, TransportKind.PUTAWAY);
    FakeTransport wip = transportsOf(aw50).get(1);
    assertThat(wip.request().requestId()).isEqualTo(aw50 + "/W2");
    assertThat(wip.request().from()).isEqualTo(Place.machineOut("CNC-03"));
    assertThat(wip.request().materialId()).isNull();
    // GH200: assembly hands over to QC on the conveyor, no AGV in between
    String gh200 = ids.get(2);
    assertThat(transportsOf(gh200)).extracting(t -> t.request().kind())
        .containsExactly(TransportKind.SUPPLY, TransportKind.SUPPLY, TransportKind.WIP, TransportKind.PUTAWAY);
    assertThat(h.events(EventTypes.OPERATION_STARTED)).hasSize(11);
    assertThat(h.events(EventTypes.OPERATION_COMPLETED)).hasSize(11);
    assertThat(warnings()).isEmpty();
  }

  @Test
  void secondDeliveryOfTheSameTransportHasNoEffect() {
    String id = create("FP12", 2);
    FakeTransport supply = h.transports.transports().getFirst();
    h.transports.deliver(supply.id());
    assertThat(h.machines.jobs()).hasSize(1);
    int events = h.events().size();

    h.transports.deliver(supply.id()); // FlowPilot reports it again, as a new event
    NexusEvent delivered = h.events(EventTypes.TRANSPORT_DELIVERED).getFirst();
    h.work.inNewTransaction(() -> h.bus.redeliver(delivered.eventId())); // the bus delivers the first one again

    assertThat(h.machines.jobs()).hasSize(1);
    assertThat(h.events()).hasSize(events + 1); // only the second transport.delivered itself
    assertThat(order(id).status()).isEqualTo(OrderStatus.IN_PROGRESS);
  }

  @Test
  void unknownTransportIsIgnored() {
    h.bus.publish(EventTypes.TRANSPORT_DELIVERED, "flowpilot", Severity.SUCCESS, null,
        new Payloads.TransportDelivered("TO-4711", TransportKind.SUPPLY, null, "RM-FP12", 1, Place.machineIn("CNC-02")));
    assertThat(h.events()).hasSize(1);
  }

  @Test
  void machineGetsTheNextJobOnlyWhenIdle() {
    String first = create("FP12", 2);
    String second = create("FP12", 2);
    deliverAll();
    assertThat(h.machines.jobs()).hasSize(2); // milling of both, on two machines

    finish(h.machines.jobs().get(0), 2, 0);
    deliverAll();
    MachineJob qcFirst = h.machines.jobs().get(2);
    assertThat(qcFirst.machineId()).isEqualTo("QC-01");
    assertThat(qcFirst.orderId()).isEqualTo(first);
    h.factory.statusChanged("QC-01", MachineStatus.IDLE, MachineStatus.RUNNING);

    finish(h.machines.jobs().get(1), 2, 0);
    deliverAll();
    assertThat(h.machines.jobs()).as("QC-01 is busy").hasSize(3);

    h.factory.jobFinished("QC-01", first, 2, 2, 0);
    assertThat(h.machines.jobs()).as("status is still RUNNING").hasSize(3);

    h.factory.statusChanged("QC-01", MachineStatus.RUNNING, MachineStatus.IDLE);
    assertThat(h.machines.jobs()).hasSize(4);
    assertThat(h.machines.jobs().get(3).orderId()).isEqualTo(second);
    assertThat(h.events(EventTypes.MACHINE_STATUS)).hasSize(2);
  }

  @Test
  void setupIsRequestedOnlyOnProductChange() {
    String a = create("FP12", 2);
    runToEnd();
    String b = create("FP12", 3);
    runToEnd();
    String c = create("AW50", 1);
    runToEnd();

    MachineJob millA = job(a, 1);
    MachineJob millB = job(b, 1);
    MachineJob millC = job(c, 2);
    assertThat(millA.machineId()).isEqualTo("CNC-02");
    assertThat(millA.setupMs()).isEqualTo(30_000);
    assertThat(millA.processMs()).isEqualTo(2 * 55_000);
    assertThat(millB.machineId()).isEqualTo("CNC-02");
    assertThat(millB.setupMs()).as("same product again").isZero();
    assertThat(millC.machineId()).isEqualTo("CNC-02");
    assertThat(millC.setupMs()).isEqualTo(30_000);
    assertThat(job(a, 2).setupMs()).as("QC needs no setup").isZero();
  }

  private MachineJob job(String orderId, int seq) {
    return h.machines.jobs().stream().filter(j -> j.orderId().equals(orderId) && j.stepSeq() == seq).findFirst().orElseThrow();
  }

  @Test
  void scrapReducesTheQuantityThatMovesOn() {
    String id = create("GH200", 5);
    deliverAll();
    finish(job(id, 1), 4, 1);
    FakeTransport wip = transportsOf(id).get(2);
    assertThat(wip.request().kind()).isEqualTo(TransportKind.WIP);
    assertThat(wip.request().quantity()).isEqualTo(4);
    deliverAll();
    assertThat(job(id, 2).quantity()).isEqualTo(4);
    finish(job(id, 2), 4, 0);
    assertThat(job(id, 3).quantity()).isEqualTo(4);
    finish(job(id, 3), 3, 1);

    assertThat(transportsOf(id).getLast().request().quantity()).isEqualTo(3);
    deliverAll();
    ProductionOrder o = order(id);
    assertThat(o.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(o.good()).isEqualTo(3);
    assertThat(o.scrapped()).isEqualTo(2);
    assertThat(h.factory.operations(id)).extracting(Operation::scrap).containsExactly(1, 0, 1);
  }

  @Test
  void zeroGoodPartsCompletesWithoutPutaway() {
    String id = create("FP12", 2);
    deliverAll();
    finish(job(id, 1), 2, 0);
    deliverAll();
    finish(job(id, 2), 0, 2);

    assertThat(order(id).status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(order(id).good()).isZero();
    assertThat(transportsOf(id)).noneMatch(t -> t.request().kind() == TransportKind.PUTAWAY);
  }

  @Test
  void zeroGoodPartsBeforeTheLastStepEndsTheOrder() {
    String id = create("GH200", 2);
    deliverAll();
    finish(job(id, 1), 0, 2);

    assertThat(order(id).status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(order(id).scrapped()).isEqualTo(2);
    assertThat(h.factory.operations(id)).extracting(Operation::status)
        .containsExactly(Operation.Status.DONE, Operation.Status.CANCELLED, Operation.Status.CANCELLED);
    assertThat(h.materials.released()).containsExactly(id + "/RES");
    assertThat(transportsOf(id)).noneMatch(t -> t.request().kind() != TransportKind.SUPPLY);
  }

  @Test
  void finishedJobOfACancelledOrderCreatesNoTransport() {
    String id = create("FP12", 2);
    deliverAll();
    assertThat(h.factory.operations(id).getFirst().status()).isEqualTo(Operation.Status.RUNNING);
    h.factory.cancelOrder(id);
    int transports = h.transports.transports().size();

    h.factory.jobFinished("CNC-02", id, 1, 2, 0);

    assertThat(h.transports.transports()).hasSize(transports);
    assertThat(h.factory.operations(id).getFirst().status()).isEqualTo(Operation.Status.DONE);
    assertThat(order(id).status()).isEqualTo(OrderStatus.CANCELLED);
    String next = create("FP12", 1);
    deliverAll();
    assertThat(job(next, 1).machineId()).as("the machine is free again").isEqualTo("CNC-02");
  }

  @Test
  void feedbackForUnknownOrderOnlyWarns() {
    String id = create("FP12", 1);
    deliverAll();
    int jobs = h.machines.jobs().size();

    h.factory.jobFinished("CNC-01", "PO-9999", 1, 1, 0);
    h.factory.conveyorDelivered("QC-01", "PO-9999", 2, 1);
    h.factory.jobFinished("CNC-01", id, 1, 1, 0);   // wrong machine
    h.factory.jobFinished("CNC-02", id, 2, 1, 0);   // step not running
    h.factory.conveyorDelivered("QC-01", id, 2, 1); // FP12 does not come over a conveyor
    assertThat(warnings()).hasSize(5).allSatisfy(w -> assertThat(w.payload()).isInstanceOf(Payloads.Decision.class));
    assertThat(warnings().getFirst().correlationId()).isEqualTo("PO-9999");
    assertThat(h.factory.operations(id).getFirst().status()).isEqualTo(Operation.Status.RUNNING);

    runToEnd();
    assertThat(order(id).status()).isEqualTo(OrderStatus.COMPLETED);
    h.factory.jobFinished("QC-01", id, 2, 1, 0); // completed order
    assertThat(warnings()).hasSize(6);
    assertThat(h.machines.jobs()).hasSize(jobs + 1);
  }
}
