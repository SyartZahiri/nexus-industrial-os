package io.nexus.contracts.port;

import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;

class MemoryIdSourceTest extends IdSourceContract {
  private final MemoryUnitOfWork work = new MemoryUnitOfWork();
  private final MemoryIdSource ids = new MemoryIdSource(work);

  @Override protected IdSource ids() { return ids; }
  @Override protected UnitOfWork work() { return work; }
  @Override protected void presetCounter(String prefix, long value) { ids.preset(prefix, value); }
}
