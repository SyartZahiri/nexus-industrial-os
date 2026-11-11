package io.nexus.contracts.memory;

import io.nexus.contracts.event.EventLog;
import io.nexus.contracts.event.NexusEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** EventLog held in memory. Behaves like the PostgreSQL one; EventLogContract checks both. */
public class MemoryEventLog implements EventLog {
  private record Stored(long runId, NexusEvent event) {}

  private final MemoryUnitOfWork work;
  private final List<Stored> events = new ArrayList<>();
  private final Map<String, Stored> byId = new LinkedHashMap<>();
  private final Set<String> processed = new HashSet<>();
  private final Map<String, Integer> failures = new LinkedHashMap<>();

  public MemoryEventLog(MemoryUnitOfWork work) {
    this.work = work;
  }

  private static String key(String consumer, String eventId) {
    return consumer + "\u0000" + eventId;
  }

  @Override
  public void append(long runId, NexusEvent event) {
    var stored = new Stored(runId, event);
    if (byId.putIfAbsent(event.eventId(), stored) != null) {
      throw new IllegalStateException("Ereignis-ID doppelt: " + event.eventId());
    }
    events.add(stored);
    work.onRollback(() -> {
      events.remove(stored);
      byId.remove(event.eventId());
    });
  }

  @Override
  public boolean markProcessed(String consumer, String eventId) {
    String key = key(consumer, eventId);
    if (!processed.add(key)) return false;
    work.onRollback(() -> processed.remove(key));
    return true;
  }

  @Override
  public int recordFailure(String consumer, String eventId, String error) {
    String key = key(consumer, eventId);
    Integer before = failures.get(key);
    int attempts = before == null ? 1 : before + 1;
    failures.put(key, attempts);
    work.onRollback(() -> {
      if (before == null) failures.remove(key);
      else failures.put(key, before);
    });
    return attempts;
  }

  @Override
  public void clearFailure(String consumer, String eventId) {
    String key = key(consumer, eventId);
    Integer before = failures.remove(key);
    if (before != null) work.onRollback(() -> failures.put(key, before));
  }

  @Override
  public void park(String consumer, String eventId) {
    String key = key(consumer, eventId);
    if (failures.containsKey(key)) return;
    failures.put(key, 0);
    work.onRollback(() -> failures.remove(key));
  }

  @Override
  public boolean hasOpenFailure(String consumer, long runId, int maxAttempts) {
    String prefix = key(consumer, "");
    for (Map.Entry<String, Integer> f : failures.entrySet()) {
      if (!f.getKey().startsWith(prefix) || f.getValue() >= maxAttempts) continue;
      Stored stored = byId.get(f.getKey().substring(prefix.length()));
      if (stored != null && stored.runId() == runId) return true;
    }
    return false;
  }

  @Override
  public List<Failure> openFailures(long runId, int maxAttempts) {
    List<Failure> open = new ArrayList<>();
    for (Stored stored : events) { // publish order
      if (stored.runId() != runId) continue;
      for (Map.Entry<String, Integer> f : failures.entrySet()) {
        int split = f.getKey().indexOf('\u0000');
        if (f.getKey().substring(split + 1).equals(stored.event().eventId()) && f.getValue() < maxAttempts) {
          open.add(new Failure(f.getKey().substring(0, split), stored.event(), f.getValue()));
        }
      }
    }
    return open;
  }

  @Override
  public Optional<NexusEvent> find(String eventId) {
    Stored stored = byId.get(eventId);
    return stored == null ? Optional.empty() : Optional.of(stored.event());
  }

  /** Every stored event in publish order. */
  public List<NexusEvent> all() {
    return events.stream().map(Stored::event).toList();
  }
}
