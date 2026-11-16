package io.nexus.app.routemind;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import com.google.protobuf.Duration;
import io.nexus.routemind.optimize.Evaluation;
import io.nexus.routemind.optimize.Feasibility;
import io.nexus.routemind.optimize.Job;
import io.nexus.routemind.optimize.Problem;
import io.nexus.routemind.optimize.Reasons;
import io.nexus.routemind.optimize.Route;
import io.nexus.routemind.optimize.RouteOptimizer;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.optimize.Solution;
import io.nexus.routemind.optimize.Truck;
import io.nexus.routemind.optimize.Unassigned;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RouteOptimizer on Google OR-Tools (server only, Spec 15a): capacitated vehicle routing with time windows. Weight and
 * pallets are hard capacities, every vehicle is back by the end of its shift, lateness costs as in Evaluation (soft
 * upper bound), time away costs per vehicle (span cost). Each delivery may be dropped at a high penalty, so an
 * overfull wave still gets a plan. Costs in milli-CHF. Greedy descent, time limit as a guard: the same problem gives
 * the same plan.
 */
public final class OrToolsOptimizer implements RouteOptimizer {
  public static final String NAME = "OR-Tools (Routing)";
  private static final long DROP_PENALTY = 1_000_000_000L;
  private static final long LATE_PER_SECOND = Math.round(Evaluation.LATE_COST_PER_MINUTE * 1000 / 60);
  private static final int TIME_LIMIT_SECONDS = 5;

  static {
    Loader.loadNativeLibraries();
  }

  @Override
  public Solution solve(Problem p) {
    var split = Feasibility.split(p);
    List<Unassigned> unassigned = new ArrayList<>(split.unassigned());
    List<Job> jobs = split.plannable();
    if (jobs.isEmpty()) return new Solution(NAME, List.of(), unassigned);
    int n = jobs.size() + 1; // node 0 is the depot
    int[] place = new int[n];
    place[0] = p.depot();
    for (int i = 1; i < n; i++) place[i] = jobs.get(i - 1).place();
    int vehicles = p.trucks().size();
    RoutingIndexManager manager = new RoutingIndexManager(n, vehicles, 0);
    RoutingModel routing = new RoutingModel(manager);

    for (int v = 0; v < vehicles; v++) {
      Truck t = p.trucks().get(v);
      int distance = routing.registerTransitCallback((long from, long to) -> Math.round(
          p.matrix().meters(place[manager.indexToNode(from)], place[manager.indexToNode(to)]) * t.costPerKm()));
      routing.setArcCostEvaluatorOfVehicle(distance, v);
    }
    long[] maxKg = new long[vehicles];
    long[] maxPallets = new long[vehicles];
    long horizon = 0;
    for (int v = 0; v < vehicles; v++) {
      maxKg[v] = p.trucks().get(v).maxKg();
      maxPallets[v] = p.trucks().get(v).maxPallets();
      horizon = Math.max(horizon, (p.trucks().get(v).endMs() - p.departMs()) / 1000);
    }
    int kg = routing.registerUnaryTransitCallback((long i) -> {
      int node = manager.indexToNode(i);
      return node == 0 ? 0 : jobs.get(node - 1).weightKg();
    });
    routing.addDimensionWithVehicleCapacity(kg, 0, maxKg, true, "Gewicht");
    int pallets = routing.registerUnaryTransitCallback((long i) -> {
      int node = manager.indexToNode(i);
      return node == 0 ? 0 : jobs.get(node - 1).pallets();
    });
    routing.addDimensionWithVehicleCapacity(pallets, 0, maxPallets, true, "Paletten");
    int seconds = routing.registerTransitCallback((long from, long to) -> {
      int a = manager.indexToNode(from);
      int b = manager.indexToNode(to);
      long service = a == 0 ? 0 : jobs.get(a - 1).serviceMs() / 1000;
      return service + p.matrix().seconds(place[a], place[b]);
    });
    routing.addDimension(seconds, horizon, horizon, false, "Zeit"); // waiting at a customer is allowed
    RoutingDimension time = routing.getMutableDimension("Zeit");
    for (int v = 0; v < vehicles; v++) {
      Truck t = p.trucks().get(v);
      time.cumulVar(routing.start(v)).setRange(0, 0);
      time.cumulVar(routing.end(v)).setMax((t.endMs() - p.departMs()) / 1000);
      time.setSpanCostCoefficientForVehicle(Math.round(t.costPerHour() * 1000 / 3600), v);
    }
    for (int i = 1; i < n; i++) {
      Job j = jobs.get(i - 1);
      long index = manager.nodeToIndex(i);
      time.cumulVar(index).setMin(Math.max(0, (j.earliestMs() - p.departMs()) / 1000));
      time.setCumulVarSoftUpperBound(index, Math.max(0, (j.latestMs() - p.departMs()) / 1000), LATE_PER_SECOND);
      routing.addDisjunction(new long[] {index}, DROP_PENALTY);
    }

    RoutingSearchParameters params = main.defaultRoutingSearchParameters().toBuilder()
        .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
        .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GREEDY_DESCENT)
        .setTimeLimit(Duration.newBuilder().setSeconds(TIME_LIMIT_SECONDS).build())
        .build();
    Assignment solution = routing.solveWithParameters(params);
    if (solution == null) {
      Solution h = new SavingsHeuristic().solve(p);
      return new Solution("Heuristik (OR-Tools ohne Lösung)", h.routes(), h.unassigned());
    }
    List<Route> routes = new ArrayList<>();
    Set<String> served = new HashSet<>();
    for (int v = 0; v < vehicles; v++) {
      List<String> ids = new ArrayList<>();
      long index = solution.value(routing.nextVar(routing.start(v)));
      while (!routing.isEnd(index)) {
        String id = jobs.get(manager.indexToNode(index) - 1).id();
        ids.add(id);
        served.add(id);
        index = solution.value(routing.nextVar(index));
      }
      if (!ids.isEmpty()) routes.add(new Route(p.trucks().get(v).id(), ids));
    }
    for (Job j : jobs) if (!served.contains(j.id())) unassigned.add(new Unassigned(j.id(), Reasons.CAPACITY));
    return new Solution(NAME, routes, unassigned);
  }
}
