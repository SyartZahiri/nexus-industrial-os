package io.nexus.app.plant;

import io.nexus.contracts.DomainException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Drives the plant on the server: one thread, every 50 ms of real time, advances the simulation by real time ×
 * speed in whole steps. The plant takes its lock per step, so commands from REST land between steps.
 *
 * <p>Starts paused. After a stall (garbage collection, a slow query) at most {@link #MAX_STEPS_PER_TICK} steps run
 * and the rest of the owed time is dropped: the plant stays responsive instead of racing to catch up. A step that
 * throws pauses the loop and is reported; the loop can be started again.
 */
public class SimulationLoop {
  public static final int MAX_STEPS_PER_TICK = 50;
  static final long TICK_MS = 50;
  private static final long STEP_MS = 100;

  private final LongConsumer advance;
  private final Consumer<Throwable> onFailure;
  private volatile boolean running;
  private volatile double speed = 1;
  private double owedMs;
  private ScheduledExecutorService executor;

  public SimulationLoop(LongConsumer advance, Consumer<Throwable> onFailure) {
    this.advance = advance;
    this.onFailure = onFailure;
  }

  /** Starts the thread that calls tick; without it (in tests) tick is called by hand. */
  public SimulationLoop start() {
    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "nexus-sim");
      t.setDaemon(true);
      return t;
    });
    long[] last = {System.nanoTime()};
    executor.scheduleAtFixedRate(() -> {
      long now = System.nanoTime();
      long elapsed = (now - last[0]) / 1_000_000;
      last[0] = now;
      tick(elapsed);
    }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    return this;
  }

  public void stop() {
    if (executor != null) executor.shutdownNow();
  }

  /** One tick of the clock: realMs of real time passed. */
  synchronized void tick(long realMs) {
    if (!running) {
      owedMs = 0;
      return;
    }
    owedMs += realMs * speed;
    long steps = (long) (owedMs / STEP_MS);
    if (steps > MAX_STEPS_PER_TICK) {
      steps = MAX_STEPS_PER_TICK;
      owedMs = 0;
    } else {
      owedMs -= steps * STEP_MS;
    }
    if (steps == 0) return;
    try {
      advance.accept(steps * STEP_MS);
    } catch (Throwable t) {           // the loop thread must survive anything; the plant pauses instead
      running = false;
      owedMs = 0;
      onFailure.accept(t);
    }
  }

  public void play() {
    running = true;
  }

  public void pause() {
    running = false;
  }

  public boolean running() {
    return running;
  }

  public double speed() {
    return speed;
  }

  /** 0.25 to 25 times real time. */
  public void setSpeed(double newSpeed) {
    if (Double.isNaN(newSpeed) || newSpeed < 0.25 || newSpeed > 25) {
      throw DomainException.invalid("Tempo muss zwischen 0,25 und 25 liegen");
    }
    speed = newSpeed;
  }
}
