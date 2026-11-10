package io.nexus.contracts.sim;

/** Simulation clock in milliseconds since run start. Advanced only by the simulation loop. */
public final class SimTime {
  private volatile long nowMs;

  public long nowMs() {
    return nowMs;
  }

  public synchronized void advance(long ms) {
    if (ms < 0) throw new IllegalArgumentException("Simulationszeit läuft nicht rückwärts: " + ms);
    nowMs += ms;
  }

  public synchronized void reset() {
    nowMs = 0;
  }
}
