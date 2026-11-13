package io.nexus.smartfactory.store;

import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.smartfactory.store.memory.MemoryMachineStore;
import io.nexus.smartfactory.store.memory.MemoryOrderStore;

class MemoryStoreTest extends StoreContract {
  private final MemoryUnitOfWork work = new MemoryUnitOfWork();
  private final MemoryOrderStore orders = new MemoryOrderStore(work);
  private final MemoryMachineStore machines = new MemoryMachineStore(work);

  @Override protected OrderStore orders() { return orders; }
  @Override protected MachineStore machines() { return machines; }
  @Override protected UnitOfWork work() { return work; }
}
