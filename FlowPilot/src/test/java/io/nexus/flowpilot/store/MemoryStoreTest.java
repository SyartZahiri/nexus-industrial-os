package io.nexus.flowpilot.store;

import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.flowpilot.store.memory.MemoryFleetStore;
import io.nexus.flowpilot.store.memory.MemoryStockStore;
import io.nexus.flowpilot.store.memory.MemoryTransportStore;

class MemoryStoreTest extends StoreContract {
  private final MemoryUnitOfWork work = new MemoryUnitOfWork();
  private final MemoryStockStore stock = new MemoryStockStore(work);
  private final MemoryTransportStore transports = new MemoryTransportStore(work);
  private final MemoryFleetStore fleet = new MemoryFleetStore(work);

  @Override protected StockStore stock() { return stock; }
  @Override protected TransportStore transports() { return transports; }
  @Override protected FleetStore fleet() { return fleet; }
  @Override protected UnitOfWork work() { return work; }
}
