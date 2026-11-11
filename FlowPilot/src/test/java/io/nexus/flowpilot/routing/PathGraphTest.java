package io.nexus.flowpilot.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.nexus.contracts.DomainException;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.master.FlowPilotMasterData.Edge;
import io.nexus.flowpilot.master.FlowPilotMasterData.Node;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PathGraphTest {
  private final PathGraph graph = new PathGraph(FlowPilotMasterData.nodes(), FlowPilotMasterData.edges());

  /** Reference: plain Dijkstra over the unblocked graph. */
  private static double dijkstra(String from, String to) {
    Map<String, Double> dist = new HashMap<>();
    record Item(String id, double d) {}
    var open = new PriorityQueue<Item>((a, b) -> Double.compare(a.d(), b.d()));
    dist.put(from, 0.0);
    open.add(new Item(from, 0));
    while (!open.isEmpty()) {
      Item cur = open.poll();
      if (cur.d() > dist.get(cur.id())) continue;
      if (cur.id().equals(to)) return cur.d();
      for (Edge e : FlowPilotMasterData.edges()) {
        if (!e.from().equals(cur.id())) continue;
        double nd = cur.d() + e.length();
        if (nd < dist.getOrDefault(e.to(), Double.POSITIVE_INFINITY)) {
          dist.put(e.to(), nd);
          open.add(new Item(e.to(), nd));
        }
      }
    }
    return Double.POSITIVE_INFINITY;
  }

  private static List<String> docks() {
    return FlowPilotMasterData.nodes().stream().filter(Node::dock).map(Node::id).toList();
  }

  private static double length(List<String> nodes) {
    var g = new PathGraph(FlowPilotMasterData.nodes(), FlowPilotMasterData.edges());
    double sum = 0;
    for (int i = 1; i < nodes.size(); i++) sum += g.edge(nodes.get(i - 1), nodes.get(i)).length();
    return sum;
  }

  @Test
  void aStarFindsTheSameLengthAsDijkstraForEveryPairOfDocks() {
    int compared = 0;
    for (String a : docks()) {
      for (String b : docks()) {
        double expected = dijkstra(a, b);
        Route r = graph.route(a, b, Set.of());
        assertThat(r.found()).as(a + " → " + b).isEqualTo(expected != Double.POSITIVE_INFINITY);
        if (!r.found()) continue;
        assertThat(r.length()).as(a + " → " + b).isCloseTo(expected, within(1e-9));
        assertThat(r.nodes()).startsWith(a).endsWith(b);
        assertThat(length(r.nodes())).isCloseTo(r.length(), within(1e-9));
        compared++;
      }
    }
    assertThat(compared).isGreaterThan(100);
  }

  @Test
  void blockedEdgeForcesTheDetourAndUnblockingRestoresTheShortRoute() {
    Route shortRoute = graph.route("D_RAW", "D_CNC04", Set.of());
    assertThat(shortRoute.found()).isTrue();
    int detours = 0;
    for (int i = 1; i < shortRoute.nodes().size(); i++) {
      Edge e = graph.edge(shortRoute.nodes().get(i - 1), shortRoute.nodes().get(i));
      graph.setBlocked(e.id(), true);
      assertThat(graph.blockedEdges()).containsExactly(e.id());
      Route detour = graph.route("D_RAW", "D_CNC04", Set.of());
      if (detour.found()) {
        detours++;
        assertThat(detour.length()).isGreaterThan(shortRoute.length());
        for (int k = 1; k < detour.nodes().size(); k++) {
          assertThat(graph.edge(detour.nodes().get(k - 1), detour.nodes().get(k)).id()).isNotEqualTo(e.id());
        }
      }
      graph.setBlocked(e.id(), false);
      assertThat(graph.route("D_RAW", "D_CNC04", Set.of())).isEqualTo(shortRoute);
    }
    assertThat(detours).isPositive();
  }

  @Test
  void unreachableTargetGivesNone() {
    for (Edge e : FlowPilotMasterData.edges()) {
      if (e.to().equals("D_CNC01")) graph.setBlocked(e.id(), true);
    }
    assertThat(graph.route("D_RAW", "D_CNC01", Set.of())).isEqualTo(Route.NONE);
    assertThat(Route.NONE.found()).isFalse();
  }

  @Test
  void avoidedNodeIsNotPassedButMayBeTheTarget() {
    int avoided = 0;
    for (String a : docks()) {
      for (String b : docks()) {
        Route direct = graph.route(a, b, Set.of());
        if (direct.nodes().size() < 3) continue;
        for (String middle : direct.nodes().subList(1, direct.nodes().size() - 1)) {
          Route around = graph.route(a, b, Set.of(middle));
          if (around.found()) {
            avoided++;
            assertThat(around.nodes()).doesNotContain(middle);
            assertThat(around.length()).isGreaterThanOrEqualTo(direct.length());
          }
          Route toIt = graph.route(a, middle, Set.of(middle));
          assertThat(toIt.found()).isTrue();
          assertThat(toIt.nodes()).endsWith(middle);
        }
      }
    }
    assertThat(avoided).as("some node on some route can be bypassed").isPositive();
  }

  @Test
  void routeToItselfIsEmptyLength() {
    assertThat(graph.route("D_RAW", "D_RAW", Set.of())).isEqualTo(new Route(List.of("D_RAW"), 0));
  }

  @Test
  void routeIsDeterministic() {
    for (String a : docks()) {
      for (String b : docks()) {
        assertThat(graph.route(a, b, Set.of()).nodes()).isEqualTo(
            new PathGraph(FlowPilotMasterData.nodes(), FlowPilotMasterData.edges()).route(a, b, Set.of()).nodes());
      }
    }
  }

  @Test
  void unknownEdgeIdIsNotFound() {
    assertThatThrownBy(() -> graph.setBlocked("X>Y", true))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.NOT_FOUND));
    assertThatThrownBy(() -> graph.node("NOPE")).isInstanceOf(DomainException.class);
  }
}
