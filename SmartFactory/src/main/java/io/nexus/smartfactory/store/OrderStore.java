package io.nexus.smartfactory.store;

import java.util.List;
import java.util.Optional;

/** Storage of production orders, operations and their containers. Writes join the current unit of work. */
public interface OrderStore {
  void insert(ProductionOrder order, List<Operation> operations);

  /** Optimistic: DomainException CONFLICT if the stored version differs. Returns the order with the new version. */
  ProductionOrder update(ProductionOrder order);

  Optional<ProductionOrder> order(String id);

  /** Ordered by id. */
  List<ProductionOrder> orders();

  /** Not COMPLETED or CANCELLED, ordered by id. */
  List<ProductionOrder> open();

  /** Ordered by seq. */
  List<Operation> operations(String orderId);

  /** Replaces the operation with the same id. */
  void saveOperation(Operation op);

  /** Not DONE or CANCELLED, ordered by order id, seq. */
  List<Operation> operationsOnMachine(String machineId);

  /** Inserts or replaces by id. */
  void saveMaterial(OperationMaterial m);

  /** Ordered by id. */
  List<OperationMaterial> materials(String orderId, int stepSeq);

  Optional<OperationMaterial> materialByTransport(String transportId);
}
