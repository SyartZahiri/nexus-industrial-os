package io.nexus.plant;

import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MachineControl;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.sim.Simulation;
import io.nexus.smartfactory.SmartFactory;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.ProductionOrder;
import io.nexus.smartfactory.store.memory.MemoryMachineStore;
import io.nexus.smartfactory.store.memory.MemoryOrderStore;
import java.util.ArrayList;
import java.util.List;

/**
 * The running plant: machines, FlowPilot and SmartFactory with one clock and one fixed step. Server and browser
 * use this class unchanged; only the stores behind FlowPilot and SmartFactory differ.
 *
 * <p>Each step, in this order (Spec 11): clock, automatic orders, shipping, machines, FlowPilot, retry of failed
 * event deliveries. A step holds the event bus's monitor, the platform-wide lock every facade uses, so commands
 * from other threads land between steps, never inside one.
 */
public final class Plant {
  public static final long STEP_MS = 100;

  /** Drop and take positions on the collecting conveyor CV-03, from FactoryTwin/src/sim/initialState.ts. */
  private static final double CV03_LENGTH_M = 27;
  private static final double CV03_SPEED_MPS = 0.6;

  private final EventBus bus;
  private final SimTime time;
  private final FlowPilot flowPilot;
  private final SmartFactory smartFactory;
  private final Simulation simulation;
  private final OrderGenerator orders;
  private final Shipping shipping = new Shipping();
  private final MemoryEventLog memoryLog;
  private PlantRules rules;

  /** Everything in memory: the browser runtime and core tests. */
  public static Plant inMemory(long seed, PlantRules rules) {
    var work = new MemoryUnitOfWork();
    var log = new MemoryEventLog(work);
    var time = new SimTime();
    var eventIds = new EventIds(1);
    var bus = new EventBus(log, work, time, () -> eventIds);
    var ids = new MemoryIdSource(work);
    var random = new SeededRandom(seed);
    var flowPilot = FlowPilot.inMemory(bus, work, time, ids);
    Simulation[] simulation = new Simulation[1];
    var smartFactory = new SmartFactory(new MemoryOrderStore(work), new MemoryMachineStore(work), bus, work, time, ids,
        flowPilot::materials, flowPilot::transports, () -> simulation[0]);
    simulation[0] = simulationFor(random, smartFactory);
    return new Plant(bus, time, random, flowPilot, smartFactory, simulation[0], rules, log);
  }

  /** The machines of the plant, reporting to SmartFactory. The server builds its simulation with this too. */
  public static Simulation simulationFor(SeededRandom random, SmartFactory smartFactory) {
    List<Simulation.MachineSpec> specs = new ArrayList<>();
    for (SmartFactoryMasterData.Machine m : SmartFactoryMasterData.machines()) {
      specs.add(new Simulation.MachineSpec(m.id(), m.kind()));
    }
    List<Simulation.ConveyorLink> links = new ArrayList<>();
    for (SmartFactoryMasterData.ConveyorLink l : SmartFactoryMasterData.conveyorLinks()) {
      links.add(new Simulation.ConveyorLink(l.fromMachineId(), l.conveyorId(), l.toMachineId(), dropAt(l.fromMachineId()),
          CV03_LENGTH_M, CV03_SPEED_MPS));
    }
    return new Simulation(specs, links, random.fork("sim"), smartFactory);
  }

  private static double dropAt(String machineId) {
    return switch (machineId) {
      case "ROB-01" -> 6.7;
      case "ROB-02" -> 15.7;
      default -> throw new IllegalStateException("Keine Ablageposition auf CV-03 für " + machineId);
    };
  }

  /** With existing facades: the server runtime passes its JDBC-backed modules and its simulation. */
  public Plant(EventBus bus, SimTime time, SeededRandom random, FlowPilot flowPilot, SmartFactory smartFactory,
               Simulation simulation, PlantRules rules) {
    this(bus, time, random, flowPilot, smartFactory, simulation, rules, null);
  }

  private Plant(EventBus bus, SimTime time, SeededRandom random, FlowPilot flowPilot, SmartFactory smartFactory,
                Simulation simulation, PlantRules rules, MemoryEventLog memoryLog) {
    this.bus = bus;
    this.time = time;
    this.flowPilot = flowPilot;
    this.smartFactory = smartFactory;
    this.simulation = simulation;
    this.orders = new OrderGenerator(random.fork("orders"));
    this.memoryLog = memoryLog;
    applyRules(rules);
  }

  /** Runs whole steps of STEP_MS until at least simMs have passed; returns the number of steps. */
  public int advance(long simMs) {
    int steps = (int) ((simMs + STEP_MS - 1) / STEP_MS);
    for (int i = 0; i < steps; i++) step();
    return steps;
  }

  private void step() {
    synchronized (bus) {
      time.advance(STEP_MS);
      long now = time.nowMs();
      orders.step(now, rules, smartFactory);
      shipping.step(now, rules, flowPilot);
      simulation.step(STEP_MS);
      flowPilot.step(STEP_MS);
      bus.retryFailed();
    }
  }

  public void setRules(PlantRules rules) {
    synchronized (bus) {
      applyRules(rules);
    }
  }

  private void applyRules(PlantRules newRules) {
    simulation.setRules(newRules.machines());
    if (rules == null || rules.dispatchRule() != newRules.dispatchRule()) smartFactory.setDispatchRule(newRules.dispatchRule());
    rules = newRules;
  }

  public PlantRules rules() {
    synchronized (bus) {
      return rules;
    }
  }

  /** Override and coolant of a machine; only the simulation knows them. */
  public void configureMachine(String machineId, int overridePercent, boolean coolant) {
    synchronized (bus) {
      simulation.configure(machineId, overridePercent, coolant);
    }
  }

  public FlowPilot flowPilot() {
    return flowPilot;
  }

  public SmartFactory smartFactory() {
    return smartFactory;
  }

  /** The machines. Use the plant's methods or SmartFactory's commands to change them, never this from outside a step. */
  public Simulation simulation() {
    return simulation;
  }

  public MachineControl machineControl() {
    return simulation;
  }

  public long nowMs() {
    return time.nowMs();
  }

  /** Every event of the run, in publish order. Only for the in-memory plant; the server reads its event table. */
  public List<NexusEvent> events() {
    if (memoryLog == null) throw new IllegalStateException("Ereignisse liegen auf dem Server in der Datenbank");
    synchronized (bus) {
      return memoryLog.all();
    }
  }

  public PlantFrame frame() {
    synchronized (bus) {
      List<PlantFrame.StockView> stock = new ArrayList<>();
      for (StockLine l : flowPilot.stock()) {
        stock.add(new PlantFrame.StockView(l.materialId(), l.locationId(), l.onHand(), l.reserved()));
      }
      List<ProductionOrder> all = smartFactory.orders();
      List<ProductionOrder> shown = new ArrayList<>();
      List<ProductionOrder> finished = new ArrayList<>();
      for (ProductionOrder o : all) {
        if (o.status().terminal()) finished.add(o);
        else shown.add(o);
      }
      shown.addAll(finished.subList(Math.max(0, finished.size() - 20), finished.size()));
      shown.sort((a, b) -> a.id().compareTo(b.id()));
      List<PlantFrame.OrderView> orderViews = new ArrayList<>();
      for (ProductionOrder o : shown) {
        List<Operation> ops = smartFactory.operations(o.id());
        int done = 0;
        for (Operation op : ops) if (op.status() == Operation.Status.DONE) done++;
        orderViews.add(new PlantFrame.OrderView(o.id(), o.productId(), o.quantity(), o.priority().name(),
            o.status().name(), o.dueAtMs(), o.good(), o.scrapped(), done, ops.size()));
      }
      List<TransportOrder> open = new ArrayList<>(flowPilot.openTransports());
      open.sort((a, b) -> a.id().compareTo(b.id()));
      List<PlantFrame.TransportView> transports = new ArrayList<>();
      for (TransportOrder t : open) {
        transports.add(new PlantFrame.TransportView(t.id(), t.kind().name(), t.orderId(), t.materialId(), t.quantity(),
            t.status().name(), t.agvId(), t.from().kind() + ":" + t.from().id(), t.to().kind() + ":" + t.to().id()));
      }
      return new PlantFrame(time.nowMs(), simulation.machines(), simulation.conveyors(), flowPilot.vehicles(), stock,
          orderViews, transports);
    }
  }

}
