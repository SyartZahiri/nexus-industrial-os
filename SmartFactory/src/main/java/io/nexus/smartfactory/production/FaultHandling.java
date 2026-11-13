package io.nexus.smartfactory.production;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.port.MachineControl;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.smartfactory.orders.OrderService;
import io.nexus.smartfactory.planning.Plan;
import io.nexus.smartfactory.store.MachineRecord;
import io.nexus.smartfactory.store.MachineStore;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OrderStore;
import java.util.List;
import java.util.function.Supplier;

/**
 * Machine faults and commands. A machine leaving service triggers a replan; OrderService.replan moves waiting
 * operations with their containers to an alternative machine. Running operations stay and resume after repair.
 */
public final class FaultHandling {
  private final OrderStore orders;
  private final MachineStore machines;
  private final OrderService service;
  private final ProductionFlow flow;
  private final Supplier<MachineControl> control;

  public FaultHandling(OrderStore orders, MachineStore machines, OrderService service, ProductionFlow flow,
                       Supplier<MachineControl> control) {
    this.orders = orders;
    this.machines = machines;
    this.service = service;
    this.flow = flow;
    this.control = control;
  }

  /** Reported right before the machine goes to FAULT. A scrapped batch is booked here: no jobFinished follows. */
  public void faultRaised(String machineId, String faultTypeId, String label, boolean scrappedWorkpiece) {
    Operation running = null;
    for (Operation op : orders.operationsOnMachine(machineId)) if (op.status() == Operation.Status.RUNNING) running = op;
    service.publish(EventTypes.MACHINE_FAULT, Severity.ERROR, running == null ? null : running.orderId(),
        new Payloads.MachineFault(machineId, faultTypeId, label, scrappedWorkpiece));
    if (scrappedWorkpiece && running != null) {
      int quantity = flow.jobQuantity(service.order(running.orderId()), running);
      flow.jobFinished(machineId, running.orderId(), running.seq(), 0, quantity);
    }
  }

  /** Called after the new state is stored, with the state the machine had before. */
  public void afterStatusChange(String machineId, MachineStatus before, MachineStatus now) {
    boolean wasAvailable = before == null || OrderService.available(before);
    boolean isAvailable = OrderService.available(now);
    if (wasAvailable == isAvailable) return;
    Plan plan = service.replan();
    if (isAvailable) return;
    for (Operation op : orders.operationsOnMachine(machineId)) {
      if (op.status() == Operation.Status.RUNNING || plan.unplannable(op.orderId(), op.seq()) == null) continue;
      service.decision(op.orderId(), Severity.WARNING, op.orderId() + "/" + op.seq(), machineId, List.of(),
          "keine Ausweichmaschine für " + op.process() + ", Auftrag wartet");
    }
  }

  public MachineStatus status(String machineId) {
    for (MachineRecord r : machines.machines()) if (r.id().equals(machineId)) return r.status();
    throw DomainException.notFound("Maschine", machineId);
  }

  // ---- commands: checked against the current state; the state changes only when the machine reports back --

  public void start(String machineId) {
    MachineStatus s = status(machineId);
    if (s != MachineStatus.STOPPED) throw new IllegalTransitionException(machineId, s, MachineStatus.IDLE);
    control.get().start(machineId);
  }

  public void stop(String machineId) {
    MachineStatus s = status(machineId);
    boolean operating = s == MachineStatus.IDLE || s == MachineStatus.SETUP || s == MachineStatus.RUNNING
        || s == MachineStatus.BLOCKED;
    if (!operating) throw new IllegalTransitionException(machineId, s, MachineStatus.STOPPED);
    control.get().stop(machineId);
  }

  public void injectFault(String machineId, String faultTypeId) {
    MachineStatus s = status(machineId);
    if (!MachineStatus.RULES.can(s, MachineStatus.FAULT)) throw new IllegalTransitionException(machineId, s, MachineStatus.FAULT);
    control.get().injectFault(machineId, faultTypeId);
  }

  public void repair(String machineId) {
    MachineStatus s = status(machineId);
    if (s != MachineStatus.FAULT) throw new IllegalTransitionException(machineId, s, MachineStatus.REPAIR);
    control.get().repair(machineId);
  }

  public void maintain(String machineId) {
    MachineStatus s = status(machineId);
    if (s != MachineStatus.IDLE && s != MachineStatus.STOPPED) {
      throw new IllegalTransitionException(machineId, s, MachineStatus.MAINTENANCE);
    }
    control.get().maintain(machineId);
  }
}
