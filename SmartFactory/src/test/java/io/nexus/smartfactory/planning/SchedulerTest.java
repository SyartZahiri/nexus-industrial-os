package io.nexus.smartfactory.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.smartfactory.catalog.Catalog;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.planning.Plan.PlannedOp;
import io.nexus.smartfactory.planning.PlanInput.MachineState;
import io.nexus.smartfactory.planning.PlanInput.OpToPlan;
import io.nexus.smartfactory.planning.PlanInput.OrderToPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SchedulerTest {
  private static final Catalog CATALOG = new Catalog();

  private static MachineState machine(String id, boolean available, long busyUntil, String lastProduct,
                                      List<long[]> maintenance) {
    var m = SmartFactoryMasterData.machine(id);
    return new MachineState(id, m.kind(), m.capabilities(), available, busyUntil, lastProduct, maintenance);
  }

  private static MachineState machine(String id) {
    return machine(id, true, 0, null, List.of());
  }

  private static List<MachineState> all() {
    return SmartFactoryMasterData.machines().stream().map(m -> machine(m.id())).toList();
  }

  private static List<MachineState> allBut(String unavailable) {
    return SmartFactoryMasterData.machines().stream()
        .map(m -> machine(m.id(), !m.id().equals(unavailable), 0, null, List.of())).toList();
  }

  private static OrderToPlan order(String id, String product, int qty, Priority prio, long due, long created) {
    List<OpToPlan> ops = CATALOG.routing(product).stream()
        .map(s -> new OpToPlan(s.seq(), s.process(), s.baseTimeS(), s.preferredMachineId(), null, null, null)).toList();
    return new OrderToPlan(id, product, qty, prio, due, created, ops);
  }

  private static OrderToPlan order(String id, String product, int qty) {
    return order(id, product, qty, Priority.NORMAL, Long.MAX_VALUE, 0);
  }

  private static Plan plan(DispatchRule rule, List<MachineState> machines, OrderToPlan... orders) {
    return Scheduler.plan(new PlanInput(0, rule, List.of(orders), machines));
  }

  private static PlannedOp op(Plan plan, String orderId, int seq) {
    return plan.ops().stream().filter(p -> p.orderId().equals(orderId) && p.seq() == seq).findFirst().orElseThrow();
  }

  @Test
  void stepsRunInOrderAndNeverOverlapOnAMachine() {
    var random = new SeededRandom(42);
    String[] products = {"AW50", "FP12", "GH200", "MT7"};
    List<OrderToPlan> orders = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      orders.add(order("PO-" + (1000 + i), products[random.nextInt(4)], 1 + random.nextInt(10),
          Priority.values()[random.nextInt(3)], random.nextInt(10_000_000), random.nextInt(1000)));
    }
    for (DispatchRule rule : DispatchRule.values()) {
      Plan plan = Scheduler.plan(new PlanInput(0, rule, orders, all()));
      assertThat(plan.unplannable()).isEmpty();
      assertThat(plan.ops()).hasSize(orders.stream().mapToInt(o -> o.ops().size()).sum());
      for (OrderToPlan o : orders) {
        for (int seq = 2; seq <= o.ops().size(); seq++) {
          assertThat(op(plan, o.orderId(), seq).startMs()).as(rule + " " + o.orderId())
              .isGreaterThanOrEqualTo(op(plan, o.orderId(), seq - 1).endMs());
        }
      }
      Map<String, List<PlannedOp>> byMachine = new TreeMap<>();
      for (PlannedOp p : plan.ops()) byMachine.computeIfAbsent(p.machineId(), k -> new ArrayList<>()).add(p);
      for (List<PlannedOp> list : byMachine.values()) {
        list.sort(Comparator.comparingLong(PlannedOp::startMs));
        for (int i = 1; i < list.size(); i++) {
          assertThat(list.get(i).startMs()).as(rule + " " + list.get(i)).isGreaterThanOrEqualTo(list.get(i - 1).endMs());
        }
      }
    }
  }

  @Test
  void setupOnlyWhenTheProductChanges() {
    var machines = List.of(machine("CNC-02", true, 0, "FP12", List.of()), machine("CNC-03"), machine("QC-01"));
    Plan plan = plan(DispatchRule.FIFO, machines,
        order("PO-1", "FP12", 1, Priority.NORMAL, Long.MAX_VALUE, 1),
        order("PO-2", "AW50", 1, Priority.NORMAL, Long.MAX_VALUE, 2),
        order("PO-3", "FP12", 1, Priority.NORMAL, Long.MAX_VALUE, 3));
    assertThat(op(plan, "PO-1", 1).setupMs()).as("CNC-02 ran FP12 last").isZero();
    assertThat(op(plan, "PO-2", 1).setupMs()).as("CNC-03 ran nothing yet").isEqualTo(30_000);
    assertThat(op(plan, "PO-2", 2).setupMs()).as("CNC-02 switches to AW50").isEqualTo(30_000);
    assertThat(op(plan, "PO-3", 1).setupMs()).as("CNC-02 switches back").isEqualTo(30_000);
    assertThat(op(plan, "PO-1", 2).setupMs()).as("QC never sets up").isZero();
    PlannedOp first = op(plan, "PO-1", 1);
    assertThat(first.endMs() - first.startMs()).isEqualTo(55_000);
  }

  @Test
  void maintenanceWindowIsSkipped() {
    var machines = List.of(machine("CNC-02", true, 0, "FP12", List.<long[]>of(new long[] {10_000, 100_000})),
        machine("QC-01"));
    Plan plan = plan(DispatchRule.FIFO, machines, order("PO-1", "FP12", 1), order("PO-2", "FP12", 1));
    assertThat(op(plan, "PO-1", 1).startMs()).isEqualTo(100_000);
    assertThat(op(plan, "PO-2", 1).startMs()).isEqualTo(155_000);
  }

  @Test
  void unavailableMachineIsNotUsedAndAlternativeIsChosen() {
    Plan plan = plan(DispatchRule.FIFO, allBut("CNC-01"), order("PO-1", "GH200", 5));
    assertThat(op(plan, "PO-1", 1).machineId()).isIn("CNC-02", "CNC-04");
    assertThat(plan.ops()).noneMatch(p -> p.machineId().equals("CNC-01"));
  }

  @Test
  void noAlternativeMakesTheOrderUnplannable() {
    Plan plan = plan(DispatchRule.FIFO, allBut("QC-01"), order("PO-1", "FP12", 1));
    assertThat(op(plan, "PO-1", 1).machineId()).isEqualTo("CNC-02");
    assertThat(plan.unplannable()).singleElement().satisfies(u -> {
      assertThat(u.orderId()).isEqualTo("PO-1");
      assertThat(u.seq()).isEqualTo(2);
      assertThat(u.reason()).contains("PRUEFEN");
    });
    assertThat(plan.lateOrders()).isEmpty();

    var noRobots = all().stream().map(m -> m.id().startsWith("ROB")
        ? machine(m.id(), false, 0, null, List.of()) : m).toList();
    Plan blocked = plan(DispatchRule.FIFO, noRobots, order("PO-2", "GH200", 1));
    assertThat(blocked.unplannable()).extracting(Plan.Unplannable::seq).containsExactly(2, 3);
    assertThat(blocked.unplannable().get(1).reason()).isEqualTo("Vorgänger nicht planbar");
  }

  @Test
  void urgentOrderIsPlannedFirstUnderPriorityButNotUnderFifo() {
    var machines = List.of(machine("CNC-02", true, 0, "FP12", List.of()), machine("QC-01"));
    OrderToPlan normal = order("PO-1", "FP12", 2, Priority.NORMAL, 10_000_000, 0);
    OrderToPlan urgent = order("PO-2", "FP12", 2, Priority.EILIG, 10_000_000, 10);
    Plan byPriority = plan(DispatchRule.PRIORITY, machines, normal, urgent);
    assertThat(op(byPriority, "PO-2", 1).startMs()).isLessThan(op(byPriority, "PO-1", 1).startMs());
    Plan byArrival = plan(DispatchRule.FIFO, machines, normal, urgent);
    assertThat(op(byArrival, "PO-1", 1).startMs()).isLessThan(op(byArrival, "PO-2", 1).startMs());
  }

  @Test
  void otherRulesSortByDueDateAndWork() {
    var machines = List.of(machine("CNC-02", true, 0, "FP12", List.of()), machine("QC-01"));
    OrderToPlan big = order("PO-1", "FP12", 9, Priority.NORMAL, 5_000_000, 0);
    OrderToPlan small = order("PO-2", "FP12", 1, Priority.NORMAL, 9_000_000, 1);
    assertThat(op(plan(DispatchRule.SPT, machines, big, small), "PO-2", 1).startMs()).isZero();
    assertThat(op(plan(DispatchRule.EDD, machines, big, small), "PO-1", 1).startMs()).isZero();
  }

  @Test
  void lateOrdersAreDetected() {
    Plan plan = plan(DispatchRule.FIFO, all(),
        order("PO-1", "FP12", 1, Priority.NORMAL, 1_000, 0),
        order("PO-2", "AW50", 1, Priority.NORMAL, 10_000_000, 0));
    assertThat(plan.lateOrders()).containsExactly("PO-1");
  }

  @Test
  void bottleneckIsTheBusiestMachine() {
    var machines = List.of(machine("CNC-02", true, 0, "FP12", List.of()), machine("QC-01"));
    Plan plan = plan(DispatchRule.FIFO, machines,
        order("PO-1", "FP12", 10), order("PO-2", "FP12", 10), order("PO-3", "FP12", 10));
    assertThat(plan.bottleneckMachineId()).isEqualTo("CNC-02");
    long horizon = plan.ops().stream().mapToLong(PlannedOp::endMs).max().orElseThrow();
    assertThat(plan.utilization()).containsOnlyKeys("CNC-02", "QC-01");
    assertThat(plan.utilization().get("CNC-02")).isEqualTo(3 * 550_000.0 / horizon);
    assertThat(plan.utilization().get("QC-01")).isEqualTo(3 * 180_000.0 / horizon);
  }

  @Test
  void emptyPlanHasNoBottleneckAndZeroUtilization() {
    Plan plan = plan(DispatchRule.FIFO, all());
    assertThat(plan.ops()).isEmpty();
    assertThat(plan.utilization().values()).containsOnly(0.0);
    assertThat(plan.bottleneckMachineId()).isNull();
  }

  @Test
  void preferredMachineWinsATie() {
    Plan plan = plan(DispatchRule.FIFO, all(), order("PO-1", "FP12", 1));
    assertThat(op(plan, "PO-1", 1).machineId()).as("CNC-01, -02, -04 equally free").isEqualTo("CNC-02");
    Plan other = plan(DispatchRule.FIFO, all(), order("PO-1", "MT7", 1));
    assertThat(op(other, "PO-1", 1).machineId()).isEqualTo("CNC-04");
  }

  @Test
  void runningAndFinishedOperationsAreRespected() {
    var ops = List.of(
        new OpToPlan(1, io.nexus.contracts.Domain.ProcessType.FRAESEN, 85, "CNC-01", "CNC-01", null, 5_000L),
        new OpToPlan(2, io.nexus.contracts.Domain.ProcessType.MONTAGE, 65, "ROB-01", "ROB-01", 90_000L, null),
        new OpToPlan(3, io.nexus.contracts.Domain.ProcessType.PRUEFEN, 28, "QC-01", null, null, null));
    var running = new OrderToPlan("PO-1", "GH200", 1, Priority.NORMAL, Long.MAX_VALUE, 0, ops);
    var machines = all().stream().map(m -> switch (m.id()) {
      case "ROB-01" -> machine("ROB-01", true, 90_000, "GH200", List.of());
      case "QC-01" -> machine("QC-01", true, 120_000, null, List.of());
      default -> m;
    }).toList();
    Plan plan = Scheduler.plan(new PlanInput(1_000, DispatchRule.FIFO, List.of(running), machines));
    assertThat(plan.ops()).singleElement().satisfies(p -> {
      assertThat(p.seq()).isEqualTo(3);
      assertThat(p.startMs()).isEqualTo(120_000);
    });
  }

  @Test
  void fixedMachineIsKept() {
    var ops = List.of(
        new OpToPlan(1, io.nexus.contracts.Domain.ProcessType.FRAESEN, 55, "CNC-02", "CNC-04", null, null),
        new OpToPlan(2, io.nexus.contracts.Domain.ProcessType.PRUEFEN, 18, "QC-01", null, null, null));
    var o = new OrderToPlan("PO-1", "FP12", 1, Priority.NORMAL, Long.MAX_VALUE, 0, ops);
    Plan plan = Scheduler.plan(new PlanInput(0, DispatchRule.FIFO, List.of(o), all()));
    assertThat(op(plan, "PO-1", 1).machineId()).isEqualTo("CNC-04");
  }

  @Test
  void sameInputSamePlan() {
    var input = new PlanInput(0, DispatchRule.PRIORITY, List.of(order("PO-1", "GH200", 3), order("PO-2", "MT7", 4),
        order("PO-3", "AW50", 2, Priority.EILIG, 100, 5)), all());
    assertThat(Scheduler.plan(input)).isEqualTo(Scheduler.plan(input));
  }
}
