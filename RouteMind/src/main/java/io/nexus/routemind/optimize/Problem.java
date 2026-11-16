package io.nexus.routemind.optimize;

import io.nexus.contracts.DomainException;
import java.util.List;

/** All trucks leave the depot at departMs. Jobs are given in id order; solvers keep their results deterministic. */
public record Problem(long departMs, int depot, List<Truck> trucks, List<Job> jobs, Matrix matrix) {
  public Problem {
    trucks = List.copyOf(trucks);
    jobs = List.copyOf(jobs);
  }

  public Job job(String id) {
    for (Job j : jobs) if (j.id().equals(id)) return j;
    throw DomainException.notFound("Lieferung", id);
  }

  public Truck truck(String id) {
    for (Truck t : trucks) if (t.id().equals(id)) return t;
    throw DomainException.notFound("Lieferfahrzeug", id);
  }
}
