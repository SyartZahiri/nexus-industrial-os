package io.nexus.smartfactory.analytics;

import io.nexus.contracts.state.MachineStatus;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.smartfactory.catalog.Catalog;
import io.nexus.smartfactory.store.MachineRecord;
import io.nexus.smartfactory.store.MachineStore;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OrderStore;
import io.nexus.smartfactory.store.ProductionOrder;
import io.nexus.smartfactory.store.StateInterval;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Throughput, lead time, on-time rate and per machine time in status, utilization and OEE, from stored orders,
 * operations and state intervals. The window is [fromMs, toMs]; ratios without a denominator are 0, or 1 where
 * nothing went wrong (on-time rate, availability, quality).
 */
public final class Analytics {
  private static final double HOUR_MS = 3_600_000.0;

  private final OrderStore orders;
  private final MachineStore machines;
  private final Catalog catalog;

  public Analytics(OrderStore orders, MachineStore machines, Catalog catalog) {
    this.orders = orders;
    this.machines = machines;
    this.catalog = catalog;
  }

  public Kpis compute(long fromMs, long toMs) {
    long window = toMs - fromMs;
    List<ProductionOrder> all = orders.orders();
    int good = 0;
    int onTime = 0;
    List<Long> leadTimes = new ArrayList<>();
    for (ProductionOrder o : all) {
      if (o.status() != OrderStatus.COMPLETED || !within(o.completedAtMs(), fromMs, toMs)) continue;
      good += o.good();
      leadTimes.add(o.completedAtMs() - o.createdAtMs());
      if (o.completedAtMs() <= o.dueAtMs()) onTime++;
    }
    leadTimes.sort(null);
    long leadSum = 0;
    for (long t : leadTimes) leadSum += t;
    int n = leadTimes.size();
    double avgLead = n == 0 ? 0 : leadSum / 1000.0 / n;
    double p90Lead = n == 0 ? 0 : leadTimes.get((int) Math.ceil(0.9 * n) - 1) / 1000.0;

    // finished operations per machine: planned work (quantity × base time), good and scrap
    Map<String, long[]> work = new TreeMap<>();
    for (ProductionOrder o : all) {
      for (Operation op : orders.operations(o.id())) {
        if (op.status() != Operation.Status.DONE || op.machineId() == null || !within(op.endedAtMs(), fromMs, toMs)) continue;
        long[] w = work.computeIfAbsent(op.machineId(), k -> new long[3]);
        w[0] += (long) (op.good() + op.scrap()) * catalog.step(o.productId(), op.seq()).baseTimeS() * 1000L;
        w[1] += op.good();
        w[2] += op.scrap();
      }
    }

    Map<String, Kpis.MachineKpi> perMachine = new TreeMap<>();
    String bottleneck = null;
    double highest = 0;
    for (MachineRecord m : machines.machines()) {
      Map<MachineStatus, Long> inStatus = new EnumMap<>(MachineStatus.class);
      for (MachineStatus s : MachineStatus.values()) inStatus.put(s, 0L);
      for (StateInterval i : machines.intervals(m.id(), fromMs, toMs)) {
        inStatus.put(i.status(), inStatus.get(i.status()) + (i.toMs() - i.fromMs()));
      }
      long running = inStatus.get(MachineStatus.RUNNING);
      long down = inStatus.get(MachineStatus.FAULT) + inStatus.get(MachineStatus.REPAIR) + inStatus.get(MachineStatus.MAINTENANCE)
          + inStatus.get(MachineStatus.UNKNOWN);                 // an unreachable machine does not produce
      long[] w = work.getOrDefault(m.id(), new long[3]);
      double utilization = window <= 0 ? 0 : (double) running / window;
      double availability = window <= 0 ? 1 : (double) (window - down) / window;
      double performance = running == 0 ? 0 : Math.min(1.0, (double) w[0] / running);
      double quality = w[1] + w[2] == 0 ? 1 : (double) w[1] / (w[1] + w[2]);
      perMachine.put(m.id(), new Kpis.MachineKpi(utilization, inStatus, availability, performance, quality,
          availability * performance * quality));
      if (utilization > highest) { // ties keep the lower id
        highest = utilization;
        bottleneck = m.id();
      }
    }
    double hours = window / HOUR_MS;
    return new Kpis(fromMs, toMs, window <= 0 ? 0 : good / hours, avgLead, p90Lead,
        n == 0 ? 1 : (double) onTime / n, perMachine, bottleneck);
  }

  private static boolean within(Long t, long fromMs, long toMs) {
    return t != null && t >= fromMs && t <= toMs;
  }
}
