package io.nexus.routemind.optimize;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.sim.SeededRandom;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.master.Roads;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class OptimizerTest {
  static final Matrix ROADS = new Matrix() {
    @Override public int seconds(int from, int to) { return Roads.seconds(from, to); }
    @Override public int meters(int from, int to) { return Roads.meters(from, to); }
  };
  static final long DEPART = 2 * RouteMindMasterData.HOUR;
  static final long MIN = RouteMindMasterData.MINUTE;

  static List<Truck> fleet(long endMs) {
    List<Truck> out = new ArrayList<>();
    for (var v : RouteMindMasterData.vehicles()) {
      out.add(new Truck(v.id(), v.maxKg(), v.maxPallets(), v.costPerKm(), v.costPerHour(), endMs));
    }
    return out;
  }

  /** n jobs at random customers, 50..650 kg, 1..3 pallets, windows of 2..6 h starting up to 1 h after departure. */
  public static Problem instance(long seed, int n) {
    var r = new SeededRandom(seed);
    List<Job> jobs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int place = 1 + r.nextInt(30);
      long earliest = DEPART + r.nextInt(61) * MIN;
      jobs.add(new Job(String.format("J%02d", i), place, 50 + r.nextInt(601), 1 + r.nextInt(3), earliest,
          earliest + (2 + r.nextInt(5)) * RouteMindMasterData.HOUR, 10 * MIN));
    }
    return new Problem(DEPART, 0, fleet(DEPART + 10 * RouteMindMasterData.HOUR), jobs, ROADS);
  }

  public static void assertValid(Problem p, Solution s) {
    Set<String> seen = new HashSet<>();
    for (Route route : s.routes()) {
      Truck t = p.truck(route.truckId());
      var f = Evaluation.route(p, t, Evaluation.jobs(p, route.jobIds()));
      assertThat(f.weightKg()).as(route.truckId()).isLessThanOrEqualTo(t.maxKg());
      assertThat(f.pallets()).as(route.truckId()).isLessThanOrEqualTo(t.maxPallets());
      assertThat(f.returnMs()).as(route.truckId()).isLessThanOrEqualTo(t.endMs());
      for (String id : route.jobIds()) assertThat(seen.add(id)).as("planned once: " + id).isTrue();
    }
    for (Unassigned u : s.unassigned()) {
      assertThat(seen.add(u.jobId())).as("planned or open, not both: " + u.jobId()).isTrue();
      assertThat(u.reason()).isIn(Reasons.WINDOW, Reasons.CAPACITY, Reasons.SHIFT);
    }
    Set<String> all = new HashSet<>();
    for (Job j : p.jobs()) all.add(j.id());
    assertThat(seen).isEqualTo(all);
    Set<String> trucks = new HashSet<>();
    for (Route route : s.routes()) assertThat(trucks.add(route.truckId())).as("one route per truck").isTrue();
  }

  @Test
  void everyJobIsPlannedOrReportedWithAReason() {
    for (long seed = 1; seed <= 20; seed++) {
      Problem p = instance(seed, 5 + (int) (seed % 20));
      assertValid(p, new NearestNeighbour().solve(p));
      assertValid(p, new SavingsHeuristic().solve(p));
    }
  }

  @Test
  void reasons() {
    long end = DEPART + 10 * RouteMindMasterData.HOUR;
    var tooHeavy = new Job("HEAVY", 1, 3500, 2, DEPART, DEPART + 4 * RouteMindMasterData.HOUR, 10 * MIN);
    var tooLate = new Job("LATE", 24, 100, 1, DEPART, DEPART + 5 * MIN, 10 * MIN); // Winterthur is not 5 min away
    var afterShift = new Job("SHIFT", 24, 100, 1, end - 10 * MIN, end + RouteMindMasterData.HOUR, 10 * MIN);
    var fine = new Job("OK", 1, 100, 1, DEPART, DEPART + 4 * RouteMindMasterData.HOUR, 10 * MIN);
    var p = new Problem(DEPART, 0, fleet(end), List.of(tooHeavy, tooLate, afterShift, fine), ROADS);
    var s = new SavingsHeuristic().solve(p);
    assertThat(s.unassigned()).containsExactlyInAnyOrder(new Unassigned("HEAVY", Reasons.CAPACITY),
        new Unassigned("SHIFT", Reasons.SHIFT));
    // a window that cannot be met is soft: the job is planned (and late), and reported as late
    assertThat(s.routes()).flatExtracting(Route::jobIds).containsExactlyInAnyOrder("LATE", "OK");
    assertThat(Feasibility.split(p).late()).containsExactly(new Unassigned("LATE", Reasons.WINDOW));
    var none = new Problem(DEPART, 0, List.of(), List.of(fine), ROADS);
    assertThat(new SavingsHeuristic().solve(none).unassigned()).containsExactly(new Unassigned("OK", Reasons.SHIFT));
  }

  @Test
  void heuristicIsNotWorseThanNearestNeighbour() {
    for (long seed = 1; seed <= 10; seed++) {
      Problem p = instance(seed, 12);
      Solution nn = new NearestNeighbour().solve(p);
      Solution h = new SavingsHeuristic().solve(p);
      assertThat(h.unassigned().size()).as("seed " + seed).isLessThanOrEqualTo(nn.unassigned().size());
      if (h.unassigned().size() == nn.unassigned().size()) {
        assertThat(Evaluation.of(p, h).cost()).as("seed " + seed).isLessThanOrEqualTo(Evaluation.of(p, nn).cost() + 1e-6);
      }
    }
  }

  @Test
  void sameProblemSameSolution() {
    Problem p = instance(7, 15);
    assertThat(new SavingsHeuristic().solve(p)).isEqualTo(new SavingsHeuristic().solve(p));
    assertThat(new NearestNeighbour().solve(p)).isEqualTo(new NearestNeighbour().solve(p));
  }
}
