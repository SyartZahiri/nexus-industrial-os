package io.nexus.contracts.event;

import java.util.UUID;

/** Deterministic event ids: same run and seed give the same sequence, different runs never collide. */
public final class EventIds {
  private final long mostSig;
  private long counter;

  public EventIds(long runId, long seed) {
    this.mostSig = (runId << 32) | (seed & 0xFFFFFFFFL);
  }

  public synchronized UUID next() {
    return new UUID(mostSig, counter++);
  }
}
