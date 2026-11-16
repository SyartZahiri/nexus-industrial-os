package io.nexus.routemind.optimize;

/** Driving time and distance between place indices (Roads in RouteMind, anything in tests). */
public interface Matrix {
  int seconds(int from, int to);

  int meters(int from, int to);
}
