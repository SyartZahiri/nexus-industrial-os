package io.nexus.routemind.store;

import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.routemind.store.memory.MemoryRouteStore;

class MemoryRouteStoreTest extends RouteStoreContract {
  private final MemoryUnitOfWork work = new MemoryUnitOfWork();
  private final MemoryRouteStore store = new MemoryRouteStore(work);

  @Override protected RouteStore store() { return store; }
  @Override protected UnitOfWork work() { return work; }
}
