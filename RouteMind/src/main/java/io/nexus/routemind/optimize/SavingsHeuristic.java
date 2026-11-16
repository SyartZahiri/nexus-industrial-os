package io.nexus.routemind.optimize;

import java.util.ArrayList;
import java.util.List;

/**
 * Vehicle routing in plain Java (Spec 15a): Clarke-Wright savings on the largest truck, routes to the smallest truck
 * that carries them, left-overs by cheapest insertion, then first-improvement 2-opt within a route and relocation of
 * single stops between routes until nothing improves. Deterministic: every loop runs in index order.
 *
 * <p>ponytail: O(routes² · stops²) per improvement round, fine for a wave of up to ~30 deliveries; a neighbour list
 * would be the upgrade for larger instances.
 */
public final class SavingsHeuristic implements RouteOptimizer {
  public static final String NAME = "Heuristik (Savings, 2-opt, Verschieben)";
  private static final int MAX_ROUNDS = 200;
  private static final double EPS = 1e-9;

  private record Saving(int a, int b, long value) {}

  @Override
  public Solution solve(Problem p) {
    var split = Feasibility.split(p);
    List<Unassigned> unassigned = new ArrayList<>(split.unassigned());
    if (split.plannable().isEmpty()) return new Solution(NAME, List.of(), unassigned);
    Truck biggest = p.trucks().get(0);
    for (Truck t : p.trucks()) if (t.maxKg() > biggest.maxKg()) biggest = t;
    List<List<Job>> routes = savings(p, split.plannable(), biggest);
    List<List<Job>> jobs = new ArrayList<>();
    List<Truck> trucks = new ArrayList<>();
    assign(p, routes, jobs, trucks, unassigned);
    improve(p, jobs, trucks);
    Solution fromSavings = solution(jobs, trucks, unassigned);
    // the start plan, improved the same way, is the floor: the result is never worse than nearest neighbour
    Solution nn = new NearestNeighbour().solve(p);
    List<List<Job>> nnJobs = new ArrayList<>();
    List<Truck> nnTrucks = new ArrayList<>();
    for (Route r : nn.routes()) {
      nnJobs.add(new ArrayList<>(Evaluation.jobs(p, r.jobIds())));
      nnTrucks.add(p.truck(r.truckId()));
    }
    improve(p, nnJobs, nnTrucks);
    Solution fromNn = solution(nnJobs, nnTrucks, nn.unassigned());
    if (fromNn.unassigned().size() < fromSavings.unassigned().size()) return fromNn;
    if (fromNn.unassigned().size() == fromSavings.unassigned().size()
        && Evaluation.of(p, fromNn).cost() < Evaluation.of(p, fromSavings).cost() - EPS) return fromNn;
    return fromSavings;
  }

  private static Solution solution(List<List<Job>> jobs, List<Truck> trucks, List<Unassigned> unassigned) {
    List<Route> out = new ArrayList<>();
    for (int i = 0; i < jobs.size(); i++) {
      if (!jobs.get(i).isEmpty()) out.add(new Route(trucks.get(i).id(), Evaluation.ids(jobs.get(i))));
    }
    return new Solution(NAME, out, unassigned);
  }

  private static List<List<Job>> savings(Problem p, List<Job> jobs, Truck truck) {
    List<List<Job>> routes = new ArrayList<>();
    for (Job j : jobs) {
      List<Job> r = new ArrayList<>();
      r.add(j);
      routes.add(r);
    }
    Matrix m = p.matrix();
    int d = p.depot();
    List<Saving> list = new ArrayList<>();
    for (int a = 0; a < jobs.size(); a++) {
      for (int b = 0; b < jobs.size(); b++) {
        if (a == b) continue;
        int x = jobs.get(a).place();
        int y = jobs.get(b).place();
        long value = (long) m.seconds(x, d) + m.seconds(d, y) - m.seconds(x, y);
        if (value > 0) list.add(new Saving(a, b, value));
      }
    }
    list.sort((s, t) -> s.value() != t.value() ? Long.compare(t.value(), s.value())
        : s.a() != t.a() ? Integer.compare(s.a(), t.a()) : Integer.compare(s.b(), t.b()));
    for (Saving s : list) {
      List<Job> ending = null;
      List<Job> starting = null;
      for (List<Job> r : routes) {
        if (r.get(r.size() - 1) == jobs.get(s.a())) ending = r;
        if (r.get(0) == jobs.get(s.b())) starting = r;
      }
      if (ending == null || starting == null || ending == starting) continue;
      List<Job> merged = new ArrayList<>(ending);
      merged.addAll(starting);
      var f = Evaluation.route(p, truck, merged);
      if (!f.fits(truck)) continue;
      double apart = Evaluation.route(p, truck, ending).cost() + Evaluation.route(p, truck, starting).cost();
      if (f.cost() > apart + EPS) continue;
      routes.remove(starting);
      ending.clear();
      ending.addAll(merged);
    }
    return routes;
  }

  /** Heaviest route first, each on the smallest free truck that carries it within its shift. */
  private static void assign(Problem p, List<List<Job>> routes, List<List<Job>> jobs, List<Truck> trucks,
                             List<Unassigned> unassigned) {
    List<Truck> free = new ArrayList<>(p.trucks());
    free.sort((a, b) -> a.maxKg() != b.maxKg() ? Integer.compare(a.maxKg(), b.maxKg()) : a.id().compareTo(b.id()));
    routes.sort((a, b) -> {
      int wa = weight(a);
      int wb = weight(b);
      return wa != wb ? Integer.compare(wb, wa) : a.get(0).id().compareTo(b.get(0).id());
    });
    List<Job> left = new ArrayList<>();
    for (List<Job> r : routes) {
      Truck chosen = null;
      for (Truck t : free) {
        if (Evaluation.route(p, t, r).fits(t)) {
          chosen = t;
          break;
        }
      }
      if (chosen == null) {
        left.addAll(r);
        continue;
      }
      free.remove(chosen);
      jobs.add(new ArrayList<>(r));
      trucks.add(chosen);
    }
    left.sort((a, b) -> a.id().compareTo(b.id()));
    for (Job j : left) {
      if (!insertCheapest(p, jobs, trucks, j)) unassigned.add(new Unassigned(j.id(), Reasons.CAPACITY));
    }
  }

  private static boolean insertCheapest(Problem p, List<List<Job>> jobs, List<Truck> trucks, Job j) {
    int bestRoute = -1;
    int bestPos = -1;
    double bestDelta = Double.MAX_VALUE;
    for (int k = 0; k < jobs.size(); k++) {
      double before = cost(p, trucks.get(k), jobs.get(k));
      for (int pos = 0; pos <= jobs.get(k).size(); pos++) {
        List<Job> tried = new ArrayList<>(jobs.get(k));
        tried.add(pos, j);
        var f = Evaluation.route(p, trucks.get(k), tried);
        if (!f.fits(trucks.get(k))) continue;
        double delta = f.cost() - before;
        if (delta < bestDelta - EPS) {
          bestDelta = delta;
          bestRoute = k;
          bestPos = pos;
        }
      }
    }
    if (bestRoute < 0) return false;
    jobs.get(bestRoute).add(bestPos, j);
    return true;
  }

  private static void improve(Problem p, List<List<Job>> jobs, List<Truck> trucks) {
    for (int round = 0; round < MAX_ROUNDS; round++) {
      if (!twoOpt(p, jobs, trucks) && !relocate(p, jobs, trucks)) return;
    }
  }

  /** First reversal of a segment within one route that lowers its cost. */
  private static boolean twoOpt(Problem p, List<List<Job>> jobs, List<Truck> trucks) {
    for (int k = 0; k < jobs.size(); k++) {
      List<Job> route = jobs.get(k);
      double base = cost(p, trucks.get(k), route);
      for (int i = 0; i < route.size() - 1; i++) {
        for (int j = i + 1; j < route.size(); j++) {
          List<Job> c = new ArrayList<>(route);
          for (int a = i, b = j; a < b; a++, b--) {
            Job tmp = c.get(a);
            c.set(a, c.get(b));
            c.set(b, tmp);
          }
          var f = Evaluation.route(p, trucks.get(k), c);
          if (f.fits(trucks.get(k)) && f.cost() < base - EPS) {
            jobs.set(k, c);
            return true;
          }
        }
      }
    }
    return false;
  }

  /** First move of one stop to another position, in its own route or another one, that lowers the total cost. */
  private static boolean relocate(Problem p, List<List<Job>> jobs, List<Truck> trucks) {
    for (int a = 0; a < jobs.size(); a++) {
      for (int i = 0; i < jobs.get(a).size(); i++) {
        for (int b = 0; b < jobs.size(); b++) {
          List<Job> from = new ArrayList<>(jobs.get(a));
          Job moved = from.remove(i);
          List<Job> base = a == b ? from : jobs.get(b);
          double before = cost(p, trucks.get(a), jobs.get(a)) + (a == b ? 0 : cost(p, trucks.get(b), jobs.get(b)));
          for (int pos = 0; pos <= base.size(); pos++) {
            if (a == b && pos == i) continue;
            List<Job> to = new ArrayList<>(base);
            to.add(pos, moved);
            if (!Evaluation.route(p, trucks.get(b), to).fits(trucks.get(b))) continue;
            if (a != b && !Evaluation.route(p, trucks.get(a), from).fits(trucks.get(a))) continue;
            double after = a == b ? cost(p, trucks.get(a), to) : cost(p, trucks.get(a), from) + cost(p, trucks.get(b), to);
            if (after < before - EPS) {
              if (a == b) {
                jobs.set(a, to);
              } else {
                jobs.set(a, from);
                jobs.set(b, to);
              }
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private static double cost(Problem p, Truck t, List<Job> route) {
    return Evaluation.route(p, t, route).cost();
  }

  private static int weight(List<Job> route) {
    int kg = 0;
    for (Job j : route) kg += j.weightKg();
    return kg;
  }
}
