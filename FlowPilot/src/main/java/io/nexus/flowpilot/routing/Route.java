package io.nexus.flowpilot.routing;

import java.util.List;

/** A path through the graph as node ids, from start to target, with its length in metres. */
public record Route(List<String> nodes, double length) {
  public static final Route NONE = new Route(List.of(), Double.POSITIVE_INFINITY);

  public boolean found() {
    return !nodes.isEmpty();
  }
}
