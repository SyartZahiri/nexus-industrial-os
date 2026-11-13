package io.nexus.smartfactory.store.memory;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OperationMaterial;
import io.nexus.smartfactory.store.OrderStore;
import io.nexus.smartfactory.store.ProductionOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** OrderStore in memory. Every write registers its undo with the unit of work. Keys are sorted strings. */
public final class MemoryOrderStore implements OrderStore {
  private final MemoryUnitOfWork work;
  private final Map<String, ProductionOrder> orders = new TreeMap<>();
  /** Key order id, separator, seq offset to a fixed width, so iteration is by order id, then seq. */
  private final Map<String, Operation> operations = new TreeMap<>();
  private final Map<String, OperationMaterial> materials = new TreeMap<>();

  public MemoryOrderStore(MemoryUnitOfWork work) {
    this.work = work;
  }

  private <V> void put(Map<String, V> map, String key, V value) {
    V before = map.put(key, value);
    work.onRollback(() -> {
      if (before == null) map.remove(key);
      else map.put(key, before);
    });
  }

  private static String opKey(String orderId, int seq) {
    return orderId + "\u0000" + (1000 + seq);
  }

  @Override
  public void insert(ProductionOrder order, List<Operation> ops) {
    if (orders.containsKey(order.id())) throw DomainException.conflict("Auftrag existiert bereits: " + order.id());
    put(orders, order.id(), order);
    for (Operation op : ops) put(operations, opKey(op.orderId(), op.seq()), op);
  }

  @Override
  public ProductionOrder update(ProductionOrder order) {
    ProductionOrder stored = orders.get(order.id());
    if (stored == null) throw DomainException.notFound("Auftrag", order.id());
    if (stored.version() != order.version()) {
      throw DomainException.conflict("Auftrag " + order.id() + " wurde zwischenzeitlich geändert");
    }
    var next = new ProductionOrder(order.id(), order.productId(), order.quantity(), order.priority(), order.status(),
        order.dueAtMs(), order.createdAtMs(), order.completedAtMs(), order.good(), order.scrapped(), order.version() + 1);
    put(orders, order.id(), next);
    return next;
  }

  @Override
  public Optional<ProductionOrder> order(String id) {
    return Optional.ofNullable(orders.get(id));
  }

  @Override
  public List<ProductionOrder> orders() {
    return List.copyOf(orders.values());
  }

  @Override
  public List<ProductionOrder> open() {
    return orders.values().stream().filter(o -> !o.status().terminal()).toList();
  }

  @Override
  public List<Operation> operations(String orderId) {
    return operations.values().stream().filter(o -> o.orderId().equals(orderId)).toList();
  }

  @Override
  public void saveOperation(Operation op) {
    put(operations, opKey(op.orderId(), op.seq()), op);
  }

  @Override
  public List<Operation> operationsOnMachine(String machineId) {
    List<Operation> out = new ArrayList<>();
    for (Operation op : operations.values()) {
      if (machineId.equals(op.machineId()) && !op.finished()) out.add(op);
    }
    return out;
  }

  @Override
  public void saveMaterial(OperationMaterial m) {
    put(materials, m.id(), m);
  }

  @Override
  public List<OperationMaterial> materials(String orderId, int stepSeq) {
    return materials.values().stream().filter(m -> m.orderId().equals(orderId) && m.stepSeq() == stepSeq).toList();
  }

  @Override
  public Optional<OperationMaterial> materialByTransport(String transportId) {
    return materials.values().stream().filter(m -> transportId.equals(m.transportId())).findFirst();
  }
}
