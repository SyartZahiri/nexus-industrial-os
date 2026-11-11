package io.nexus.contracts.memory;

import io.nexus.contracts.port.IdSource;
import java.util.LinkedHashMap;
import java.util.Map;

/** IdSource in memory; counters take part in the MemoryUnitOfWork like every other in-memory write. */
public final class MemoryIdSource implements IdSource {
  private final MemoryUnitOfWork work;
  private final Map<String, Long> counters = new LinkedHashMap<>();

  public MemoryIdSource(MemoryUnitOfWork work) {
    this.work = work;
  }

  @Override
  public String next(String prefix) {
    Long before = counters.get(prefix);
    long value = before == null ? 1 : before + 1;
    counters.put(prefix, value);
    work.onRollback(() -> {
      if (before == null) counters.remove(prefix);
      else counters.put(prefix, before);
    });
    return IdSource.format(prefix, value);
  }

  @Override
  public void reset() {
    counters.clear();
  }

  /** For tests: the next id of the prefix will be value + 1. */
  public void preset(String prefix, long value) {
    counters.put(prefix, value);
  }
}
