package io.nexus.web;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilotSelfTest;
import io.nexus.smartfactory.SmartFactorySelfTest;
import java.util.ArrayList;
import java.util.List;
import org.teavm.jso.JSExport;

/**
 * Entry points of the browser runtime. TeaVM compiles this class and everything it reaches to JavaScript;
 * methods marked with JSExport become exports of the ES module.
 */
public final class WebRuntime {
  private WebRuntime() {}

  /**
   * Exercises the core end to end without a database: event bus with FIFO delivery, a consumer that publishes,
   * a consumer that fails once and is retried, the seeded random generator and floating point arithmetic.
   * The build runs it on the JVM and in Node and requires identical output.
   */
  @JSExport
  public static String selfTest(int seed) {
    var work = new MemoryUnitOfWork();
    var log = new MemoryEventLog(work);
    var ids = new EventIds(1);
    var bus = new EventBus(log, work, new SimTime(), () -> ids);
    List<String> lines = new ArrayList<>();

    bus.register(new EventConsumer() {
      public String name() { return "a-follow"; }
      public void on(NexusEvent e) {
        if (e.type().equals(EventTypes.ORDER_CREATED)) {
          bus.publish(EventTypes.SCENARIO_FINISHED, "selftest", Severity.INFO, e.correlationId(),
              new Payloads.Note("Folgeereignis"));
        }
        lines.add(e.eventId() + " " + e.type() + " " + name());
      }
    });
    boolean[] failedOnce = {false};
    bus.register(new EventConsumer() {
      public String name() { return "b-flaky"; }
      public void on(NexusEvent e) {
        if (e.type().equals(EventTypes.ORDER_CREATED) && !failedOnce[0]) {
          failedOnce[0] = true;
          throw new IllegalStateException("erster Versuch scheitert");
        }
        lines.add(e.eventId() + " " + e.type() + " " + name());
      }
    });

    bus.publish(EventTypes.ORDER_CREATED, "selftest", Severity.INFO, "PO-1",
        new Payloads.OrderCreated("PO-1", "GH200", 5, Priority.EILIG, 3_600_000));
    bus.retryFailed();

    var random = new SeededRandom(seed);
    lines.add("random=" + Long.toHexString(random.nextLong()) + "," + Long.toHexString(random.nextLong()) + ","
        + Long.toHexString(random.nextLong()));
    var fork = random.fork("flowpilot");
    lines.add("fork=" + Long.toHexString(fork.nextLong()) + " int=" + fork.nextInt(1000));
    // Bit patterns, not printed decimals: the simulation must compute the same doubles on both runtimes.
    double x = random.nextDouble();
    lines.add("arith=" + bits(x) + "," + bits(x * 17.5 + 3.25 / (x + 1)) + "," + bits(Math.sqrt(x)) + ","
        + bits(random.range(0.85, 1.2)));
    // one line per core module; multi-line output is joined so the line count stays stable
    lines.add("flowpilot=" + oneLine(FlowPilotSelfTest.run(seed)));
    lines.add("smartfactory=" + oneLine(SmartFactorySelfTest.run(seed)));
    return String.join("\n", lines);
  }

  private static String oneLine(String text) {
    return String.join(" | ", text.split("\n"));
  }

  private static String bits(double value) {
    return Long.toHexString(Double.doubleToLongBits(value));
  }

  public static void main(String[] args) {}
}
