package io.nexus.plant;

import io.nexus.contracts.Domain.MachineKind;
import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.json.JsonWriter;
import io.nexus.contracts.port.ProductCatalog.MaterialDemand;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.contracts.state.TourStatus;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.contracts.state.VehicleStatus;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.routemind.RouteMind;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.Tour;
import io.nexus.smartfactory.SmartFactory;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.planning.Plan;
import io.nexus.smartfactory.store.MachineRecord;
import io.nexus.smartfactory.store.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Scenario Lab (Spec 15): scripted interventions on the running plant. A run acts once and then looks at the plant
 * every simulated second until the expected behaviour shows (PASSED) or its time limit passes (FAILED, with the
 * reason). One run at a time; a reset forgets all runs. Runs inside the plant step, so both runtimes behave alike.
 */
final class Scenarios {
  static final long MIN = 60_000;
  private static final long CHECK_EVERY_MS = 1_000;
  private static final int KEEP = 10;

  enum State { WAITING, RUNNING, PASSED, FAILED }

  record Definition(String id, String title, String trigger, String expected, long limitMs) {}

  record Step(long atMs, String text) {}

  static final List<Definition> DEFINITIONS = List.of(
      new Definition("A", "Maschinenausfall", "Störung an einer laufenden CNC",
          "Umplanung auf eine Ausweichmaschine", 30 * MIN),
      new Definition("B", "AGV-Ausfall", "Störung an einem beladenen AGV",
          "Transport fällt aus, ein Ersatz-AGV übernimmt die Ladung", 30 * MIN),
      new Definition("C", "Materialengpass", "Bestand gesenkt, dann ein Auftrag über dem Bestand",
          "Keine Reservierung, der Auftrag wartet in SHORTAGE auf den Wareneingang", 30 * MIN),
      new Definition("D", "Expressauftrag", "Auftrag mit Priorität eilig",
          "Neuplanung, verschobene Aufträge mit ihrer Verspätung", 5 * MIN),
      new Definition("E", "Kompletter Durchlauf", "Neuer Auftrag",
          "Reservierung bis Einlagerung, als Schrittfolge sichtbar", 6 * 60 * MIN),
      new Definition("F", "Produktionsverzögerung",
          "Die letzte Maschine eines Auftrags mit geplanter Lieferung steht, bis die Tour abfährt",
          "Die Tour fährt nicht mit der unfertigen Ware: Neuplanung oder Abfahrt ohne sie", 6 * 60 * MIN),
      new Definition("G", "Ausfall eines Lieferfahrzeugs", "Panne eines beladenen Lieferfahrzeugs unterwegs",
          "Tour unterbrochen, offene Lieferungen in einer neuen Tour geplant", 6 * 60 * MIN),
      new Definition("H", "Expressauftrag bis zur Lieferplanung", "Eilauftrag",
          "Lieferung in einer der nächsten Wellen geplant", 3 * 60 * MIN),
      new Definition("I", "Materialengpass und Liefertermin", "Auftrag über Bestand",
          "Auftrag in SHORTAGE, die Lieferung bekommt keinen Termin", 2 * 60 * MIN),
      new Definition("J", "Vollständige Lieferkette", "Neuer Auftrag",
          "Reservierung, Fertigung, Einlagerung, Verladung, Zustellung als Schrittfolge", 10 * 60 * MIN));

  private final SmartFactory sf;
  private final FlowPilot fp;
  private final RouteMind rm;
  private final EventBus bus;
  private final List<Run> runs = new ArrayList<>(); // newest first
  private int counter;

  Scenarios(SmartFactory sf, FlowPilot fp, RouteMind rm, EventBus bus) {
    this.sf = sf;
    this.fp = fp;
    this.rm = rm;
    this.bus = bus;
  }

  String start(String scenarioId, long now) {
    Definition def = null;
    for (Definition d : DEFINITIONS) if (d.id().equals(scenarioId)) def = d;
    if (def == null) throw DomainException.notFound("Szenario", scenarioId); // unknown stays unknown, also while one runs
    Run active = active();
    if (active != null) {
      throw DomainException.conflict("Szenario " + active.def.id() + " (" + active.def.title() + ") läuft noch");
    }
    Run run = switch (def.id()) {
      case "A" -> new MachineFailure();
      case "B" -> new AgvFailure();
      case "C" -> new Shortage();
      case "D" -> new Express();
      case "E" -> new FullRun();
      case "F" -> new ProductionDelay();
      case "G" -> new VehicleBreakdown();
      case "H" -> new ExpressDelivery();
      case "I" -> new ShortageDelivery();
      case "J" -> new SupplyChain();
      default -> throw DomainException.notFound("Szenario", scenarioId);
    };
    return begin(def, run, now);
  }

  /**
   * Lists the run, then intervenes. If the intervention fails (perhaps after part of it took effect, e.g. stock
   * booked out), the run is finished as FAILED with the reason, so the attempt stays traceable, and the error goes to
   * the caller. A finished run blocks no further start.
   */
  String begin(Definition def, Run run, long now) {
    run.def = def;
    run.runId = "SZ-" + (++counter);
    run.startedAtMs = now;
    runs.add(0, run);
    while (runs.size() > KEEP) runs.remove(runs.size() - 1);
    boolean begun = false;
    try {
      run.begin(now);
      begun = true;
    } catch (RuntimeException | Error e) {
      String type = e.getClass().getName();
      try {
        finish(run, now, false, "Eingriff gescheitert (" + type.substring(type.lastIndexOf('.') + 1) + ")");
      } catch (RuntimeException reportFailed) {
        // the event could not be written; the run still ends below, and the caller gets the original error
      }
      throw e;
    } finally {
      if (!begun && !run.done()) { // whatever happened, never leave an active run that blocks every start
        run.state = State.FAILED;
        run.finishedAtMs = now;
      }
    }
    return run.runId;
  }

  /** One plant step; looks at the active run once per simulated second. */
  void step(long now) {
    Run run = active();
    if (run == null || now % CHECK_EVERY_MS != 0) return;
    if (now - run.startedAtMs >= run.def.limitMs()) {
      finish(run, now, false, "Zeitgrenze erreicht: " + run.timeoutReason());
      return;
    }
    run.check(now);
  }

  void reset() {
    runs.clear();
    counter = 0;
  }

  private Run active() {
    return !runs.isEmpty() && !runs.get(0).done() ? runs.get(0) : null;
  }

  String json() {
    var w = new JsonWriter().beginObject().name("definitions").beginArray();
    for (Definition d : DEFINITIONS) {
      w.beginObject().name("id").value(d.id()).name("title").value(d.title()).name("trigger").value(d.trigger())
          .name("expected").value(d.expected()).name("limitMinutes").value(d.limitMs() / MIN).endObject();
    }
    w.endArray().name("runs").beginArray();
    for (Run r : runs) {
      w.beginObject().name("runId").value(r.runId).name("scenario").value(r.def.id()).name("state").value(r.state.name())
          .name("orderId").value(r.orderId).name("startedAtMs").value(r.startedAtMs).name("finishedAtMs");
      if (r.finishedAtMs == null) w.nullValue();
      else w.value((long) r.finishedAtMs);
      w.name("steps").beginArray();
      for (Step s : r.steps) w.beginObject().name("atMs").value(s.atMs()).name("text").value(s.text()).endObject();
      w.endArray().endObject();
    }
    return w.endArray().endObject().toString();
  }

  // ---- lifecycle of a run -------------------------------------------------------------------------------------

  private void started(Run run, long now, String orderId, String text) {
    run.state = State.RUNNING;
    run.orderId = orderId;
    note(run, now, text);
    bus.publish(EventTypes.SCENARIO_STARTED, "scenario", Severity.INFO, orderId,
        new Payloads.Note(run.def.id() + " " + run.def.title() + ": " + text));
  }

  private static void note(Run run, long now, String text) {
    run.steps.add(new Step(now, text));
  }

  private void finish(Run run, long now, boolean passed, String text) {
    run.end();
    run.state = passed ? State.PASSED : State.FAILED;
    run.finishedAtMs = now;
    note(run, now, text);
    bus.publish(EventTypes.SCENARIO_FINISHED, "scenario", passed ? Severity.SUCCESS : Severity.WARNING, run.orderId,
        new Payloads.Note(run.def.id() + " " + run.def.title() + ": " + text));
  }

  abstract class Run {
    Definition def;
    String runId;
    long startedAtMs;
    State state = State.WAITING;
    String orderId;
    Long finishedAtMs;
    final List<Step> steps = new ArrayList<>();

    boolean done() {
      return state == State.PASSED || state == State.FAILED;
    }

    /** The intervention at start; may already find the order (started) or leave the run WAITING for its trigger. */
    abstract void begin(long now);

    /** Called once per simulated second while the run is active. */
    abstract void check(long now);

    abstract String timeoutReason();

    /** Undoes the intervention when the run ends, also by timeout. */
    void end() {}
  }

  /**
   * A: a fault on a CNC that is producing. The running batch stays in the machine (its parts are inside); SmartFactory
   * plans the work around it on machines with the same capability (Spec 10). So that there is work to plan around in
   * every run, the scenario then places an order whose first step prefers the failed machine. Passes when that step,
   * or an operation that was waiting on the machine, is planned on another machine.
   */
  final class MachineFailure extends Run {
    private String machineId;
    private int probeSeq = 1;
    private final List<String> watched = new ArrayList<>(); // "orderId/seq" that must leave the machine

    @Override
    void begin(long now) {
      note(this, now, "Wartet auf eine CNC, die gerade fertigt");
    }

    @Override
    void check(long now) {
      if (machineId == null) {
        trigger(now);
        return;
      }
      for (String key : watched) {
        int slash = key.lastIndexOf('/');
        String order = key.substring(0, slash);
        int seq = Integer.parseInt(key.substring(slash + 1));
        for (Plan.PlannedOp op : sf.plan().ops()) {
          if (op.orderId().equals(order) && op.seq() == seq && !op.machineId().equals(machineId)) {
            finish(this, now, true, "Auftrag " + order + " Schritt " + seq + " umgeplant auf Ausweichmaschine "
                + op.machineId() + " statt " + machineId);
            return;
          }
        }
      }
    }

    private void trigger(long now) {
      for (MachineRecord m : sf.machines()) {
        if (m.status() != MachineStatus.RUNNING || SmartFactoryMasterData.machine(m.id()).kind() != MachineKind.CNC) continue;
        Operation running = sf.runningOn(m.id());
        if (running == null) continue;
        machineId = m.id();
        for (Plan.PlannedOp op : sf.plan().ops()) {
          boolean isRunning = op.orderId().equals(running.orderId()) && op.seq() == running.seq();
          if (op.machineId().equals(machineId) && !isRunning) watched.add(op.orderId() + "/" + op.seq());
        }
        sf.injectFault(machineId, "SERVO");
        String product = productFor(machineId);
        String probe = sf.createOrder(new NewOrder(product, 2, Priority.NORMAL, 4 * 60 * MIN));
        watched.add(probe + "/" + probeSeq);
        started(this, now, probe, "Störung SERVO an " + machineId + ", das laufende Los von " + running.orderId()
            + " bleibt in der Maschine; Auftrag " + probe + " (" + product + ") bevorzugt " + machineId);
        return;
      }
    }

    /** A product whose first step prefers the machine, else one whose first step the machine can do. */
    private String productFor(String machine) {
      var abilities = SmartFactoryMasterData.machine(machine).capabilities();
      String able = null;
      for (var step : SmartFactoryMasterData.routing()) {
        if (step.seq() != 1) continue;
        if (machine.equals(step.preferredMachineId())) return step.productId();
        if (able == null && abilities.contains(step.process())) able = step.productId();
      }
      return able != null ? able : SmartFactoryMasterData.products().get(0).id();
    }

    @Override
    String timeoutReason() {
      return machineId == null ? "Keine CNC fertigte; läuft die Simulation mit Aufträgen?"
          : "Keine Arbeit wurde von " + machineId + " auf eine Ausweichmaschine geplant";
    }
  }

  /** B: a fault on an AGV carrying a load; passes when another AGV takes the failed transport over. */
  final class AgvFailure extends Run {
    private String agvId;
    private String transportId;
    private boolean failedSeen;

    @Override
    void begin(long now) {
      note(this, now, "Wartet auf ein beladenes AGV");
    }

    @Override
    void check(long now) {
      List<TransportOrder> open = fp.openTransports();
      if (agvId == null) {
        for (TransportOrder t : open) {
          if (t.status() != TransportStatus.IN_TRANSIT || t.agvId() == null) continue;
          agvId = t.agvId();
          transportId = t.id();
          fp.injectFault(agvId, "NAV");
          started(this, now, t.orderId(), "Störung NAV an " + agvId + ", beladen mit Transport " + transportId);
          return;
        }
        return;
      }
      TransportOrder t = null;
      for (TransportOrder x : open) if (x.id().equals(transportId)) t = x;
      if (t == null) {
        finish(this, now, failedSeen, failedSeen ? "Transport " + transportId + " zugestellt"
            : "Transport " + transportId + " wurde vor dem Ausfall zugestellt");
        return;
      }
      if (t.status() == TransportStatus.FAILED && !failedSeen) {
        failedSeen = true;
        note(this, now, "Transport " + transportId + " ausgefallen, wartet auf ein Ersatz-AGV");
      }
      if (t.agvId() != null && !t.agvId().equals(agvId)) {
        finish(this, now, true, "Ersatz-AGV " + t.agvId() + " übernimmt Transport " + transportId);
      }
    }

    @Override
    String timeoutReason() {
      return agvId == null ? "Kein AGV fuhr beladen; läuft die Simulation mit Aufträgen?"
          : "Transport " + transportId + " fand kein Ersatz-AGV";
    }
  }

  /** C: stock of the first material lowered to half of what a new order needs; passes when the order waits in SHORTAGE. */
  final class Shortage extends Run {

    @Override
    void begin(long now) {
      lowerStockAndOrder(this, now);
    }

    @Override
    void check(long now) {
      OrderStatus s = sf.order(orderId).status();
      if (s == OrderStatus.SHORTAGE) {
        finish(this, now, true, "Keine Reservierung, Auftrag wartet auf den Wareneingang");
      } else if (s == OrderStatus.RELEASED || s == OrderStatus.IN_PROGRESS || s == OrderStatus.COMPLETED) {
        finish(this, now, false, "Auftrag wurde ohne Engpass freigegeben");
      }
    }

    @Override
    String timeoutReason() {
      return "Auftrag " + orderId + " kam nicht in SHORTAGE";
    }
  }

  /** D: an urgent order; passes once it is planned, listing the orders whose end moved later. */
  final class Express extends Run {
    private Map<String, Long> before;

    @Override
    void begin(long now) {
      before = ends(sf.plan());
      String product = SmartFactoryMasterData.products().get(0).id();
      String id = sf.createOrder(new NewOrder(product, 2, Priority.EILIG, 60 * MIN));
      started(this, now, id, "Eilauftrag " + id + " angelegt");
    }

    @Override
    void check(long now) {
      Map<String, Long> after = ends(sf.plan());
      if (!after.containsKey(orderId)) return; // not planned yet
      int shifted = 0;
      for (var e : after.entrySet()) {
        Long was = before.get(e.getKey());
        if (was == null || e.getValue() <= was) continue;
        shifted++;
        note(this, now, e.getKey() + " endet " + ((e.getValue() - was + MIN - 1) / MIN) + " min später");
      }
      finish(this, now, true, shifted == 0 ? "Kein anderer Auftrag verschoben" : shifted + " Aufträge verschoben");
    }

    @Override
    String timeoutReason() {
      return "Eilauftrag " + orderId + " wurde nicht eingeplant";
    }

    private Map<String, Long> ends(Plan plan) {
      Map<String, Long> m = new TreeMap<>(); // sorted by order id: the same notes in every runtime
      for (Plan.PlannedOp op : plan.ops()) m.merge(op.orderId(), op.endMs(), Math::max);
      return m;
    }
  }

  /** E: a new order, followed from release through every step to the putaway of the finished goods. */
  final class FullRun extends Run {
    private OrderStatus last;
    private boolean released;
    private final Set<Integer> stepsDone = new TreeSet<>();

    @Override
    void begin(long now) {
      String product = SmartFactoryMasterData.products().get(0).id();
      String id = sf.createOrder(new NewOrder(product, 2, Priority.HOCH, 6 * 60 * MIN));
      started(this, now, id, "Auftrag " + id + " angelegt, 2 Stück " + product);
      last = OrderStatus.CREATED;
    }

    @Override
    void check(long now) {
      var order = sf.order(orderId);
      OrderStatus s = order.status();
      // release (with the reservation) can pass between two looks; it happened if the order is beyond it
      if (!released && (s == OrderStatus.RELEASED || s == OrderStatus.IN_PROGRESS || s == OrderStatus.COMPLETED)) {
        released = true;
        note(this, now, "Auftrag " + word(OrderStatus.RELEASED));
      }
      if (s != last) {
        last = s;
        if (s != OrderStatus.RELEASED && s != OrderStatus.COMPLETED) note(this, now, "Auftrag " + word(s));
      }
      for (Operation op : sf.operations(orderId)) {
        if (op.status() == Operation.Status.DONE && stepsDone.add(op.seq())) {
          note(this, now, "Schritt " + op.seq() + " (" + op.process().name() + ") fertig auf " + op.machineId());
        }
      }
      if (s == OrderStatus.CANCELLED) {
        finish(this, now, false, "Auftrag wurde storniert");
      } else if (s == OrderStatus.COMPLETED) {
        // SmartFactory completes an order when its putaway transport is delivered; without a good part there is none
        if (order.good() > 0) finish(this, now, true, "Fertigware eingelagert, " + order.good() + " Gutteile");
        else finish(this, now, false, "Kein Gutteil, der Auftrag endete ohne Einlagerung");
      }
    }

    @Override
    String timeoutReason() {
      return "Auftrag " + orderId + " steht bei " + word(last);
    }
  }

  /** C's intervention: stock of the first material down to half of a new order's need, then that order. */
  private void lowerStockAndOrder(Run run, long now) {
    String product = SmartFactoryMasterData.products().get(0).id();
    int quantity = 10;
    List<MaterialDemand> demand = sf.catalog().demandFor(product, quantity);
    String material = demand.get(0).materialId();
    int need = 0;
    for (MaterialDemand d : demand) if (d.materialId().equals(material)) need += d.quantity();
    int keep = need / 2;
    int available = 0;
    for (StockLine l : fp.stock()) if (l.materialId().equals(material)) available += l.available();
    int remove = Math.max(0, available - keep); // never more than is available: stock stays at or above zero
    for (StockLine l : fp.stock()) {
      if (remove == 0) break;
      if (!l.materialId().equals(material) || l.available() <= 0) continue;
      int take = Math.min(remove, l.available());
      fp.issue(material, l.locationId(), take);
      remove -= take;
    }
    String id = sf.createOrder(new NewOrder(product, quantity, Priority.NORMAL, 4 * 60 * MIN));
    started(run, now, id, "Bestand von " + material + " auf " + Math.min(available, keep) + " gesenkt, Auftrag " + id
        + " braucht " + need);
  }

  /** F: an order whose delivery is already planned; its last machine is stopped until that tour leaves. */
  final class ProductionDelay extends Run {
    private String tourId;
    private String machineId;

    @Override
    void begin(long now) {
      String product = SmartFactoryMasterData.products().get(0).id();
      String id = sf.createOrder(new NewOrder(product, 2, Priority.HOCH, 90 * MIN));
      started(this, now, id, "Auftrag " + id + " angelegt, wartet auf seine Lieferplanung");
    }

    @Override
    void check(long now) {
      Delivery d = rm.deliveryOfOrder(orderId);
      if (tourId == null) {
        if (d.tourId() == null && sf.order(orderId).status().terminal()) {
          // finished before any wave counted on it: nothing to delay, so try again with a new order
          String product = SmartFactoryMasterData.products().get(0).id();
          String next = sf.createOrder(new NewOrder(product, 2, Priority.HOCH, 90 * MIN));
          note(this, now, "Auftrag " + orderId + " war vor seiner Lieferplanung fertig, neuer Auftrag " + next);
          orderId = next;
          return;
        }
        if (d.tourId() == null || d.status() != DeliveryStatus.AWAITING_GOODS) return;
        List<Operation> ops = sf.operations(orderId);
        Operation last = ops.get(ops.size() - 1);
        if (last.machineId() == null || last.status() == Operation.Status.DONE) return;
        tourId = d.tourId();
        machineId = last.machineId();
        sf.stopMachine(machineId);
        note(this, now, "Lieferung " + d.id() + " in Tour " + tourId + " geplant; " + machineId + " angehalten");
        return;
      }
      Tour t = tour(tourId);
      if (t.status() == TourStatus.PLANNED || t.status() == TourStatus.LOADING) return;
      boolean onBoard = tourId.equals(d.tourId()) && d.status() != DeliveryStatus.AWAITING_GOODS;
      if (onBoard) finish(this, now, false, "Tour " + tourId + " fuhr mit Lieferung " + d.id() + " ab");
      else if (t.status() == TourStatus.REPLANNED) finish(this, now, true, "Tour " + tourId + " neu geplant");
      else finish(this, now, true, "Tour " + tourId + " fuhr ohne die unfertige Lieferung " + d.id() + " ab");
    }

    @Override
    void end() {
      if (machineId != null) sf.startMachine(machineId);
    }

    @Override
    String timeoutReason() {
      return tourId == null ? "Lieferung von " + orderId + " wurde nie vor der Fertigstellung geplant"
          : "Tour " + tourId + " fuhr nicht ab";
    }
  }

  /** G: the first loaded vehicle under way breaks down; its open deliveries must get a new tour. */
  final class VehicleBreakdown extends Run {
    private String tourId;
    private final List<String> open = new ArrayList<>();

    @Override
    void begin(long now) {
      // waits in check() for a vehicle under way
    }

    @Override
    void check(long now) {
      if (tourId == null) {
        for (RouteMind.TruckView v : rm.trucks()) {
          if (v.status() != VehicleStatus.EN_ROUTE) continue;
          Tour t = tour(v.tourId());
          for (Tour.Stop s : t.stops()) {
            Delivery d = delivery(s.deliveryId());
            if (d.status() == DeliveryStatus.IN_TRANSIT) open.add(d.id());
          }
          if (open.isEmpty()) continue;
          tourId = t.id();
          rm.breakDown(v.id());
          started(this, now, delivery(open.get(0)).orderId(), "Panne an " + v.id() + " auf Tour " + tourId + ", "
              + open.size() + " Lieferungen offen");
          return;
        }
        return;
      }
      for (String id : open) {
        Delivery d = delivery(id);
        boolean replanned = d.status() == DeliveryStatus.DELIVERED
            || (d.tourId() != null && !d.tourId().equals(tourId));
        if (!replanned) return;
      }
      finish(this, now, true, "Offene Lieferungen in neuen Touren: " + String.join(", ", open));
    }

    @Override
    String timeoutReason() {
      return tourId == null ? "Kein Lieferfahrzeug war unterwegs; läuft die Simulation mit Aufträgen?"
          : "Lieferungen von Tour " + tourId + " wurden nicht neu geplant";
    }
  }

  /** H: an urgent order; passes once its delivery is on a tour. */
  final class ExpressDelivery extends Run {
    @Override
    void begin(long now) {
      String product = SmartFactoryMasterData.products().get(0).id();
      String id = sf.createOrder(new NewOrder(product, 2, Priority.EILIG, 45 * MIN));
      started(this, now, id, "Eilauftrag " + id + " angelegt");
    }

    @Override
    void check(long now) {
      Delivery d = rm.deliveryOfOrder(orderId);
      if (d.tourId() == null) return;
      Tour t = tour(d.tourId());
      finish(this, now, true, "Lieferung " + d.id() + " in Tour " + t.id() + ", Abfahrt " + (t.departAtMs() / MIN)
          + " min, Ankunft geplant " + (d.plannedArrivalMs() / MIN) + " min");
    }

    @Override
    String timeoutReason() {
      return "Lieferung von " + orderId + " wurde nicht eingeplant";
    }
  }

  /** I: the order of C waits in SHORTAGE; its delivery gets no tour through the next wave. */
  final class ShortageDelivery extends Run {
    private Long shortageAt;

    @Override
    void begin(long now) {
      lowerStockAndOrder(this, now); // the intervention of C, reported under this run
    }

    @Override
    void check(long now) {
      if (shortageAt == null) {
        if (sf.order(orderId).status() != OrderStatus.SHORTAGE) return;
        shortageAt = now;
        note(this, now, "Auftrag in SHORTAGE");
        return;
      }
      long nextWave = (shortageAt / RouteMind.WAVE_MS + 1) * RouteMind.WAVE_MS;
      if (now < nextWave + CHECK_EVERY_MS) return;
      Delivery d = rm.deliveryOfOrder(orderId);
      if (d.tourId() == null) {
        finish(this, now, true, "Lieferung " + d.id() + " ohne Termin, sie wartet auf den Wareneingang");
      } else {
        finish(this, now, false, "Lieferung " + d.id() + " wurde trotz Engpass geplant");
      }
    }

    @Override
    String timeoutReason() {
      return "Auftrag " + orderId + " kam nicht in SHORTAGE";
    }
  }

  /** J: the whole chain of one order up to the customer. */
  final class SupplyChain extends Run {
    private DeliveryStatus last;

    @Override
    void begin(long now) {
      String product = SmartFactoryMasterData.products().get(0).id();
      String id = sf.createOrder(new NewOrder(product, 2, Priority.HOCH, 90 * MIN));
      started(this, now, id, "Auftrag " + id + " angelegt");
    }

    @Override
    void check(long now) {
      Delivery d = rm.deliveryOfOrder(orderId);
      if (d.status() != last) {
        last = d.status();
        String text = switch (d.status()) {
          case CREATED -> null;
          case AWAITING_GOODS -> "Auftrag freigegeben, Lieferung " + d.id() + " für " + d.customerId();
          case READY_TO_LOAD -> "Ware eingelagert und an der Verladezone";
          case LOADED -> "Ware verladen";
          case IN_TRANSIT -> "Tour " + d.tourId() + " abgefahren";
          case DELIVERED -> null;
          case FAILED -> "Zustellung gescheitert, neuer Versuch";
          case CANCELLED -> null;
        };
        if (text != null) note(this, now, text);
      }
      if (d.status() == DeliveryStatus.DELIVERED) {
        finish(this, now, true, "Ware zugestellt bei " + d.customerId());
      } else if (d.status() == DeliveryStatus.CANCELLED) {
        finish(this, now, false, "Lieferung storniert");
      }
    }

    @Override
    String timeoutReason() {
      return "Lieferung von " + orderId + " steht bei " + last;
    }
  }

  private Tour tour(String id) {
    for (Tour t : rm.tours()) if (t.id().equals(id)) return t;
    throw DomainException.notFound("Tour", id);
  }

  private Delivery delivery(String id) {
    for (Delivery d : rm.deliveries()) if (d.id().equals(id)) return d;
    throw DomainException.notFound("Lieferauftrag", id);
  }

  static String word(OrderStatus s) {
    return switch (s) {
      case CREATED -> "angelegt";
      case MATERIAL_PENDING -> "wartet auf Material";
      case SHORTAGE -> "in Fehlmenge";
      case RELEASED -> "freigegeben, Material reserviert";
      case IN_PROGRESS -> "in Arbeit";
      case COMPLETED -> "fertig";
      case CANCELLED -> "storniert";
    };
  }
}
