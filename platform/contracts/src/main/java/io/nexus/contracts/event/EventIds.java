package io.nexus.contracts.event;

/** Event ids of one simulation run: "<runId>-<counter>". Deterministic within a run, unique across runs. */
public final class EventIds {
  private final long runId;
  private long counter;

  public EventIds(long runId) {
    this.runId = runId;
  }

  public long runId() {
    return runId;
  }

  public synchronized String next() {
    return runId + "-" + counter++;
  }
}
