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
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.ProductCatalog;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.sim.SimTime;
import io.nexus.smartfactory.catalog.Catalog;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.orders.OrderChange;
import io.nexus.smartfactory.orders.OrderService;
import io.nexus.smartfactory.planning.DispatchRule;
import io.nexus.smartfactory.planning.Plan;
import io.nexus.smartfactory.store.MachineStore;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OrderStore;
import io.nexus.smartfactory.store.ProductionOrder;
import io.nexus.smartfactory.store.memory.MemoryMachineStore;
import io.nexus.smartfactory.store.memory.MemoryOrderStore;
import java.util.List;
import java.util.function.Supplier;

/**
 * Facade of SmartFactory. Every public method runs in its own unit of work and is synchronized.
 * Ports of other modules are suppliers, resolved on first use, so the module starts without them.
 */
public final class SmartFactory {
  /** Name of the event consumer SmartFactory registers at the bus. */
  public static final String CONSUMER = "smartfactory";

  private final OrderStore orders;
  private final MachineStore machineStore;
  private final UnitOfWork work;
  private final Catalog catalog = new Catalog();
  private final OrderService orderService;

  public SmartFactory(OrderStore orders, MachineStore machineStore, EventBus bus, UnitOfWork work, SimTime time,
                      IdSource ids, Supplier<MaterialService> materials, Supplier<TransportService> transports,
                      Supplier<MachineControl> machines) {
    this.orders = orders;
    this.machineStore = machineStore;
    this.work = work;
    this.orderService = new OrderService(orders, machineStore, bus, time, ids, catalog, materials, transports);
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
    return inUnit(() -> orderService.create(order));
  }

  public void changeOrder(String orderId, OrderChange change) {
    inUnit(() -> { orderService.change(orderId, change); return null; });
  }

  public void cancelOrder(String orderId) {
    inUnit(() -> { orderService.cancel(orderId); return null; });
  }

  public void setDispatchRule(DispatchRule rule) {
    inUnit(() -> { orderService.setRule(rule); return null; });
  }

  public DispatchRule dispatchRule() {
    return orderService.rule();
  }

  /** The last computed plan. */
  public Plan plan() {
    return inUnit(orderService::plan);
  }

  public synchronized List<ProductionOrder> orders() {
    return orders.orders();
  }

  public synchronized List<Operation> operations(String orderId) {
    return orders.operations(orderId);
  }

  /** Runs in the delivering bus's unit of work, so the processed marker and these writes commit together. */
  private synchronized void consume(NexusEvent event) {
    try {
      if (event.type().equals(EventTypes.STOCK_CHANGED)) {
        orderService.stockChanged(((Payloads.StockChanged) event.payload()).materialId());
      }
    } catch (RuntimeException | Error e) {
      orderService.forgetPlan();
      throw e;
    }
  }

  private synchronized <T> T inUnit(Supplier<T> action) {
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
