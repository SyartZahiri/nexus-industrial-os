package io.nexus.routemind.optimize;

/** Port of the vehicle routing (Spec 15a): heuristic in the core, OR-Tools on the server. */
public interface RouteOptimizer {
  Solution solve(Problem p);
}
