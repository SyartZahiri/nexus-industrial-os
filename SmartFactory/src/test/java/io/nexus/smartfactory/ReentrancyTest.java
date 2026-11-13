package io.nexus.smartfactory;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.testkit.FakeMachineControl;
import io.nexus.smartfactory.testkit.FakeMaterialService;
import io.nexus.smartfactory.testkit.FakeTransportService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FlowPilot reserves in a unit of its own and its stock.changed reaches SmartFactory at once, while SmartFactory
 * is still in the middle of its own work. SmartFactory must never act on a half-built order.
 */
class ReentrancyTest {
  final MemoryUnitOfWork work = new MemoryUnitOfWork();
  final MemoryEventLog log = new MemoryEventLog(work);
  final SimTime time = new SimTime();
  final EventIds eventIds = new EventIds(1);
  final EventBus bus = new EventBus(log, work, time, () -> eventIds);
  final FakeMaterialService stock = new FakeMaterialService();
  final FakeTransportService transports = new FakeTransportService(bus);
  final FakeMachineControl machines = new FakeMachineControl();

  final boolean[] failReservations = {false};

  /** Like FlowPilot: a successful reservation commits on its own and announces the stock change right away. */
  final MaterialService flowPilotLike = new MaterialService() {
    @Override public ReservationResult reserve(ReservationRequest r) {
      if (failReservations[0]) throw new IllegalStateException("Datenbank kurz weg");
      ReservationResult[] result = new ReservationResult[1];
      work.inNewTransaction(() -> {
        result[0] = stock.reserve(r);
        if (result[0] instanceof ReservationResult.Success) {
          for (Line line : r.lines()) stockChanged(line.materialId());
        }
      });
      return result[0];
    }
    @Override public void release(String requestId) { stock.release(requestId); }
    @Override public List<Availability> availability(String materialId) { return stock.availability(materialId); }
  };

  final SmartFactory factory = SmartFactory.inMemory(bus, work, time, new MemoryIdSource(work), flowPilotLike,
      transports, machines);

  void stockChanged(String materialId) {
    bus.publish(EventTypes.STOCK_CHANGED, "flowpilot", Severity.INFO, null,
        new Payloads.StockChanged(materialId, "RAW-A", 1000, 0));
  }

  @Test
  void anOrderBeingCreatedIsNotReleasedByItsOwnReservation() {
    String id = factory.createOrder(new NewOrder("FP12", 3, Priority.NORMAL, 3_600_000));
    assertThat(factory.order(id).status()).isEqualTo(OrderStatus.MATERIAL_PENDING);
    assertThat(factory.operations(id).get(0).status()).isEqualTo(Operation.Status.WAITING_MATERIAL);
    assertThat(machines.jobs()).isEmpty();
  }

  /** The shortage retry runs from an event delivery, not from a command: the same guard must hold there. */
  @Test
  void aShortageRetriedFromAnEventIsNotReleasedByTheRetrysOwnReservation() {
    stock.setStock("RM-FP12", "RAW-A", 0);
    String id = factory.createOrder(new NewOrder("FP12", 3, Priority.NORMAL, 3_600_000));
    assertThat(factory.order(id).status()).isEqualTo(OrderStatus.SHORTAGE);

    stock.setStock("RM-FP12", "RAW-A", 1000);
    work.inNewTransaction(() -> stockChanged("RM-FP12"));      // goods receipt in FlowPilot

    assertThat(factory.order(id).status()).isEqualTo(OrderStatus.MATERIAL_PENDING);
    assertThat(factory.operations(id).get(0).status()).isEqualTo(Operation.Status.WAITING_MATERIAL);
    assertThat(machines.jobs()).isEmpty();
  }

  /** retryFailed delivers outside the bus's queue, so a nested commit is delivered straight back into SmartFactory. */
  @Test
  void aShortageRetriedByTheBusAfterAFailureIsNotReleasedByTheRetrysOwnReservation() {
    stock.setStock("RM-FP12", "RAW-A", 0);
    String id = factory.createOrder(new NewOrder("FP12", 3, Priority.NORMAL, 3_600_000));
    stock.setStock("RM-FP12", "RAW-A", 1000);
    failReservations[0] = true;
    work.inNewTransaction(() -> stockChanged("RM-FP12"));      // SmartFactory fails on this delivery
    assertThat(factory.order(id).status()).isEqualTo(OrderStatus.SHORTAGE);

    failReservations[0] = false;
    bus.retryFailed();

    assertThat(factory.order(id).status()).isEqualTo(OrderStatus.MATERIAL_PENDING);
    assertThat(factory.operations(id).get(0).status()).isEqualTo(Operation.Status.WAITING_MATERIAL);
    assertThat(machines.jobs()).isEmpty();
  }

  /** A machine that refuses a job must not leave an operation RUNNING that no machine works on. */
  @Test
  void aJobTheMachineRefusesIsHandedOverAgainLater() {
    boolean[] refuse = {true};
    machines.onStartJob(job -> {
      if (refuse[0]) throw new IllegalStateException("Maschine nicht erreichbar");
    });
    String id = factory.createOrder(new NewOrder("FP12", 3, Priority.NORMAL, 3_600_000));
    for (var t : transports.open()) transports.deliver(t.id());
    assertThat(factory.operations(id).get(0).status()).isEqualTo(Operation.Status.READY);

    refuse[0] = false;
    factory.setDispatchRule(factory.dispatchRule());          // any later command moves production on
    assertThat(factory.operations(id).get(0).status()).isEqualTo(Operation.Status.RUNNING);
    assertThat(machines.jobs()).hasSize(2);
  }
}
