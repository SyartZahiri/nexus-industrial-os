package io.nexus.plant;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.smartfactory.orders.NewOrder;
import java.util.List;

/**
 * A fixed hour of plant life, used to prove that every runtime does the same thing: the JVM and the browser build
 * (M1-D1), and the server with PostgreSQL (M1-D2) must produce exactly this text for the same seed.
 */
public final class Scenario1h {
  private Scenario1h() {}

  /** Rules of the scenario: everything as in production, except that only the script places orders. */
  public static PlantRules rules() {
    PlantRules d = PlantRules.DEFAULT;
    return new PlantRules(d.machines(), false, d.autoOrderIntervalMs(), d.maxOpenOrders(), d.shipping(), d.dispatchRule());
  }

  /** Runs the script on a freshly reset plant; returns one line per event, then the frame. */
  public static String run(Plant plant) {
    var sf = plant.smartFactory();
    for (String product : List.of("GH200", "AW50", "FP12", "MT7")) {
      sf.createOrder(new NewOrder(product, 4, Priority.NORMAL, 3 * 3_600_000L));
    }
    plant.advance(20 * 60_000);
    attempt(() -> sf.injectFault("CNC-01", "SERVO"));
    plant.advance(10 * 60_000);
    attempt(() -> plant.flowPilot().injectFault("AGV-02", "NAV"));
    plant.advance(10 * 60_000);
    sf.createOrder(new NewOrder("MT7", 2, Priority.EILIG, 3_600_000));
    plant.advance(20 * 60_000);
    return render(plant.events(), plant.frame());
  }

  /** The comparable text: events without their run-specific ids, then the frame. */
  public static String render(List<NexusEvent> events, PlantFrame frame) {
    StringBuilder out = new StringBuilder();
    for (NexusEvent e : events) out.append(PlantJson.event(e, false)).append('\n');
    return out.append(PlantJson.frame(frame)).append('\n').toString();
  }

  /** A command the script gives may be refused (machine already in a fault); that refusal is part of the run. */
  private static void attempt(Runnable command) {
    try {
      command.run();
    } catch (RuntimeException refused) {
      // deterministic: the same state refuses the same command on every runtime
    }
  }
}
