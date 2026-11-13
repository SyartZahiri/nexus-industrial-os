package io.nexus.smartfactory;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.smartfactory.analytics.Kpis;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.master.SmartFactoryMasterData.Product;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.planning.Plan;
import io.nexus.smartfactory.testkit.FakeMachineControl;
import io.nexus.smartfactory.testkit.FakeMaterialService;
import io.nexus.smartfactory.testkit.FakeTransportService;
import io.nexus.smartfactory.testkit.FakeTransportService.FakeTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Self-test of the module for the browser build: platform/web runs it on the JVM and, compiled by TeaVM,
 * in Node, and requires identical output. One order per product runs from creation to stock against the
 * testkit fakes, with a fault at CNC-01 on the way; doubles are printed as bit patterns.
 */
public final class SmartFactorySelfTest {
  private SmartFactorySelfTest() {}

  private static final long HOUR = 3_600_000;

  public static String run(long seed) {
    var random = new SeededRandom(seed).fork("smartfactory");
    var work = new MemoryUnitOfWork();
    var log = new MemoryEventLog(work);
    var time = new SimTime();
    var eventIds = new EventIds(1);
    var bus = new EventBus(log, work, time, () -> eventIds);
    var transports = new FakeTransportService(bus);
    var machines = new FakeMachineControl();
    var factory = SmartFactory.inMemory(bus, work, time, new MemoryIdSource(work), new FakeMaterialService(),
        transports, machines);

    List<String> orderIds = new ArrayList<>();
    for (Product p : SmartFactoryMasterData.products()) {
      orderIds.add(factory.createOrder(new NewOrder(p.id(), 1 + random.nextInt(10), Priority.NORMAL, 8 * HOUR)));
    }
    factory.faultRaised("CNC-01", "SERVO", "Achsantrieb gestört (Servo)", false);
    factory.statusChanged("CNC-01", MachineStatus.IDLE, MachineStatus.FAULT);
    Plan plan = factory.plan();

    int finished = 0;
    boolean repaired = false;
    boolean moved = true;
    while (moved) {
      moved = false;
      for (FakeTransport t : transports.open()) {
        time.advance(20_000 + random.nextInt(40_000));
        transports.deliver(t.id());
        moved = true;
      }
      if (!repaired) {
        time.advance(120_000);
        factory.statusChanged("CNC-01", MachineStatus.FAULT, MachineStatus.REPAIR);
        time.advance(300_000);
        factory.statusChanged("CNC-01", MachineStatus.REPAIR, MachineStatus.IDLE);
        repaired = true;
      }
      List<MachineJob> jobs = machines.jobs();
      while (finished < jobs.size()) {
        runJob(factory, time, random, jobs.get(finished++));
        moved = true;
      }
    }

    List<String> lines = new ArrayList<>();
    for (String id : orderIds) {
      var o = factory.order(id);
      var statuses = new StringBuilder("CREATED");
      for (NexusEvent e : log.all()) {
        if (e.payload() instanceof Payloads.OrderStatusChanged c && c.orderId().equals(id)) statuses.append('>').append(c.to());
      }
      lines.add(id + " " + o.productId() + "x" + o.quantity() + " good=" + o.good() + " scrap=" + o.scrapped() + ": "
          + statuses);
    }
    var jobs = new StringBuilder("jobs:");
    for (MachineJob j : machines.jobs()) {
      jobs.append(' ').append(j.machineId()).append(':').append(j.orderId()).append('/').append(j.stepSeq())
          .append('x').append(j.quantity()).append('+').append(j.setupMs());
    }
    lines.add(jobs.toString());
    var planText = new StringBuilder("plan:");
    for (Plan.PlannedOp p : plan.ops()) {
      planText.append(' ').append(p.orderId()).append('/').append(p.seq()).append('@').append(p.machineId())
          .append(':').append(p.startMs()).append('-').append(p.endMs());
    }
    lines.add(planText.append(" bottleneck=").append(plan.bottleneckMachineId()).toString());
    int rescheduled = 0;
    for (NexusEvent e : log.all()) if (e.type().equals(EventTypes.ORDER_RESCHEDULED)) rescheduled++;
    lines.add("rescheduled=" + rescheduled);
    Kpis k = factory.kpis(0, time.nowMs());
    var kpis = new StringBuilder("kpis: throughput=").append(bits(k.throughputPerHour()))
        .append(" lead=").append(bits(k.avgLeadTimeS())).append(" p90=").append(bits(k.p90LeadTimeS()))
        .append(" onTime=").append(bits(k.onTimeRate())).append(" bottleneck=").append(k.bottleneckMachineId());
    for (Map.Entry<String, Kpis.MachineKpi> m : k.machines().entrySet()) {
      kpis.append(' ').append(m.getKey()).append('=').append(bits(m.getValue().utilization())).append('/')
          .append(bits(m.getValue().oee()));
    }
    lines.add(kpis.toString());
    lines.add("events=" + log.all().size());
    return String.join("\n", lines);
  }

  /** Plays the machine: runs the batch, sometimes scraps one part, and reports back. */
  private static void runJob(SmartFactory factory, SimTime time, SeededRandom random, MachineJob job) {
    factory.statusChanged(job.machineId(), MachineStatus.IDLE, MachineStatus.RUNNING);
    time.advance(job.setupMs() + job.processMs());
    int scrap = job.quantity() > 1 && random.nextInt(4) == 0 ? 1 : 0;
    int good = job.quantity() - scrap;
    factory.jobFinished(job.machineId(), job.orderId(), job.stepSeq(), good, scrap);
    if (SmartFactoryMasterData.machine(job.machineId()).outputToConveyor() && good > 0) {
      time.advance(30_000);
      factory.conveyorDelivered("QC-01", job.orderId(), job.stepSeq() + 1, good);
    }
    factory.statusChanged(job.machineId(), MachineStatus.RUNNING, MachineStatus.IDLE);
  }

  private static String bits(double value) {
    return Long.toHexString(Double.doubleToLongBits(value));
  }
}
