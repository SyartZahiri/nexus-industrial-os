package io.nexus.plant;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.plant.machines.LocalMachines;
import io.nexus.plant.machines.MachineGateway;
import io.nexus.smartfactory.SmartFactory;
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
  /** One call advances at most one simulated day; larger requests are refused instead of freezing the caller. */
  public static final long MAX_ADVANCE_MS = 86_400_000;

  private final EventBus bus;
  private final SimTime time;
  private final FlowPilot flowPilot;
  private final SmartFactory smartFactory;
  private final MachineGateway machines;
  private OrderGenerator orders;
  private final Shipping shipping = new Shipping();
  private final MemoryEventLog memoryLog;
  private PlantRules rules;
  private final Scenarios scenarios;

  /** Everything in memory: the browser runtime and core tests. */
  public static Plant inMemory(long seed, PlantRules rules) {
    return inMemory(seed, rules, 1);
  }

  /** @param runId run number in the event ids, so ids of a restarted plant never repeat those of the last one */
  public static Plant inMemory(long seed, PlantRules rules, long runId) {
    var work = new MemoryUnitOfWork();
    var log = new MemoryEventLog(work);
    var time = new SimTime();
    var eventIds = new EventIds(runId);
    var bus = new EventBus(log, work, time, () -> eventIds);
    var ids = new MemoryIdSource(work);
    var flowPilot = FlowPilot.inMemory(bus, work, time, ids);
    MachineGateway[] machines = new MachineGateway[1];
    var smartFactory = new SmartFactory(new MemoryOrderStore(work), new MemoryMachineStore(work), bus, work, time, ids,
        flowPilot::materials, flowPilot::transports, () -> machines[0]);
    machines[0] = new LocalMachines(smartFactory);
    return new Plant(bus, time, seed, flowPilot, smartFactory, machines[0], rules, log);
  }

  /** With existing facades: the server runtime passes its JDBC-backed modules and its machines (OPC UA). */
  public Plant(EventBus bus, SimTime time, long seed, FlowPilot flowPilot, SmartFactory smartFactory,
               MachineGateway machines, PlantRules rules) {
    this(bus, time, seed, flowPilot, smartFactory, machines, rules, null);
  }

  private Plant(EventBus bus, SimTime time, long seed, FlowPilot flowPilot, SmartFactory smartFactory,
                MachineGateway machines, PlantRules rules, MemoryEventLog memoryLog) {
    this.bus = bus;
    this.time = time;
    this.flowPilot = flowPilot;
    this.smartFactory = smartFactory;
    this.machines = machines;
    this.orders = new OrderGenerator(new SeededRandom(seed).fork("orders"));
    this.memoryLog = memoryLog;
    this.scenarios = new Scenarios(smartFactory, flowPilot, bus);
    machines.reset(seed);
    applyRules(rules);
  }

  /** Runs whole steps of STEP_MS until at least simMs have passed (0 to MAX_ADVANCE_MS); returns the steps. */
  public int advance(long simMs) {
    if (simMs < 0 || simMs > MAX_ADVANCE_MS) {
      throw DomainException.invalid("Simulationszeit je Aufruf zwischen 0 und " + MAX_ADVANCE_MS + " ms");
    }
    int steps = (int) ((simMs + STEP_MS - 1) / STEP_MS);
    for (int i = 0; i < steps; i++) step();
    return steps;
  }

  /**
   * One step. Each phase runs on its own: if one fails, it is reported as an error decision and the others still
   * run, so the clock, the machines and the fleet never stop because of one fault in the code or the database.
   */
  private void step() {
    synchronized (bus) {
      time.advance(STEP_MS);
      long now = time.nowMs();
      phase("Aufträge", () -> orders.step(now, rules, smartFactory));
      phase("Versand", () -> shipping.step(now, rules, flowPilot));
      phase("Maschinen", () -> machines.step(STEP_MS));
      phase("FlowPilot", () -> flowPilot.step(STEP_MS));
      phase("Szenarien", () -> scenarios.step(now));
      for (int i = 0; i < extraPhases.size(); i++) phase(extraPhaseNames.get(i), extraPhases.get(i));
      phase("Zustellung", bus::retryFailed);
    }
  }

  private final List<String> extraPhaseNames = new ArrayList<>();
  private final List<Runnable> extraPhases = new ArrayList<>();

  /** Tests only: a phase that runs after FlowPilot in every step. */
  void addPhaseForTest(String name, Runnable phase) {
    extraPhaseNames.add(name);
    extraPhases.add(phase);
  }

  private void phase(String name, Runnable work) {
    try {
      work.run();
    } catch (RuntimeException | StackOverflowError e) {
      String type = e.getClass().getName();
      String reason = "Phase " + name + " fehlgeschlagen: " + type.substring(type.lastIndexOf('.') + 1);
      try {
        bus.publish(EventTypes.DECISION, "plant", Severity.ERROR, null,
            new Payloads.Decision("Simulationsschritt", name, List.of(), reason));
      } catch (RuntimeException reportFailed) {
        // nothing left to tell; the next step tries again
      }
    }
  }

  /**
   * Starts the plant afresh with a new seed: new machines (cold, unworn), new order generator, the current rules
   * applied as at construction. The caller has already reset stores, clock, id counters, event run, bus, FlowPilot
   * and SmartFactory; afterwards the plant behaves exactly like a new one built with this seed.
   */
  public void reset(long seed) {
    synchronized (bus) {
      scenarios.reset();
      machines.reset(seed);
      orders = new OrderGenerator(new SeededRandom(seed).fork("orders"));
      applyRules(rules);
    }
  }

  /** Starts scenario id (A to E) on the running plant; @return the run id. */
  public String startScenario(String id) {
    synchronized (bus) {
      return scenarios.start(id, time.nowMs());
    }
  }

  /** Tests only: the scenario runner of this plant. */
  Scenarios scenarios() {
    return scenarios;
  }

  /** The scenarios and their last runs as JSON (PlantQueries "scenarios"). */
  public String scenariosJson() {
    synchronized (bus) {
      return scenarios.json();
    }
  }

  public void setRules(PlantRules rules) {
    synchronized (bus) {
      applyRules(rules);
    }
  }

  private void applyRules(PlantRules newRules) {
    machines.setRules(newRules.machines());
    // only on a change: a new plant on default rules commands nothing while it is being built (server start)
    if (smartFactory.dispatchRule() != newRules.dispatchRule()) smartFactory.setDispatchRule(newRules.dispatchRule());
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
      machines.configure(machineId, overridePercent, coolant);
    }
  }

  public FlowPilot flowPilot() {
    return flowPilot;
  }

  public SmartFactory smartFactory() {
    return smartFactory;
  }

  /** The machines. Commands go through SmartFactory or the plant; tests read views() here. */
  public MachineGateway machines() {
    return machines;
  }

  public long nowMs() {
    synchronized (bus) {
      return time.nowMs();
    }
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
      // every list sorted here in Java: a database may order text differently (collation), the frame must not
      List<PlantFrame.StockView> stock = new ArrayList<>();
      for (StockLine l : flowPilot.stock()) {
        stock.add(new PlantFrame.StockView(l.materialId(), l.locationId(), l.onHand(), l.reserved()));
      }
      stock.sort((a, b) -> a.materialId().equals(b.materialId())
          ? a.locationId().compareTo(b.locationId()) : a.materialId().compareTo(b.materialId()));
      // open orders and the 20 last finished ones, without loading every order the plant ever had
      List<ProductionOrder> shown = new ArrayList<>(smartFactory.openOrders());
      shown.addAll(smartFactory.lastFinished(20));
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
      List<io.nexus.sim.Simulation.MachineView> machineViews = new ArrayList<>(machines.views());
      machineViews.sort((a, b) -> a.id().compareTo(b.id()));
      List<FlowPilot.VehicleView> vehicles = new ArrayList<>(flowPilot.vehicles());
      vehicles.sort((a, b) -> a.id().compareTo(b.id()));
      return new PlantFrame(time.nowMs(), machineViews, machines.conveyors(), vehicles, stock, orderViews, transports);
    }
  }

}
