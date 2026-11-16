package io.nexus.app.routemind;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.routemind.optimize.Evaluation;
import io.nexus.routemind.optimize.OptimizerTest;
import io.nexus.routemind.optimize.Problem;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.optimize.Solution;
import org.junit.jupiter.api.Test;

/** Spec 17: heuristic against OR-Tools on fixed instances. */
class OrToolsOptimizerTest {
  private final OrToolsOptimizer ortools = new OrToolsOptimizer();

  @Test
  void solutionsKeepEveryRule() {
    for (long seed = 1; seed <= 10; seed++) {
      Problem p = OptimizerTest.instance(seed, 5 + (int) (seed % 20));
      Solution s = ortools.solve(p);
      assertThat(s.solver()).isEqualTo(OrToolsOptimizer.NAME);
      OptimizerTest.assertValid(p, s);
    }
  }

  @Test
  void heuristicAndOrToolsAreClose() {
    double ratioSum = 0;
    int compared = 0;
    for (long seed = 1; seed <= 10; seed++) {
      Problem p = OptimizerTest.instance(seed, 12);
      Solution o = ortools.solve(p);
      Solution h = new SavingsHeuristic().solve(p);
      if (o.unassigned().size() != h.unassigned().size()) continue;
      double oc = Evaluation.of(p, o).cost();
      double hc = Evaluation.of(p, h).cost();
      System.out.printf("seed %d: OR-Tools %.2f CHF, Heuristik %.2f CHF%n", seed, oc, hc);
      ratioSum += hc / oc;
      compared++;
    }
    assertThat(compared).isGreaterThanOrEqualTo(8);
    assertThat(ratioSum / compared).as("mean heuristic/OR-Tools cost").isBetween(0.8, 1.25);
  }

  @Test
  void sameProblemSameSolution() {
    Problem p = OptimizerTest.instance(3, 15);
    assertThat(ortools.solve(p)).isEqualTo(ortools.solve(p));
  }
}
