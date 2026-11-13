package io.nexus.smartfactory.planning;

import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.planning.Plan.PlannedOp;
import io.nexus.smartfactory.planning.Plan.Unplannable;
import io.nexus.smartfactory.planning.PlanInput.MachineState;
import io.nexus.smartfactory.planning.PlanInput.OpToPlan;
import io.nexus.smartfactory.planning.PlanInput.OrderToPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Forward scheduling with finite capacity: orders in the sequence of the dispatch rule, each step on the capable,
 * available machine where it ends earliest, after its predecessor, with setup and around maintenance windows.
 * A pure function: same input, same plan, in both runtimes.
 */
public final class Scheduler {
  private Scheduler() {}

  public static Plan plan(PlanInput in) {
    Map<String, MachineCursor> cursors = new TreeMap<>();      // free time and last product per machine
    for (MachineState m : in.machines()) cursors.put(m.id(), new MachineCursor(m, Math.max(in.nowMs(), m.busyUntilMs())));
    List<PlannedOp> planned = new ArrayList<>();
    List<Unplannable> unplannable = new ArrayList<>();
    List<String> late = new ArrayList<>();
    for (OrderToPlan order : sequence(in.orders(), in.rule())) {
      long ready = in.nowMs();
      boolean blocked = false;
      for (OpToPlan op : order.ops()) {
        if (op.doneAtMs() != null) { ready = Math.max(ready, op.doneAtMs()); continue; }
        if (op.runningUntilMs() != null) { ready = Math.max(ready, op.runningUntilMs()); continue; }
        if (blocked) { unplannable.add(new Unplannable(order.orderId(), op.seq(), "Vorgänger nicht planbar")); continue; }
        Choice best = null;
        for (MachineCursor c : cursors.values()) {             // sorted by machine id
          if (!c.machine.capabilities().contains(op.process())) continue;
          if (op.fixedMachineId() != null && !op.fixedMachineId().equals(c.machine.id())) continue;
          if (!c.machine.available()) continue;
          long setup = order.productId().equals(c.lastProductId) ? 0 : SmartFactoryMasterData.setupMs(c.machine.kind());
          long duration = setup + (long) order.quantity() * op.baseTimeS() * 1000L;
          long start = c.firstGap(Math.max(ready, c.freeAt), duration);   // skips maintenance windows
          var choice = new Choice(c, start, start + duration, setup);
          if (best == null || choice.end() < best.end()
              || (choice.end() == best.end() && c.machine.id().equals(op.preferredMachineId()))) best = choice;
        }
        if (best == null) {
          unplannable.add(new Unplannable(order.orderId(), op.seq(), "keine verfügbare Maschine für " + op.process()));
          blocked = true;
          continue;
        }
        best.cursor().book(best.start(), best.end(), order.productId());
        planned.add(new PlannedOp(order.orderId(), op.seq(), best.cursor().machine.id(), best.start(), best.end(), best.setup()));
        ready = best.end();
      }
      if (!blocked && ready > order.dueAtMs()) late.add(order.orderId());
    }
    return new Plan(planned, unplannable, late, utilization(cursors, in.nowMs(), planned), bottleneck(cursors, planned));
  }

  private static List<OrderToPlan> sequence(List<OrderToPlan> orders, DispatchRule rule) {
    Comparator<OrderToPlan> byId = Comparator.comparing(OrderToPlan::orderId);
    Comparator<OrderToPlan> order = switch (rule) {
      case PRIORITY -> Comparator.<OrderToPlan>comparingInt(o -> -o.priority().ordinal())
          .thenComparingLong(OrderToPlan::dueAtMs).thenComparingLong(OrderToPlan::createdAtMs).thenComparing(byId);
      case EDD -> Comparator.comparingLong(OrderToPlan::dueAtMs).thenComparing(byId);
      case FIFO -> Comparator.comparingLong(OrderToPlan::createdAtMs).thenComparing(byId);
      case SPT -> Comparator.comparingLong(Scheduler::remainingWork).thenComparing(byId);
    };
    List<OrderToPlan> sorted = new ArrayList<>(orders);
    sorted.sort(order);
    return sorted;
  }

  private static long remainingWork(OrderToPlan o) {
    long work = 0;
    for (OpToPlan op : o.ops()) {
      if (op.doneAtMs() == null && op.runningUntilMs() == null) work += (long) o.quantity() * op.baseTimeS();
    }
    return work;
  }

  private static Map<String, Double> utilization(Map<String, MachineCursor> cursors, long nowMs, List<PlannedOp> planned) {
    long horizon = nowMs;
    for (PlannedOp p : planned) horizon = Math.max(horizon, p.endMs());
    Map<String, Double> out = new TreeMap<>();
    for (MachineCursor c : cursors.values()) {
      out.put(c.machine.id(), horizon == nowMs ? 0.0 : (double) c.bookedMs / (horizon - nowMs));
    }
    return out;
  }

  private static String bottleneck(Map<String, MachineCursor> cursors, List<PlannedOp> planned) {
    if (planned.isEmpty()) return null;
    MachineCursor best = null;
    for (MachineCursor c : cursors.values()) if (best == null || c.bookedMs > best.bookedMs) best = c; // ties: first id
    return best.machine.id();
  }

  private record Choice(MachineCursor cursor, long start, long end, long setup) {}

  /** Booking state of one machine during a planning run. */
  private static final class MachineCursor {
    final MachineState machine;
    long freeAt;
    String lastProductId;
    long bookedMs;
    final List<long[]> blocked = new ArrayList<>();

    MachineCursor(MachineState machine, long freeAt) {
      this.machine = machine;
      this.freeAt = freeAt;
      this.lastProductId = machine.lastProductId();
      blocked.addAll(machine.maintenance());
    }

    /** Earliest start at or after {@code from} where [start, start + duration) overlaps no booking or maintenance. */
    long firstGap(long from, long duration) {
      long start = from;
      boolean moved = true;
      while (moved) {
        moved = false;
        for (long[] b : blocked) {
          if (start < b[1] && start + duration > b[0]) {
            start = b[1];
            moved = true;
          }
        }
      }
      return start;
    }

    void book(long start, long end, String productId) {
      blocked.add(new long[] {start, end});
      freeAt = Math.max(freeAt, end);
      lastProductId = productId;
      bookedMs += end - start;
    }
  }
}
