package io.nexus.routemind.optimize;

import java.util.ArrayList;
import java.util.List;

/**
 * Jobs no plan can serve, whatever the solver: reported with their reason, the rest goes to the solver. A window
 * that cannot be met is soft: the job is still planned and delivered late (Evaluation prices the lateness), and it
 * is reported in `late`; leaving it unplanned would leave the goods waiting for good, since no later wave meets it.
 */
public final class Feasibility {
  public record Split(List<Job> plannable, List<Unassigned> unassigned, List<Unassigned> late) {}

  private Feasibility() {}

  public static Split split(Problem p) {
    List<Job> ok = new ArrayList<>();
    List<Unassigned> no = new ArrayList<>();
    List<Unassigned> late = new ArrayList<>();
    long latestEnd = Long.MIN_VALUE;
    for (Truck t : p.trucks()) latestEnd = Math.max(latestEnd, t.endMs());
    for (Job j : p.jobs()) {
      String reason = null;
      long arrival = p.departMs() + p.matrix().seconds(p.depot(), j.place()) * 1000L;
      if (p.trucks().isEmpty()) {
        reason = Reasons.SHIFT;
      } else if (!fitsSomeTruck(p, j)) {
        reason = Reasons.CAPACITY;
      } else {
        long back = Math.max(arrival, j.earliestMs()) + j.serviceMs()
            + p.matrix().seconds(j.place(), p.depot()) * 1000L;
        if (back > latestEnd) reason = Reasons.SHIFT;
      }
      if (reason != null) {
        no.add(new Unassigned(j.id(), reason));
        continue;
      }
      ok.add(j);
      if (arrival > j.latestMs()) late.add(new Unassigned(j.id(), Reasons.WINDOW));
    }
    return new Split(ok, no, late);
  }

  private static boolean fitsSomeTruck(Problem p, Job j) {
    for (Truck t : p.trucks()) if (j.weightKg() <= t.maxKg() && j.pallets() <= t.maxPallets()) return true;
    return false;
  }
}
