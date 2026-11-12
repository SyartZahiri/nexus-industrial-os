package io.nexus.smartfactory.orders;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payload;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.port.MaterialService.ReservationRequest;
import io.nexus.contracts.port.MaterialService.ReservationResult;
import io.nexus.contracts.port.MaterialService.Reserved;
import io.nexus.contracts.port.ProductCatalog.MaterialDemand;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.port.TransportService.TransportRequest;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.smartfactory.catalog.Catalog;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.master.SmartFactoryMasterData.RoutingStep;
import io.nexus.smartfactory.planning.DispatchRule;
import io.nexus.smartfactory.planning.Plan;
import io.nexus.smartfactory.planning.PlanInput;
import io.nexus.smartfactory.planning.Scheduler;
import io.nexus.smartfactory.store.MachineRecord;
import io.nexus.smartfactory.store.MachineStore;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OperationMaterial;
import io.nexus.smartfactory.store.OrderStore;
import io.nexus.smartfactory.store.ProductionOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Orders, their material request and the plan. Runs inside the caller's unit of work; the facade opens it.
 * After every replan the stored operations carry the planned machine and times.
 */
public final class OrderService {
  public static final String SOURCE = "smartfactory";
  public static final int MAX_QUANTITY = 100;

  private final OrderStore orders;
  private final MachineStore machines;
  private final EventPublisher events;
  private final SimTime time;
  private final IdSource ids;
  private final Catalog catalog;
  private final Supplier<MaterialService> materials;
  private final Supplier<TransportService> transports;
  private DispatchRule rule = DispatchRule.PRIORITY;
  private Plan plan;

  public OrderService(OrderStore orders, MachineStore machines, EventPublisher events, SimTime time, IdSource ids,
                      Catalog catalog, Supplier<MaterialService> materials, Supplier<TransportService> transports) {
    this.orders = orders;
    this.machines = machines;
    this.events = events;
    this.time = time;
    this.ids = ids;
    this.catalog = catalog;
    this.materials = materials;
    this.transports = transports;
  }

  // ---- commands -----------------------------------------------------------------------------------------

  public String create(NewOrder n) {
    if (n.priority() == null) throw DomainException.invalid("Priorität fehlt");
    catalog.product(n.productId());
    checkQuantity(n.quantity());
    checkDue(n.dueInMs());
    long now = time.nowMs();
    String id = ids.next("PO");
    var order = new ProductionOrder(id, n.productId(), n.quantity(), n.priority(), OrderStatus.CREATED,
        now + n.dueInMs(), now, null, 0, 0, 0);
    List<Operation> ops = new ArrayList<>();
    for (RoutingStep s : catalog.routing(n.productId())) {
      ops.add(new Operation(id + "/" + s.seq(), id, s.seq(), s.process(), null, Operation.Status.PLANNED,
          null, null, null, null, 0, 0));
    }
    orders.insert(order, ops);
    publish(EventTypes.ORDER_CREATED, Severity.INFO, id,
        new Payloads.OrderCreated(id, n.productId(), n.quantity(), n.priority(), order.dueAtMs()));
    replan();
    requestMaterial(order(id));
    return id;
  }

  public void change(String orderId, OrderChange c) {
    ProductionOrder o = order(orderId);
    if (o.status().terminal()) throw DomainException.conflict("Auftrag " + orderId + " ist abgeschlossen");
    boolean amounts = c.quantity() != null || c.dueInMs() != null;
    if (amounts && o.status() != OrderStatus.SHORTAGE) {
      throw DomainException.conflict("Menge und Termin lassen sich nur im Materialengpass ändern");
    }
    if (c.quantity() != null) checkQuantity(c.quantity());
    if (c.dueInMs() != null) checkDue(c.dueInMs());
    List<String> what = new ArrayList<>();
    if (c.priority() != null) what.add("Priorität " + c.priority());
    if (c.quantity() != null) what.add("Menge " + c.quantity());
    if (c.dueInMs() != null) what.add("Termin in " + c.dueInMs() / 1000 + " s");
    ProductionOrder updated = orders.update(new ProductionOrder(o.id(), o.productId(),
        c.quantity() != null ? c.quantity() : o.quantity(), c.priority() != null ? c.priority() : o.priority(),
        o.status(), c.dueInMs() != null ? time.nowMs() + c.dueInMs() : o.dueAtMs(), o.createdAtMs(),
        o.completedAtMs(), o.good(), o.scrapped(), o.version()));
    decision(orderId, Severity.INFO, orderId, String.join(", ", what), List.of(), "Auftrag geändert, neu geplant");
    replan();
    if (c.quantity() != null) requestMaterial(updated); // a smaller quantity may fit the stock now
  }

  /**
   * Releases the reservation, cancels every transport not picked up yet and every waiting operation. A running
   * operation stays on its machine until it reports back; it then creates nothing further.
   */
  public void cancel(String orderId) {
    ProductionOrder o = order(orderId);
    if (o.status() == OrderStatus.CANCELLED) return;
    OrderStatus.RULES.move(orderId, o.status(), OrderStatus.CANCELLED);
    materials.get().release(orderId + "/RES");
    for (Operation op : orders.operations(orderId)) {
      for (OperationMaterial m : orders.materials(orderId, op.seq())) {
        if (m.transportId() == null || m.delivered()) continue;
        try {
          transports.get().cancel(m.transportId());
        } catch (IllegalTransitionException alreadyLoaded) {
          decision(orderId, Severity.WARNING, m.transportId(), "zustellen", List.of(),
              "Transport bereits unterwegs, die Ladung wird noch zugestellt");
        }
      }
      if (!op.finished() && op.status() != Operation.Status.RUNNING) orders.saveOperation(op.with(Operation.Status.CANCELLED));
    }
    move(o, OrderStatus.CANCELLED, "storniert");
    replan();
  }

  /** Orders waiting for this material try their reservation again, in order of their id. */
  public void stockChanged(String materialId) {
    for (ProductionOrder o : orders.open()) {
      if (o.status() != OrderStatus.SHORTAGE) continue;
      boolean needs = false;
      for (MaterialDemand d : catalog.demandFor(o.productId(), o.quantity())) needs |= d.materialId().equals(materialId);
      if (needs) requestMaterial(o);
    }
  }

  public void setRule(DispatchRule rule) {
    if (rule == null) throw DomainException.invalid("Regel fehlt");
    this.rule = rule;
    replan();
  }

  public DispatchRule rule() {
    return rule;
  }

  /** The last plan; computed now if there is none yet or it was dropped after a failed unit. */
  public Plan plan() {
    return plan != null ? plan : replan();
  }

  /** Called when a unit of work failed: the plan may describe writes that were undone. */
  public void forgetPlan() {
    plan = null;
  }

  // ---- material ---------------------------------------------------------------------------------------

  private void requestMaterial(ProductionOrder order) {
    ProductionOrder o = move(order, OrderStatus.MATERIAL_PENDING, "Material wird reserviert");
    List<MaterialDemand> demand = catalog.demandFor(o.productId(), o.quantity());
    List<Line> lines = new ArrayList<>();
    for (MaterialDemand d : demand) lines.add(new Line(d.materialId(), d.quantity()));
    ReservationResult result = materials.get().reserve(new ReservationRequest(o.id() + "/RES", o.id(), lines));
    if (result instanceof ReservationResult.Shortage s) {
      move(o, OrderStatus.SHORTAGE, "Material " + s.materialId() + " fehlt: benötigt " + s.requested()
          + ", verfügbar " + s.available());
      return;
    }
    var success = (ReservationResult.Success) result;
    List<Operation> ops = orders.operations(o.id());
    for (MaterialDemand d : demand) {
      Reserved r = null;
      for (Reserved candidate : success.reservations()) if (candidate.materialId().equals(d.materialId())) r = candidate;
      Operation op = ops.get(d.stepSeq() - 1);
      String target = op.machineId() != null ? op.machineId() : catalog.step(o.productId(), op.seq()).preferredMachineId();
      String requestId = o.id() + "/S" + d.stepSeq() + "/" + d.materialId();
      String transportId = transports.get().requestTransport(new TransportRequest(requestId, TransportKind.SUPPLY,
          o.id(), d.materialId(), d.quantity(), Place.storage(r.locationId()), Place.machineIn(target), o.priority(),
          r.reservationId()));
      orders.saveMaterial(new OperationMaterial(requestId, o.id(), d.stepSeq(), TransportKind.SUPPLY, d.materialId(),
          d.quantity(), transportId, false));
      orders.saveOperation(new Operation(op.id(), op.orderId(), op.seq(), op.process(), target,
          Operation.Status.WAITING_MATERIAL, op.plannedStartMs(), op.plannedEndMs(), op.startedAtMs(), op.endedAtMs(),
          op.good(), op.scrap()));
    }
  }

  // ---- planning -----------------------------------------------------------------------------------------

  /**
   * Plans every open order and writes machine and times into the operations that have not started.
   * An operation whose machine changes takes its containers along (redirect, or a move from the old machine).
   */
  public Plan replan() {
    long now = time.nowMs();
    List<MachineRecord> records = machines.machines();
    List<PlanInput.MachineState> states = new ArrayList<>();
    for (MachineRecord r : records) {
      var m = SmartFactoryMasterData.machine(r.id());
      long busyUntil = 0;
      for (Operation op : orders.operationsOnMachine(r.id())) {
        if (op.status() == Operation.Status.RUNNING && op.plannedEndMs() != null) busyUntil = Math.max(busyUntil, op.plannedEndMs());
      }
      List<long[]> maintenance = new ArrayList<>();
      for (var w : machines.maintenanceWindows(r.id())) if (w.endMs() > now) maintenance.add(new long[] {w.startMs(), w.endMs()});
      states.add(new PlanInput.MachineState(r.id(), m.kind(), m.capabilities(), available(r.status()), busyUntil,
          r.lastProductId(), maintenance));
    }
    List<ProductionOrder> open = orders.open();
    List<PlanInput.OrderToPlan> toPlan = new ArrayList<>();
    for (ProductionOrder o : open) {
      List<PlanInput.OpToPlan> ops = new ArrayList<>();
      for (Operation op : orders.operations(o.id())) {
        RoutingStep step = catalog.step(o.productId(), op.seq());
        Long done = op.finished() ? (op.endedAtMs() != null ? op.endedAtMs() : now) : null;
        Long running = op.status() == Operation.Status.RUNNING ? (op.plannedEndMs() != null ? op.plannedEndMs() : now) : null;
        String fixed = null;
        if (op.status() == Operation.Status.RUNNING) fixed = op.machineId();
        else if (op.machineId() != null && available(status(records, op.machineId()))
            && !orders.materials(o.id(), op.seq()).isEmpty()) fixed = op.machineId();
        ops.add(new PlanInput.OpToPlan(op.seq(), op.process(), step.baseTimeS(), step.preferredMachineId(), fixed,
            running, done));
      }
      toPlan.add(new PlanInput.OrderToPlan(o.id(), o.productId(), o.quantity(), o.priority(), o.dueAtMs(),
          o.createdAtMs(), ops));
    }
    plan = Scheduler.plan(new PlanInput(now, rule, toPlan, states));
    for (ProductionOrder o : open) {
      for (Operation op : orders.operations(o.id())) {
        if (op.finished() || op.status() == Operation.Status.RUNNING) continue;
        Plan.PlannedOp p = plan.op(o.id(), op.seq());
        String machine = p == null ? op.machineId() : p.machineId();
        if (op.machineId() != null && !op.machineId().equals(machine)) relocate(o, op, machine, records);
        var updated = new Operation(op.id(), op.orderId(), op.seq(), op.process(), machine, op.status(),
            p == null ? null : p.startMs(), p == null ? null : p.endMs(), op.startedAtMs(), op.endedAtMs(),
            op.good(), op.scrap());
        if (!updated.equals(op)) orders.saveOperation(updated);
      }
    }
    return plan;
  }

  private void relocate(ProductionOrder o, Operation op, String to, List<MachineRecord> records) {
    String from = op.machineId();
    MachineStatus fromStatus = status(records, from);
    if (!available(fromStatus)) {
      List<String> candidates = new ArrayList<>();
      for (MachineRecord r : records) {
        if (available(r.status()) && SmartFactoryMasterData.machine(r.id()).capabilities().contains(op.process())) {
          candidates.add(r.id());
        }
      }
      String reason = "Maschine " + from + " nicht verfügbar (" + fromStatus + "), " + op.process() + " auf " + to;
      decision(o.id(), Severity.INFO, o.id() + "/" + op.seq(), to, candidates, reason);
      publish(EventTypes.ORDER_RESCHEDULED, Severity.WARNING, o.id(),
          new Payloads.OrderRescheduled(o.id(), op.seq(), from, to, reason));
    }
    for (OperationMaterial m : orders.materials(o.id(), op.seq())) {
      if (m.transportId() == null) continue; // waits for a conveyor hand-over, nothing to move
      if (!m.delivered()) {
        transports.get().redirect(m.transportId(), Place.machineIn(to));
        continue;
      }
      String transportId = transports.get().requestTransport(new TransportRequest(ids.next(o.id() + "/M" + op.seq()),
          TransportKind.WIP, o.id(), m.materialId(), m.quantity(), Place.machineIn(from), Place.machineIn(to),
          o.priority(), null));
      orders.saveMaterial(new OperationMaterial(m.id(), m.orderId(), m.stepSeq(), m.kind(), m.materialId(),
          m.quantity(), transportId, false));
    }
  }

  private static MachineStatus status(List<MachineRecord> records, String machineId) {
    for (MachineRecord r : records) if (r.id().equals(machineId)) return r.status();
    throw DomainException.notFound("Maschine", machineId);
  }

  /** FAULT, REPAIR, MAINTENANCE and STOPPED take a machine out of the plan. */
  public static boolean available(MachineStatus status) {
    return switch (status) {
      case IDLE, SETUP, RUNNING, BLOCKED -> true;
      case STOPPED, FAULT, REPAIR, MAINTENANCE -> false;
    };
  }

  // ---- shared helpers ---------------------------------------------------------------------------------

  public ProductionOrder order(String orderId) {
    return orders.order(orderId).orElseThrow(() -> DomainException.notFound("Auftrag", orderId));
  }

  /** Checks the transition, stores the order with the new status and publishes the matching order event. */
  public ProductionOrder move(ProductionOrder o, OrderStatus to, String reason) {
    OrderStatus.RULES.move(o.id(), o.status(), to);
    ProductionOrder next = orders.update(o.withStatus(to));
    String type = switch (to) {
      case MATERIAL_PENDING -> EventTypes.ORDER_MATERIAL_PENDING;
      case SHORTAGE -> EventTypes.ORDER_SHORTAGE;
      case RELEASED -> EventTypes.ORDER_RELEASED;
      case IN_PROGRESS -> EventTypes.ORDER_STARTED;
      case COMPLETED -> EventTypes.ORDER_COMPLETED;
      case CANCELLED -> EventTypes.ORDER_CANCELLED;
      case CREATED -> throw new IllegalArgumentException("CREATED ist kein Zielzustand");
    };
    Severity severity = to == OrderStatus.SHORTAGE ? Severity.WARNING
        : to == OrderStatus.COMPLETED ? Severity.SUCCESS : Severity.INFO;
    publish(type, severity, o.id(), new Payloads.OrderStatusChanged(o.id(), o.status(), to, reason));
    return next;
  }

  public void decision(String orderId, Severity severity, String subject, String chosen, List<String> candidates,
                       String reason) {
    publish(EventTypes.DECISION, severity, orderId, new Payloads.Decision(subject, chosen, candidates, reason));
  }

  public void publish(String type, Severity severity, String orderId, Payload payload) {
    events.publish(type, SOURCE, severity, orderId, payload);
  }

  private static void checkQuantity(int quantity) {
    if (quantity < 1 || quantity > MAX_QUANTITY) {
      throw DomainException.invalid("Menge muss zwischen 1 und " + MAX_QUANTITY + " liegen: " + quantity);
    }
  }

  private static void checkDue(long dueInMs) {
    if (dueInMs <= 0) throw DomainException.invalid("Termin muss in der Zukunft liegen");
  }
}
