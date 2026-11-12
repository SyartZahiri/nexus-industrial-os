package io.nexus.smartfactory.production;

import static io.nexus.contracts.state.MachineStatus.FAULT;
import static io.nexus.contracts.state.MachineStatus.IDLE;
import static io.nexus.contracts.state.MachineStatus.MAINTENANCE;
import static io.nexus.contracts.state.MachineStatus.REPAIR;
import static io.nexus.contracts.state.MachineStatus.RUNNING;
import static io.nexus.contracts.state.MachineStatus.STOPPED;
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
import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.smartfactory.Harness;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.ProductionOrder;
import io.nexus.smartfactory.testkit.FakeTransportService.FakeTransport;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaultHandlingTest {
  private static final long HOUR = 3_600_000;
  private final Harness h = new Harness();

  private String create(String product, int qty) {
    return h.factory.createOrder(new NewOrder(product, qty, Priority.NORMAL, 8 * HOUR));
  }

  private ProductionOrder order(String id) {
    return h.factory.orders().stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
  }

  private Operation op(String orderId, int seq) {
    return h.factory.operations(orderId).get(seq - 1);
  }

  private List<FakeTransport> transportsOf(String orderId) {
    return h.transports.transports().stream().filter(t -> orderId.equals(t.request().orderId())).toList();
  }

  private void deliverAll() {
    for (FakeTransport t : h.transports.open()) h.transports.deliver(t.id());
  }

  private MachineJob job(String orderId, int seq) {
    return h.machines.jobs().stream().filter(j -> j.orderId().equals(orderId) && j.stepSeq() == seq).findFirst().orElseThrow();
  }

  @Test
  void faultOnAMillingCenterMovesWaitingOperationsAndRedirectsTransports() {
    String id = create("GH200", 4);
    FakeTransport supply = transportsOf(id).getFirst();
    assertThat(supply.to()).isEqualTo(Place.machineIn("CNC-01"));

    h.factory.statusChanged("CNC-01", IDLE, FAULT);

    String alternative = op(id, 1).machineId();
    assertThat(alternative).isIn("CNC-02", "CNC-04");
    assertThat(h.transports.transport(supply.id()).to()).isEqualTo(Place.machineIn(alternative));
    var rescheduled = (Payloads.OrderRescheduled) h.events(EventTypes.ORDER_RESCHEDULED).getFirst().payload();
    assertThat(rescheduled).isEqualTo(new Payloads.OrderRescheduled(id, 1, "CNC-01", alternative, rescheduled.reason()));
    assertThat(rescheduled.reason()).contains("CNC-01").contains("FAULT");
    var decision = (Payloads.Decision) h.events(EventTypes.DECISION).getLast().payload();
    assertThat(decision.chosen()).isEqualTo(alternative);
    assertThat(decision.candidates()).containsExactly("CNC-02", "CNC-04");
    assertThat(h.factory.plan().ops()).noneMatch(p -> p.machineId().equals("CNC-01"));

    h.transports.deliver(supply.id());
    assertThat(job(id, 1).machineId()).isEqualTo(alternative);
  }

  @Test
  void deliveredMaterialIsMovedToTheAlternativeMachine() {
    h.factory.statusChanged("CNC-01", IDLE, STOPPED);
    h.factory.statusChanged("CNC-04", IDLE, STOPPED);
    String running = create("FP12", 2);
    String waiting = create("FP12", 2);
    deliverAll();
    assertThat(op(running, 1).status()).isEqualTo(Operation.Status.RUNNING);
    assertThat(op(waiting, 1).status()).as("CNC-02 is busy").isEqualTo(Operation.Status.READY);
    h.factory.statusChanged("CNC-01", STOPPED, IDLE);
    assertThat(op(waiting, 1).machineId()).as("material is there, it stays").isEqualTo("CNC-02");

    h.factory.statusChanged("CNC-02", RUNNING, FAULT);

    assertThat(op(running, 1).machineId()).isEqualTo("CNC-02");
    assertThat(op(running, 1).status()).isEqualTo(Operation.Status.RUNNING);
    assertThat(op(waiting, 1).machineId()).isEqualTo("CNC-01");
    assertThat(op(waiting, 1).status()).isEqualTo(Operation.Status.WAITING_MATERIAL);
    FakeTransport move = transportsOf(waiting).getLast();
    assertThat(move.request().requestId()).isEqualTo(waiting + "/M1-0001");
    assertThat(move.request().kind()).isEqualTo(TransportKind.WIP);
    assertThat(move.request().materialId()).isEqualTo("RM-FP12");
    assertThat(move.request().quantity()).isEqualTo(2);
    assertThat(move.request().reservationId()).isNull();
    assertThat(move.request().from()).isEqualTo(Place.machineIn("CNC-02"));
    assertThat(move.to()).isEqualTo(Place.machineIn("CNC-01"));

    h.transports.deliver(move.id());
    assertThat(job(waiting, 1).machineId()).isEqualTo("CNC-01");
  }

  @Test
  void runningOperationStaysAndResumesAfterRepair() {
    String id = create("FP12", 2);
    deliverAll();
    h.factory.statusChanged("CNC-02", IDLE, RUNNING);

    h.factory.faultRaised("CNC-02", "OVERTEMP", "Spindel-Übertemperatur", false);
    h.factory.statusChanged("CNC-02", RUNNING, FAULT);

    assertThat(op(id, 1).status()).isEqualTo(Operation.Status.RUNNING);
    assertThat(op(id, 1).machineId()).isEqualTo("CNC-02");
    assertThat(h.events(EventTypes.ORDER_RESCHEDULED)).isEmpty();
    var fault = (Payloads.MachineFault) h.events(EventTypes.MACHINE_FAULT).getFirst().payload();
    assertThat(fault).isEqualTo(new Payloads.MachineFault("CNC-02", "OVERTEMP", "Spindel-Übertemperatur", false));
    assertThat(h.events(EventTypes.MACHINE_FAULT).getFirst().correlationId()).isEqualTo(id);

    h.factory.statusChanged("CNC-02", FAULT, REPAIR);
    h.factory.statusChanged("CNC-02", REPAIR, RUNNING);
    h.factory.jobFinished("CNC-02", id, 1, 2, 0);
    h.factory.statusChanged("CNC-02", RUNNING, IDLE);

    assertThat(op(id, 1).status()).isEqualTo(Operation.Status.DONE);
    assertThat(transportsOf(id).getLast().request().kind()).isEqualTo(TransportKind.WIP);
    assertThat(h.machines.jobs()).hasSize(1);
  }

  @Test
  void qcFaultMakesOrdersWaitWithoutMovingAnything() {
    String id = create("FP12", 2);
    deliverAll();
    h.factory.jobFinished("CNC-02", id, 1, 2, 0);
    FakeTransport wip = transportsOf(id).getLast();
    int transports = h.transports.transports().size();

    h.factory.statusChanged("QC-01", IDLE, FAULT);

    List<NexusEvent> warnings = h.events(EventTypes.DECISION).stream()
        .filter(e -> e.severity() == Severity.WARNING).toList();
    assertThat(warnings).singleElement().satisfies(w -> {
      assertThat(w.correlationId()).isEqualTo(id);
      assertThat(((Payloads.Decision) w.payload()).reason()).isEqualTo("keine Ausweichmaschine für PRUEFEN, Auftrag wartet");
    });
    assertThat(h.events(EventTypes.ORDER_RESCHEDULED)).isEmpty();
    assertThat(h.transports.transport(wip.id()).to()).isEqualTo(Place.machineIn("QC-01"));
    assertThat(h.transports.transports()).hasSize(transports);
    assertThat(op(id, 2).machineId()).isEqualTo("QC-01");

    h.transports.deliver(wip.id());
    assertThat(h.machines.jobs()).as("QC-01 is down").hasSize(1);

    h.factory.statusChanged("QC-01", FAULT, REPAIR);
    h.factory.statusChanged("QC-01", REPAIR, IDLE);
    assertThat(job(id, 2).machineId()).isEqualTo("QC-01");
    h.factory.jobFinished("QC-01", id, 2, 2, 0);
    deliverAll();
    assertThat(order(id).status()).isEqualTo(OrderStatus.COMPLETED);
  }

  @Test
  void scrappingFaultBooksTheBatchAsScrap() {
    String id = create("FP12", 3);
    deliverAll();

    h.factory.faultRaised("CNC-02", "TOOL", "Werkzeugbruch", true);
    h.factory.statusChanged("CNC-02", RUNNING, FAULT);

    assertThat(op(id, 1).status()).isEqualTo(Operation.Status.DONE);
    assertThat(op(id, 1).good()).isZero();
    assertThat(op(id, 1).scrap()).isEqualTo(3);
    assertThat(order(id).status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(order(id).scrapped()).isEqualTo(3);
    assertThat(transportsOf(id)).allMatch(t -> t.request().kind() == TransportKind.SUPPLY);
    assertThat(h.events(EventTypes.MACHINE_FAULT)).singleElement()
        .satisfies(e -> assertThat(e.severity()).isEqualTo(Severity.ERROR));
  }

  @Test
  void faultWithoutAJobOnlyReports() {
    h.factory.faultRaised("QC-01", "VISION", "Sensorstörung Kamera", true);
    assertThat(h.events(EventTypes.MACHINE_FAULT)).singleElement().satisfies(e -> assertThat(e.correlationId()).isNull());
    assertThat(h.events()).hasSize(1);
  }

  @Test
  void machineCommandsAreValidatedAgainstTheStateMachine() {
    assertThatThrownBy(() -> h.factory.startMachine("CNC-01")).isInstanceOf(IllegalTransitionException.class);
    assertThatThrownBy(() -> h.factory.repairMachine("CNC-01")).isInstanceOf(IllegalTransitionException.class);
    h.factory.stopMachine("CNC-01");
    h.factory.maintainMachine("CNC-01");
    h.factory.injectFault("CNC-01", null);
    assertThat(h.machines.commands()).containsExactly("stop CNC-01", "maintain CNC-01", "fault CNC-01 null");

    h.factory.statusChanged("CNC-01", IDLE, STOPPED); // the simulation confirms
    assertThatThrownBy(() -> h.factory.stopMachine("CNC-01")).isInstanceOf(IllegalTransitionException.class);
    h.factory.startMachine("CNC-01");
    h.factory.maintainMachine("CNC-01");

    h.factory.statusChanged("CNC-01", STOPPED, FAULT);
    assertThatThrownBy(() -> h.factory.maintainMachine("CNC-01")).isInstanceOf(IllegalTransitionException.class);
    assertThatThrownBy(() -> h.factory.injectFault("CNC-01", "TOOL")).isInstanceOf(IllegalTransitionException.class);
    h.factory.repairMachine("CNC-01");
    assertThat(h.machines.commands()).endsWith("start CNC-01", "maintain CNC-01", "repair CNC-01");

    assertThatThrownBy(() -> h.factory.stopMachine("XX-01"))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.NOT_FOUND));
  }

  @Test
  void everyRescheduleIsTraceable() {
    String a = create("GH200", 2);
    String b = create("AW50", 2);
    h.factory.statusChanged("CNC-01", IDLE, FAULT);
    h.factory.statusChanged("CNC-02", IDLE, MAINTENANCE);
    h.factory.statusChanged("CNC-03", IDLE, STOPPED);

    List<NexusEvent> rescheduled = h.events(EventTypes.ORDER_RESCHEDULED);
    assertThat(rescheduled).hasSizeGreaterThanOrEqualTo(3);
    for (NexusEvent r : rescheduled) {
      var p = (Payloads.OrderRescheduled) r.payload();
      assertThat(r.correlationId()).isEqualTo(p.orderId());
      assertThat(h.events(EventTypes.DECISION)).as(p.toString())
          .anySatisfy(d -> {
            assertThat(d.correlationId()).isEqualTo(p.orderId());
            assertThat(((Payloads.Decision) d.payload()).subject()).isEqualTo(p.orderId() + "/" + p.stepSeq());
          });
    }
    assertThat(rescheduled).extracting(NexusEvent::correlationId).contains(a, b);
  }
}
