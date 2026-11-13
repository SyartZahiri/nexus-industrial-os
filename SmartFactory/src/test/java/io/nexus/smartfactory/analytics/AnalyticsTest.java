package io.nexus.smartfactory.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.ProcessType;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.smartfactory.catalog.Catalog;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.ProductionOrder;
import io.nexus.smartfactory.store.memory.MemoryMachineStore;
import io.nexus.smartfactory.store.memory.MemoryOrderStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyticsTest {
  private static final long MIN = 60_000;
  private static final long HOUR = 60 * MIN;
  private final MemoryUnitOfWork work = new MemoryUnitOfWork();
  private final MemoryOrderStore orders = new MemoryOrderStore(work);
  private final MemoryMachineStore machines = new MemoryMachineStore(work);
  private final Analytics analytics = new Analytics(orders, machines, new Catalog());

  private void completed(String id, long created, long completed, long due, int good, int scrap, List<Operation> ops) {
    orders.insert(new ProductionOrder(id, "GH200", good + scrap, Priority.NORMAL, OrderStatus.COMPLETED, due, created,
        completed, good, scrap, 0), ops);
  }

  private static Operation done(String orderId, int seq, ProcessType process, String machine, long ended, int good, int scrap) {
    return new Operation(orderId + "/" + seq, orderId, seq, process, machine, Operation.Status.DONE, null, null,
        ended - MIN, ended, good, scrap);
  }

  /** CNC-01 in the first hour: idle 10 min, running 30, fault 5, repair 5, idle again. */
  private void cnc01History() {
    machines.changeState("CNC-01", MachineStatus.IDLE, 0);
    machines.changeState("CNC-01", MachineStatus.RUNNING, 10 * MIN);
    machines.changeState("CNC-01", MachineStatus.FAULT, 40 * MIN);
    machines.changeState("CNC-01", MachineStatus.REPAIR, 45 * MIN);
    machines.changeState("CNC-01", MachineStatus.IDLE, 50 * MIN);
  }

  private void twoCompletedOrders() {
    // GH200 step 1 is milling, 85 s per part: 10 parts on CNC-01, 9 good
    completed("PO-0001", 0, 30 * MIN, 35 * MIN, 9, 1, List.of(done("PO-0001", 1, ProcessType.FRAESEN, "CNC-01", 40 * MIN, 9, 1)));
    completed("PO-0002", 10 * MIN, 50 * MIN, 35 * MIN, 5, 0, List.of());
    completed("PO-0003", 0, 2 * HOUR, 3 * HOUR, 7, 0, List.of()); // outside the window
    orders.insert(new ProductionOrder("PO-0004", "GH200", 3, Priority.NORMAL, OrderStatus.IN_PROGRESS, HOUR, 0, null,
        0, 0, 0), List.of());
  }

  @Test
  void throughputCountsGoodPartsOfOrdersCompletedInTheWindow() {
    twoCompletedOrders();
    assertThat(analytics.compute(0, HOUR).throughputPerHour()).isEqualTo(14.0);
    assertThat(analytics.compute(0, 2 * HOUR).throughputPerHour()).isEqualTo(10.5);
  }

  @Test
  void leadTimeIsAveragedAndP90IsTheNearestRank() {
    twoCompletedOrders();
    Kpis k = analytics.compute(0, HOUR);
    assertThat(k.avgLeadTimeS()).isEqualTo(2100.0); // 1800 s and 2400 s
    assertThat(k.p90LeadTimeS()).isEqualTo(2400.0);

    var many = new MemoryOrderStore(work);
    for (int i = 1; i <= 10; i++) {
      many.insert(new ProductionOrder("PO-" + (100 + i), "FP12", 1, Priority.NORMAL, OrderStatus.COMPLETED, HOUR, 0,
          i * 1000L, 1, 0, 0), List.of());
    }
    Kpis ten = new Analytics(many, machines, new Catalog()).compute(0, HOUR);
    assertThat(ten.p90LeadTimeS()).isEqualTo(9.0);
    assertThat(ten.avgLeadTimeS()).isEqualTo(5.5);
  }

  @Test
  void onTimeRateIsTheShareCompletedByTheirDueDate() {
    twoCompletedOrders();
    assertThat(analytics.compute(0, HOUR).onTimeRate()).isEqualTo(0.5);
  }

  @Test
  void timeInStatusAndUtilizationComeFromTheIntervals() {
    cnc01History();
    Kpis.MachineKpi cnc = analytics.compute(0, HOUR).machines().get("CNC-01");
    assertThat(cnc.timeInStatusMs()).containsEntry(MachineStatus.IDLE, 20 * MIN).containsEntry(MachineStatus.RUNNING, 30 * MIN)
        .containsEntry(MachineStatus.FAULT, 5 * MIN).containsEntry(MachineStatus.REPAIR, 5 * MIN)
        .containsEntry(MachineStatus.MAINTENANCE, 0L);
    assertThat(cnc.timeInStatusMs().keySet()).containsExactly(MachineStatus.values());
    assertThat(cnc.utilization()).isEqualTo(0.5);
  }

  @Test
  void availabilitySubtractsFaultRepairAndMaintenance() {
    cnc01History();
    assertThat(analytics.compute(0, HOUR).machines().get("CNC-01").availability()).isEqualTo(50.0 / 60);
  }

  @Test
  void performanceIsPlannedWorkOfFinishedOperationsOverRunningTime() {
    cnc01History();
    twoCompletedOrders();
    // 10 parts × 85 s = 850 s in 1800 s running
    assertThat(analytics.compute(0, HOUR).machines().get("CNC-01").performance()).isCloseTo(850.0 / 1800, within(1e-12));
  }

  @Test
  void performanceIsCappedAtOne() {
    machines.changeState("CNC-01", MachineStatus.RUNNING, 0);
    machines.changeState("CNC-01", MachineStatus.IDLE, MIN);
    twoCompletedOrders();
    assertThat(analytics.compute(0, HOUR).machines().get("CNC-01").performance()).isEqualTo(1.0);
  }

  @Test
  void qualityIsGoodOverAllPartsOfFinishedOperations() {
    twoCompletedOrders();
    assertThat(analytics.compute(0, HOUR).machines().get("CNC-01").quality()).isEqualTo(0.9);
    assertThat(analytics.compute(0, HOUR).machines().get("CNC-02").quality()).isEqualTo(1.0);
  }

  @Test
  void oeeIsTheProductOfItsFactors() {
    cnc01History();
    twoCompletedOrders();
    Kpis.MachineKpi cnc = analytics.compute(0, HOUR).machines().get("CNC-01");
    assertThat(cnc.oee()).isEqualTo(cnc.availability() * cnc.performance() * cnc.quality());
    assertThat(cnc.oee()).isCloseTo(50.0 / 60 * 850.0 / 1800 * 0.9, within(1e-12));
  }

  @Test
  void bottleneckIsTheMostUtilisedMachine() {
    cnc01History();
    machines.changeState("QC-01", MachineStatus.RUNNING, 0);
    machines.changeState("QC-01", MachineStatus.IDLE, 20 * MIN);
    machines.changeState("CNC-02", MachineStatus.RUNNING, 0);
    machines.changeState("CNC-02", MachineStatus.IDLE, 30 * MIN);
    Kpis k = analytics.compute(0, HOUR);
    assertThat(k.bottleneckMachineId()).as("tie with CNC-02, lower id wins").isEqualTo("CNC-01");
    assertThat(k.machines().keySet()).containsExactly("CNC-01", "CNC-02", "CNC-03", "CNC-04", "QC-01", "ROB-01", "ROB-02");
  }

  @Test
  void intervalsAcrossTheWindowBordersAreClipped() {
    cnc01History();
    Kpis.MachineKpi cnc = analytics.compute(20 * MIN, 30 * MIN).machines().get("CNC-01");
    assertThat(cnc.utilization()).isEqualTo(1.0);
    assertThat(cnc.timeInStatusMs()).containsEntry(MachineStatus.RUNNING, 10 * MIN).containsEntry(MachineStatus.IDLE, 0L);
    Kpis.MachineKpi late = analytics.compute(55 * MIN, 2 * HOUR).machines().get("CNC-01");
    assertThat(late.timeInStatusMs()).as("the open interval ends at the window").containsEntry(MachineStatus.IDLE, 65 * MIN);
  }

  @Test
  void emptyWindowGivesZeroOrOneWithoutDividingByZero() {
    for (long[] window : new long[][] {{0, HOUR}, {5, 5}}) {
      Kpis k = analytics.compute(window[0], window[1]);
      assertThat(k.throughputPerHour()).isZero();
      assertThat(k.avgLeadTimeS()).isZero();
      assertThat(k.p90LeadTimeS()).isZero();
      assertThat(k.onTimeRate()).isEqualTo(1.0);
      assertThat(k.bottleneckMachineId()).isNull();
      assertThat(k.machines().values()).allSatisfy(m -> {
        assertThat(m.utilization()).isZero();
        assertThat(m.availability()).isEqualTo(1.0);
        assertThat(m.performance()).isZero();
        assertThat(m.quality()).isEqualTo(1.0);
        assertThat(m.oee()).isZero();
      });
    }
  }
}
