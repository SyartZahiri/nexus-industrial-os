package io.nexus.smartfactory;

import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.MachineControl;
import io.nexus.contracts.port.MachineFeedback;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.ProductCatalog;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.smartfactory.analytics.Analytics;
import io.nexus.smartfactory.analytics.Kpis;
import io.nexus.smartfactory.catalog.Catalog;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.orders.OrderChange;
import io.nexus.smartfactory.orders.OrderService;
import io.nexus.smartfactory.planning.DispatchRule;
import io.nexus.smartfactory.planning.Plan;
import io.nexus.smartfactory.production.FaultHandling;
import io.nexus.smartfactory.production.ProductionFlow;
import io.nexus.smartfactory.store.MachineRecord;
import io.nexus.smartfactory.store.MachineStore;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OperationMaterial;
import io.nexus.smartfactory.store.OrderStore;
import io.nexus.smartfactory.store.ProductionOrder;
import io.nexus.smartfactory.store.memory.MemoryMachineStore;
import io.nexus.smartfactory.store.memory.MemoryOrderStore;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Facade of SmartFactory. Every public method runs in its own unit of work.
 *
 * <p>Threading: every method holds the event bus's monitor, the same lock the bus holds while it delivers to
 * SmartFactory's consumer. One lock, one order: a thread delivering events and a thread calling the facade can
 * never wait for each other crosswise. Like the bus, MachineFeedback must not be called while the caller holds
 * uncommitted writes. Commands to machines leave only after the unit that decided them has committed.
 *
 * <p>Ports of other modules are suppliers, resolved on first use, so the module starts without them.
 */
public final class SmartFactory implements MachineFeedback {
  /** Name of the event consumer SmartFactory registers at the bus. */
  public static final String CONSUMER = "smartfactory";

  private final Object lock;
  private final OrderStore orders;
  private final MachineStore machineStore;
  private final UnitOfWork work;
  private final Catalog catalog = new Catalog();
  private final OrderService orderService;
  private final ProductionFlow flow;
  private final FaultHandling faults;

  public SmartFactory(OrderStore orders, MachineStore machineStore, EventBus bus, UnitOfWork work, SimTime time,
                      IdSource ids, Supplier<MaterialService> materials, Supplier<TransportService> transports,
                      Supplier<MachineControl> machines) {
    this.lock = bus;
    this.orders = orders;
    this.machineStore = machineStore;
    this.work = work;
    this.orderService = new OrderService(orders, machineStore, bus, time, ids, catalog, materials, transports);
    this.flow = new ProductionFlow(orders, machineStore, orderService, catalog, time, work, materials, transports,
        machines);
    this.faults = new FaultHandling(orders, machineStore, orderService, flow, machines);
    bus.register(new EventConsumer() {
      @Override public String name() { return CONSUMER; }
      @Override public void on(NexusEvent event) { consume(event); }
    });
  }

  public static SmartFactory inMemory(EventBus bus, MemoryUnitOfWork work, SimTime time, IdSource ids,
                                      MaterialService materials, TransportService transports, MachineControl machines) {
    return new SmartFactory(new MemoryOrderStore(work), new MemoryMachineStore(work), bus, work, time, ids,
        () -> materials, () -> transports, () -> machines);
  }

  public ProductCatalog catalog() {
    return catalog;
  }

  public String createOrder(NewOrder order) {
    return inUnit(() -> { String id = orderService.create(order); flow.advance(); return id; });
  }

  public void changeOrder(String orderId, OrderChange change) {
    inUnit(() -> { orderService.change(orderId, change); flow.advance(); return null; });
  }

  public void cancelOrder(String orderId) {
    inUnit(() -> { orderService.cancel(orderId); flow.advance(); return null; });
  }

  public void setDispatchRule(DispatchRule rule) {
    inUnit(() -> { orderService.setRule(rule); flow.advance(); return null; });
  }

  public DispatchRule dispatchRule() {
    return locked(orderService::rule);
  }

  /** The last computed plan; it follows production as jobs finish and containers arrive. */
  public Plan plan() {
    return inUnit(orderService::plan);
  }

  public List<ProductionOrder> orders() {
    return locked(orders::orders);
  }

  public List<Operation> operations(String orderId) {
    return locked(() -> orders.operations(orderId));
  }

  /** @throws io.nexus.contracts.DomainException NOT_FOUND */
  public ProductionOrder order(String orderId) {
    return locked(() -> orderService.order(orderId));
  }

  /** Containers of every step of the order, by step. */
  public List<OperationMaterial> materials(String orderId) {
    return locked(() -> {
      List<OperationMaterial> out = new ArrayList<>();
      for (Operation op : orders.operations(orderId)) out.addAll(orders.materials(orderId, op.seq()));
      return out;
    });
  }

  /** Ordered by id. */
  public List<MachineRecord> machines() {
    return locked(machineStore::machines);
  }

  /** The operation running on the machine, or null. */
  public Operation runningOn(String machineId) {
    return locked(() -> {
      for (Operation op : orders.operationsOnMachine(machineId)) if (op.status() == Operation.Status.RUNNING) return op;
      return null;
    });
  }

  public Kpis kpis(long fromMs, long toMs) {
    return locked(() -> new Analytics(orders, machineStore, catalog).compute(fromMs, toMs));
  }

  // ---- machine commands: checked against the machine's state, carried out by the simulation -------------

  public void startMachine(String machineId) {
    locked(() -> { faults.start(machineId); return null; });
  }

  public void stopMachine(String machineId) {
    locked(() -> { faults.stop(machineId); return null; });
  }

  /** @param faultTypeId null lets the simulation pick a fault valid for the machine kind */
  public void injectFault(String machineId, String faultTypeId) {
    locked(() -> { faults.injectFault(machineId, faultTypeId); return null; });
  }

  public void repairMachine(String machineId) {
    locked(() -> { faults.repair(machineId); return null; });
  }

  public void maintainMachine(String machineId) {
    locked(() -> { faults.maintain(machineId); return null; });
  }

  private MachineStatus knownStatus(String machineId) {
    for (var r : machineStore.machines()) if (r.id().equals(machineId)) return r.status();
    return null;
  }

  // ---- MachineFeedback: called by the simulation --------------------------------------------------------

  @Override
  public void statusChanged(String machineId, MachineStatus from, MachineStatus to) {
    inUnit(() -> {
      MachineStatus before = knownStatus(machineId);
      if (flow.statusChanged(machineId, from, to)) {
        faults.afterStatusChange(machineId, before, to);
        flow.advance();
      }
      return null;
    });
  }

  @Override
  public void faultRaised(String machineId, String faultTypeId, String label, boolean scrappedWorkpiece) {
    inUnit(() -> { faults.faultRaised(machineId, faultTypeId, label, scrappedWorkpiece); moveOn(); return null; });
  }

  @Override
  public void jobFinished(String machineId, String orderId, int stepSeq, int good, int scrap) {
    inUnit(() -> { flow.jobFinished(machineId, orderId, stepSeq, good, scrap); moveOn(); return null; });
  }

  @Override
  public void conveyorDelivered(String machineId, String orderId, int stepSeq, int quantity) {
    inUnit(() -> { flow.conveyorDelivered(machineId, orderId, stepSeq, quantity); moveOn(); return null; });
  }

  /** Production moved on: the plan follows, then ready operations start. */
  private void moveOn() {
    orderService.replan();
    flow.advance();
  }

  /** Runs in the delivering bus's unit of work, so the processed marker and these writes commit together. */
  private void consume(NexusEvent event) {
    synchronized (lock) {
      try {
        if (event.type().equals(EventTypes.STOCK_CHANGED)) {
          orderService.stockChanged(((Payloads.StockChanged) event.payload()).materialId());
        } else if (event.type().equals(EventTypes.TRANSPORT_DELIVERED)) {
          flow.delivered((Payloads.TransportDelivered) event.payload());
        } else {
          return;
        }
        moveOn();
      } catch (RuntimeException | Error e) {
        orderService.forgetPlan();
        throw e;
      }
    }
  }

  private <T> T locked(Supplier<T> action) {
    synchronized (lock) {
      return action.get();
    }
  }

  private <T> T inUnit(Supplier<T> action) {
    synchronized (lock) {
      Object[] result = new Object[1];
      try {
        work.inNewTransaction(() -> result[0] = action.get());
      } catch (RuntimeException | Error e) {
        orderService.forgetPlan();
        throw e;
      }
      @SuppressWarnings("unchecked") T t = (T) result[0];
      return t;
    }
  }
}
