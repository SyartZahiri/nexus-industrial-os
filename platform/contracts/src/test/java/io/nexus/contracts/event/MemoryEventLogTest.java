package io.nexus.contracts.event;

import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryUnitOfWork;

class MemoryEventLogTest extends EventLogContract {
  private final MemoryUnitOfWork work = new MemoryUnitOfWork();
  private final MemoryEventLog log = new MemoryEventLog(work);

  @Override protected EventLog log() { return log; }
  @Override protected UnitOfWork work() { return work; }
  @Override protected long runId() { return 1; }
  @Override protected long otherRunId() { return 2; }
}
