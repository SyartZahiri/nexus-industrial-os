package io.nexus.routemind.optimize;

import java.util.ArrayList;
import java.util.List;

/**
 * Times and costs of routes, the same for every solver, so plans are compared on one scale. A truck waits at a
 * customer until the window opens; arriving after it closes costs LATE_COST_PER_MINUTE (soft limit). Cost in CHF:
 * distance × price per km + time away from the depot × price per hour + lateness.
 */
public final class Evaluation {
  public static final double LATE_COST_PER_MINUTE = 2.0;

  public record StopTime(String jobId, long arrivalMs, long startMs, long lateMs) {}

  public record RouteFigures(String truckId, List<StopTime> stops, long returnMs, int meters, int driveSeconds,
                             double cost, double lateMinutes, int weightKg, int pallets) {
    public boolean fits(Truck t) {
      return weightKg <= t.maxKg() && pallets <= t.maxPallets() && returnMs <= t.endMs();
    }
  }

  public record Figures(double km, double driveMinutes, double cost, double lateMinutes, int tours) {}

  private Evaluation() {}

  public static RouteFigures route(Problem p, Truck t, List<Job> jobs) {
    long time = p.departMs();
    int at = p.depot();
    int meters = 0;
    int drive = 0;
    int kg = 0;
    int pallets = 0;
    long late = 0;
    List<StopTime> stops = new ArrayList<>();
    for (Job j : jobs) {
      int s = p.matrix().seconds(at, j.place());
      drive += s;
      meters += p.matrix().meters(at, j.place());
      long arrival = time + s * 1000L;
      long start = Math.max(arrival, j.earliestMs());
      long l = Math.max(0, start - j.latestMs());
      late += l;
      stops.add(new StopTime(j.id(), arrival, start, l));
      time = start + j.serviceMs();
      at = j.place();
      kg += j.weightKg();
      pallets += j.pallets();
    }
    if (!jobs.isEmpty()) {
      int s = p.matrix().seconds(at, p.depot());
      drive += s;
      meters += p.matrix().meters(at, p.depot());
      time += s * 1000L;
    }
    double lateMinutes = late / 60_000.0;
    double cost = jobs.isEmpty() ? 0
        : meters / 1000.0 * t.costPerKm() + (time - p.departMs()) / 3_600_000.0 * t.costPerHour()
            + lateMinutes * LATE_COST_PER_MINUTE;
    return new RouteFigures(t.id(), stops, time, meters, drive, cost, lateMinutes, kg, pallets);
  }

  public static Figures of(Problem p, Solution s) {
    double km = 0;
    double drive = 0;
    double cost = 0;
    double late = 0;
    int tours = 0;
    for (Route r : s.routes()) {
      if (r.jobIds().isEmpty()) continue;
      RouteFigures f = route(p, p.truck(r.truckId()), jobs(p, r.jobIds()));
      km += f.meters() / 1000.0;
      drive += f.driveSeconds() / 60.0;
      cost += f.cost();
      late += f.lateMinutes();
      tours++;
    }
    return new Figures(km, drive, cost, late, tours);
  }

  public static List<Job> jobs(Problem p, List<String> ids) {
    List<Job> out = new ArrayList<>();
    for (String id : ids) out.add(p.job(id));
    return out;
  }

  static List<String> ids(List<Job> jobs) {
    List<String> out = new ArrayList<>();
    for (Job j : jobs) out.add(j.id());
    return out;
  }
}
