package io.nexus.routemind.optimize;

import java.util.List;

/** Result of a solver: routes (at most one per truck) and the jobs it could not plan, each with its reason. */
public record Solution(String solver, List<Route> routes, List<Unassigned> unassigned) {
  public Solution {
    routes = List.copyOf(routes);
    unassigned = List.copyOf(unassigned);
  }
}
