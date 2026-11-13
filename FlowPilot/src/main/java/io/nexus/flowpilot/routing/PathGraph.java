package io.nexus.flowpilot.routing;

import io.nexus.contracts.DomainException;
import io.nexus.flowpilot.master.FlowPilotMasterData.Edge;
import io.nexus.flowpilot.master.FlowPilotMasterData.Node;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/** Directed path graph of the hall with blockable edges and A* routing. Maps are used for lookups only. */
public final class PathGraph {
  private final Map<String, Node> nodes = new HashMap<>();
  private final Map<String, Edge> edgesById = new HashMap<>();
  private final Map<String, Edge> edgesByEnds = new HashMap<>();
  private final Map<String, List<Edge>> outgoing = new HashMap<>();
  private final TreeSet<String> blocked = new TreeSet<>();

  private record Open(String id, double f, double g) {}

  public PathGraph(List<Node> nodes, List<Edge> edges) {
    for (Node n : nodes) {
      this.nodes.put(n.id(), n);
      outgoing.put(n.id(), new ArrayList<>());
    }
    for (Edge e : edges) {
      edgesById.put(e.id(), e);
      edgesByEnds.put(e.from() + ">" + e.to(), e);
      outgoing.get(e.from()).add(e);
    }
    for (List<Edge> out : outgoing.values()) out.sort(Comparator.comparing(Edge::to));
  }

  /** @throws DomainException NOT_FOUND for an unknown edge id */
  public void setBlocked(String edgeId, boolean isBlocked) {
    if (!edgesById.containsKey(edgeId)) throw DomainException.notFound("Kante", edgeId);
    if (isBlocked) blocked.add(edgeId);
    else blocked.remove(edgeId);
  }

  /** Blocked edge ids in sorted order. */
  public Set<String> blockedEdges() {
    return Collections.unmodifiableSet(blocked);
  }

  public boolean isBlocked(String edgeId) {
    return blocked.contains(edgeId);
  }

  public Node node(String id) {
    Node n = nodes.get(id);
    if (n == null) throw DomainException.notFound("Knoten", id);
    return n;
  }

  /** The edge from → to, or null if the two nodes are not directly connected. */
  public Edge edge(String from, String to) {
    return edgesByEnds.get(from + ">" + to);
  }

  /**
   * Shortest route avoiding blocked edges and the given nodes (except from and to). The heuristic is the
   * straight-line distance, admissible because no edge is shorter than the line between its ends. Ties are
   * broken by node id, so the result is deterministic.
   */
  public Route route(String from, String to, Set<String> avoidNodes) {
    if (from.equals(to)) return new Route(List.of(from), 0);
    Node goal = node(to);
    Map<String, Double> g = new HashMap<>();          // lookups only, never iterated
    Map<String, String> prev = new HashMap<>();
    PriorityQueue<Open> open = new PriorityQueue<>(
        Comparator.comparingDouble(Open::f).thenComparing(Open::id));
    g.put(from, 0.0);
    open.add(new Open(from, heuristic(node(from), goal), 0));
    while (!open.isEmpty()) {
      Open cur = open.poll();
      if (cur.g() > g.get(cur.id())) continue;        // stale entry
      if (cur.id().equals(to)) return rebuild(prev, from, to, cur.g());
      for (Edge e : outgoing.get(cur.id())) {         // sorted by target id
        if (blocked.contains(e.id())) continue;
        if (avoidNodes.contains(e.to()) && !e.to().equals(to)) continue;
        double ng = cur.g() + e.length();
        if (ng < g.getOrDefault(e.to(), Double.POSITIVE_INFINITY)) {
          g.put(e.to(), ng);
          prev.put(e.to(), cur.id());
          open.add(new Open(e.to(), ng + heuristic(node(e.to()), goal), ng));
        }
      }
    }
    return Route.NONE;
  }

  private static double heuristic(Node a, Node b) {
    double dx = a.x() - b.x(), dz = a.z() - b.z();
    return Math.sqrt(dx * dx + dz * dz);
  }

  private static Route rebuild(Map<String, String> prev, String from, String to, double length) {
    List<String> path = new ArrayList<>();
    for (String at = to; at != null; at = at.equals(from) ? null : prev.get(at)) path.add(at);
    Collections.reverse(path);
    return new Route(List.copyOf(path), length);
  }
}
