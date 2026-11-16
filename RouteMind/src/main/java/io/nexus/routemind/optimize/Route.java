package io.nexus.routemind.optimize;

import java.util.List;

/** The jobs one truck serves, in driving order. */
public record Route(String truckId, List<String> jobIds) {
  public Route {
    jobIds = List.copyOf(jobIds);
  }
}
