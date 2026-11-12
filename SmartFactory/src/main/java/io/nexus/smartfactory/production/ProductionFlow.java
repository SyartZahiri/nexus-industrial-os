package io.nexus.smartfactory.production;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.port.MachineControl;
import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.port.TransportService.TransportRequest;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.smartfactory.catalog.Catalog;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.orders.OrderService;
import io.nexus.smartfactory.store.MachineRecord;
import io.nexus.smartfactory.store.MachineStore;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OperationMaterial;
import io.nexus.smartfactory.store.OrderStore;
import io.nexus.smartfactory.store.ProductionOrder;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * From released material to stock: readiness, machine jobs, hand-over between steps and putaway.
 * Runs inside the caller's unit of work.
 */
public final class ProductionFlow {
  public static final String FINISHED_GOODS = "FG-A";

  private final OrderStore orders;
  private final MachineStore machines;
  private final OrderService service;
  private final Catalog catalog;
  private final SimTime time;
  private final Supplier<MaterialService> materials;
  private final Supplier<TransportService> transports;
  private final Supplier<MachineControl> control;

  public ProductionFlow(OrderStore orders, MachineStore machines, OrderService service, Catalog catalog, SimTime time,
                        Supplier<MaterialService> materials, Supplier<TransportService> transports,
                        Supplier<MachineControl> control) {
    this.orders = orders;
    this.machines = machines;
    this.service = service;
    this.catalog = catalog;
    this.time = time;
    this.materials = materials;
    this.transports = transports;
    this.control = control;
  }

  /** transport.delivered: marks the container; a putaway completes the order. Repeated deliveries do nothing. */
  public void delivered(Payloads.TransportDelivered d) {
    if (d.kind() == TransportKind.PUTAWAY) {
      Optional<ProductionOrder> o = d.orderId() == null ? Optional.empty() : orders.order(d.orderId());
      if (o.isPresent() && o.get().status() == OrderStatus.IN_PROGRESS) complete(o.get());
      return;
    }
    Optional<OperationMaterial> found = orders.materialByTransport(d.transportId());
    if (found.isEmpty() || found.get().delivered()) return;
    OperationMaterial m = found.get();
    orders.saveMaterial(new OperationMaterial(m.id(), m.orderId(), m.stepSeq(), m.kind(), m.materialId(), m.quantity(),
        m.transportId(), true));
  }

  /** Marks ready operations, releases orders whose first step is ready and starts jobs on idle machines. */
  public void advance() {
    for (ProductionOrder o : orders.open()) {
      if (o.status() == OrderStatus.SHORTAGE || o.status() == OrderStatus.CREATED) continue;
      List<Operation> ops = orders.operations(o.id());
      for (Operation op : ops) {
        if (op.status() != Operation.Status.PLANNED && op.status() != Operation.Status.WAITING_MATERIAL) continue;
        if (!ready(o, op, ops)) continue;
        orders.saveOperation(op.with(Operation.Status.READY));
        if (op.seq() == 1 && o.status() == OrderStatus.MATERIAL_PENDING) {
          o = service.move(o, OrderStatus.RELEASED, "Material für den ersten Arbeitsgang ist da");
        }
      }
    }
    for (MachineRecord machine : machines.machines()) startNext(machine);
  }

  private boolean ready(ProductionOrder o, Operation op, List<Operation> ops) {
    if (op.seq() > 1 && ops.get(op.seq() - 2).status() != Operation.Status.DONE) return false;
    List<OperationMaterial> containers = orders.materials(o.id(), op.seq());
    if (op.seq() > 1 && containers.isEmpty()) return false;
    for (OperationMaterial m : containers) if (!m.delivered()) return false;
    return true;
  }

  private void startNext(MachineRecord machine) {
    if (machine.status() != MachineStatus.IDLE) return;
    Operation next = null;
    for (Operation op : orders.operationsOnMachine(machine.id())) {
      if (op.status() == Operation.Status.RUNNING) return;
      if (op.status() != Operation.Status.READY) continue;
      if (next == null || earlier(op, next)) next = op;
    }
    if (next != null) start(machine, next);
  }

  private static boolean earlier(Operation a, Operation b) {
    if (a.plannedStartMs() == null) return false;
    return b.plannedStartMs() == null || a.plannedStartMs() < b.plannedStartMs();
  }

  private void start(MachineRecord machine, Operation op) {
    ProductionOrder o = service.order(op.orderId());
    var kind = SmartFactoryMasterData.machine(machine.id()).kind();
    int quantity = op.seq() == 1 ? o.quantity() : orders.operations(o.id()).get(op.seq() - 2).good();
    long setup = o.productId().equals(machine.lastProductId()) ? 0 : SmartFactoryMasterData.setupMs(kind);
    long process = (long) quantity * catalog.step(o.productId(), op.seq()).baseTimeS() * 1000L;
    long now = time.nowMs();
    control.get().startJob(new MachineJob(machine.id(), o.id(), op.seq(), o.productId(), quantity, setup, process));
    orders.saveOperation(new Operation(op.id(), op.orderId(), op.seq(), op.process(), machine.id(),
        Operation.Status.RUNNING, now, now + setup + process, now, null, 0, 0));
    service.publish(EventTypes.OPERATION_STARTED, Severity.INFO, o.id(),
        new Payloads.OperationChanged(o.id(), op.seq(), machine.id()));
    machines.save(new MachineRecord(machine.id(), machine.status(), o.productId(), machine.version()));
    if (o.status() == OrderStatus.RELEASED) service.move(o, OrderStatus.IN_PROGRESS, "erster Arbeitsgang gestartet");
  }

  /** The batch of a running operation is done: book it and send the good parts on. */
  public void jobFinished(String machineId, String orderId, int stepSeq, int good, int scrap) {
    Optional<ProductionOrder> found = orderId == null ? Optional.empty() : orders.order(orderId);
    if (found.isEmpty() || found.get().status() == OrderStatus.COMPLETED) {
      warn(orderId, machineId, "Rückmeldung zu unbekanntem oder abgeschlossenem Auftrag " + orderId + " ignoriert");
      return;
    }
    ProductionOrder o = found.get();
    List<Operation> ops = orders.operations(orderId);
    Operation op = stepSeq >= 1 && stepSeq <= ops.size() ? ops.get(stepSeq - 1) : null;
    if (op == null || op.status() != Operation.Status.RUNNING || !op.machineId().equals(machineId)
        || good < 0 || scrap < 0) {
      warn(orderId, machineId, "Rückmeldung passt nicht: Schritt " + stepSeq + " läuft nicht auf " + machineId);
      return;
    }
    long now = time.nowMs();
    orders.saveOperation(new Operation(op.id(), op.orderId(), op.seq(), op.process(), op.machineId(),
        Operation.Status.DONE, op.plannedStartMs(), op.plannedEndMs(), op.startedAtMs(), now, good, scrap));
    service.publish(EventTypes.OPERATION_COMPLETED, scrap > 0 ? Severity.WARNING : Severity.SUCCESS, orderId,
        new Payloads.OperationChanged(orderId, stepSeq, machineId));
    if (o.status() == OrderStatus.CANCELLED) return; // the parts stay where they are
    boolean last = stepSeq == ops.size();
    o = orders.update(new ProductionOrder(o.id(), o.productId(), o.quantity(), o.priority(), o.status(), o.dueAtMs(),
        o.createdAtMs(), o.completedAtMs(), last ? good : o.good(), o.scrapped() + scrap, o.version()));
    if (good == 0) {
      if (!last) abandonRest(o, ops, stepSeq);
      complete(o);
    } else if (last) {
      transports.get().requestTransport(new TransportRequest(orderId + "/P", TransportKind.PUTAWAY, orderId,
          catalog.finishedMaterialId(o.productId()), good, Place.machineOut(machineId), Place.storage(FINISHED_GOODS),
          o.priority(), null));
    } else {
      handOver(o, ops.get(stepSeq), machineId, good);
    }
  }

  private void handOver(ProductionOrder o, Operation next, String fromMachine, int good) {
    String target = next.machineId() != null ? next.machineId()
        : catalog.step(o.productId(), next.seq()).preferredMachineId();
    String requestId = o.id() + "/W" + next.seq();
    String transportId = null;
    if (!SmartFactoryMasterData.machine(fromMachine).outputToConveyor()) {
      transportId = transports.get().requestTransport(new TransportRequest(requestId, TransportKind.WIP, o.id(), null,
          good, Place.machineOut(fromMachine), Place.machineIn(target), o.priority(), null));
    }
    orders.saveMaterial(new OperationMaterial(requestId, o.id(), next.seq(), TransportKind.WIP, null, good,
        transportId, false));
    orders.saveOperation(new Operation(next.id(), next.orderId(), next.seq(), next.process(), target,
        next.status() == Operation.Status.PLANNED ? Operation.Status.WAITING_MATERIAL : next.status(),
        next.plannedStartMs(), next.plannedEndMs(), next.startedAtMs(), next.endedAtMs(), next.good(), next.scrap()));
  }

  /** No good part is left: later steps are dropped with their material. */
  private void abandonRest(ProductionOrder o, List<Operation> ops, int doneSeq) {
    materials.get().release(o.id() + "/RES");
    for (Operation later : ops.subList(doneSeq, ops.size())) {
      for (OperationMaterial m : orders.materials(o.id(), later.seq())) {
        if (m.transportId() == null || m.delivered()) continue;
        try {
          transports.get().cancel(m.transportId());
        } catch (IllegalTransitionException alreadyLoaded) {
          service.decision(o.id(), Severity.WARNING, m.transportId(), "zustellen", List.of(),
              "Transport bereits unterwegs, die Ladung wird noch zugestellt");
        }
      }
      orders.saveOperation(later.with(Operation.Status.CANCELLED));
    }
    service.decision(o.id(), Severity.WARNING, o.id(), "abschliessen", List.of(),
        "Kein Gutteil nach Schritt " + doneSeq + ", restliche Arbeitsgänge entfallen");
  }

  private void complete(ProductionOrder o) {
    ProductionOrder done = new ProductionOrder(o.id(), o.productId(), o.quantity(), o.priority(), o.status(),
        o.dueAtMs(), o.createdAtMs(), time.nowMs(), o.good(), o.scrapped(), o.version());
    service.move(orders.update(done), OrderStatus.COMPLETED, o.good() + " Gutteile, " + o.scrapped() + " Ausschuss");
  }

  /** Parts of an assembly cell arrived on the conveyor at the machine of the next step. */
  public void conveyorDelivered(String machineId, String orderId, int stepSeq, int quantity) {
    Optional<ProductionOrder> found = orderId == null ? Optional.empty() : orders.order(orderId);
    if (found.isEmpty() || found.get().status().terminal()) {
      warn(orderId, machineId, "Bandübergabe zu unbekanntem oder abgeschlossenem Auftrag " + orderId + " ignoriert");
      return;
    }
    List<Operation> ops = orders.operations(orderId);
    boolean fedByConveyor = stepSeq > 1 && stepSeq <= ops.size() && isConveyorLink(ops.get(stepSeq - 2).machineId(), machineId);
    OperationMaterial waiting = null;
    if (fedByConveyor) {
      for (OperationMaterial m : orders.materials(orderId, stepSeq)) {
        if (m.kind() == TransportKind.WIP && m.transportId() == null && !m.delivered()) waiting = m;
      }
    }
    if (waiting == null) {
      warn(orderId, machineId, "Bandübergabe für Schritt " + stepSeq + " an " + machineId + " nicht erwartet");
      return;
    }
    orders.saveMaterial(new OperationMaterial(waiting.id(), waiting.orderId(), waiting.stepSeq(), waiting.kind(),
        waiting.materialId(), waiting.quantity(), null, true));
  }

  private static boolean isConveyorLink(String from, String to) {
    for (var link : SmartFactoryMasterData.conveyorLinks()) {
      if (link.fromMachineId().equals(from) && link.toMachineId().equals(to)) return true;
    }
    return false;
  }

  /** Stores the machine's new state and its interval. */
  public boolean statusChanged(String machineId, MachineStatus from, MachineStatus to) {
    MachineRecord record = null;
    for (MachineRecord r : machines.machines()) if (r.id().equals(machineId)) record = r;
    if (record == null || to == null) {
      warn(null, machineId, "Statusmeldung einer unbekannten Maschine " + machineId + " ignoriert");
      return false;
    }
    machines.changeState(machineId, to, time.nowMs());
    machines.save(new MachineRecord(machineId, to, record.lastProductId(), record.version()));
    service.publish(EventTypes.MACHINE_STATUS, to == MachineStatus.FAULT ? Severity.ERROR : Severity.INFO, null,
        new Payloads.MachineStatusChanged(machineId, from, to));
    return true;
  }

  private void warn(String orderId, String subject, String reason) {
    service.decision(orderId, Severity.WARNING, subject, "ignoriert", List.of(), reason);
  }
}
