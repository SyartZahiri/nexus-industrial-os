package io.nexus.routemind.optimize;

import java.util.ArrayList;
import java.util.List;

/** The simple start plan (Spec 15a): each truck in turn drives to the nearest job that still fits. */
public final class NearestNeighbour implements RouteOptimizer {
  public static final String NAME = "Nächster Nachbar";

  @Override
  public Solution solve(Problem p) {
    var split = Feasibility.split(p);
    List<Job> open = new ArrayList<>(split.plannable());
    List<Route> routes = new ArrayList<>();
    for (Truck t : p.trucks()) {
      List<Job> route = new ArrayList<>();
      int at = p.depot();
      while (true) {
        Job best = null;
        int bestSeconds = Integer.MAX_VALUE;
        for (Job j : open) {
          int s = p.matrix().seconds(at, j.place());
          if (s >= bestSeconds) continue; // ties keep the job earlier in id order
          List<Job> tried = new ArrayList<>(route);
          tried.add(j);
          if (!Evaluation.route(p, t, tried).fits(t)) continue;
          best = j;
          bestSeconds = s;
        }
        if (best == null) break;
        route.add(best);
        open.remove(best);
        at = best.place();
      }
      if (!route.isEmpty()) routes.add(new Route(t.id(), Evaluation.ids(route)));
    }
    List<Unassigned> unassigned = new ArrayList<>(split.unassigned());
    for (Job j : open) unassigned.add(new Unassigned(j.id(), Reasons.CAPACITY));
    return new Solution(NAME, routes, unassigned);
  }
}
