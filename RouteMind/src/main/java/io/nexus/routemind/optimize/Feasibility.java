package io.nexus.routemind.optimize;

import java.util.ArrayList;
import java.util.List;

/** Jobs no plan can serve, whatever the solver: reported with their reason, the rest goes to the solver. */
public final class Feasibility {
  public record Split(List<Job> plannable, List<Unassigned> unassigned) {}

  private Feasibility() {}

  public static Split split(Problem p) {
    List<Job> ok = new ArrayList<>();
    List<Unassigned> no = new ArrayList<>();
    long latestEnd = Long.MIN_VALUE;
    for (Truck t : p.trucks()) latestEnd = Math.max(latestEnd, t.endMs());
    for (Job j : p.jobs()) {
      String reason = null;
      long arrival = p.departMs() + p.matrix().seconds(p.depot(), j.place()) * 1000L;
      if (p.trucks().isEmpty()) {
        reason = Reasons.SHIFT;
      } else if (!fitsSomeTruck(p, j)) {
        reason = Reasons.CAPACITY;
      } else if (arrival > j.latestMs()) {
        reason = Reasons.WINDOW;
      } else {
        long back = Math.max(arrival, j.earliestMs()) + j.serviceMs()
            + p.matrix().seconds(j.place(), p.depot()) * 1000L;
        if (back > latestEnd) reason = Reasons.SHIFT;
      }
      if (reason == null) ok.add(j);
      else no.add(new Unassigned(j.id(), reason));
    }
    return new Split(ok, no);
  }

  private static boolean fitsSomeTruck(Problem p, Job j) {
    for (Truck t : p.trucks()) if (j.weightKg() <= t.maxKg() && j.pallets() <= t.maxPallets()) return true;
    return false;
  }
}
