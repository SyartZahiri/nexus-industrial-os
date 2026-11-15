package io.nexus.plant;

import io.nexus.contracts.Domain.MachineKind;
import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.json.JsonWriter;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.contracts.port.ProductCatalog.MaterialDemand;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.TransportOrder;
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
          "Umplanung auf eine Ausweichmaschine, Transporte umgeleitet", 30 * MIN),
      new Definition("B", "AGV-Ausfall", "Störung an einem beladenen AGV",
          "Transport fällt aus, ein Ersatz-AGV übernimmt die Ladung", 30 * MIN),
      new Definition("C", "Materialengpass", "Bestand gesenkt, dann ein Auftrag über dem Bestand",
          "Keine Reservierung, der Auftrag wartet in SHORTAGE auf den Wareneingang", 30 * MIN),
      new Definition("D", "Expressauftrag", "Auftrag mit Priorität eilig",
          "Neuplanung, verschobene Aufträge mit ihrer Verspätung", 5 * MIN),
      new Definition("E", "Kompletter Durchlauf", "Neuer Auftrag",
          "Reservierung bis Einlagerung, als Schrittfolge sichtbar", 6 * 60 * MIN));

  private final SmartFactory sf;
  private final FlowPilot fp;
  private final EventBus bus;
  private final List<Run> runs = new ArrayList<>(); // newest first
  private int counter;

  Scenarios(SmartFactory sf, FlowPilot fp, EventBus bus) {
    this.sf = sf;
    this.fp = fp;
    this.bus = bus;
  }

  String start(String scenarioId, long now) {
    Run active = active();
    if (active != null) {
      throw DomainException.conflict("Szenario " + active.def.id() + " (" + active.def.title() + ") läuft noch");
    }
    Definition def = null;
    for (Definition d : DEFINITIONS) if (d.id().equals(scenarioId)) def = d;
    if (def == null) throw DomainException.notFound("Szenario", scenarioId);
    Run run = switch (def.id()) {
      case "A" -> new MachineFailure();
      case "B" -> new AgvFailure();
      case "C" -> new Shortage();
      case "D" -> new Express();
      case "E" -> new FullRun();
      default -> throw DomainException.notFound("Szenario", scenarioId);
    };
    run.def = def;
    run.runId = "SZ-" + (++counter);
    run.startedAtMs = now;
    runs.add(0, run);
    while (runs.size() > KEEP) runs.remove(runs.size() - 1);
    run.begin(now);
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
  }

  /**
   * A: a fault on a CNC that is producing and has further operations planned. The running batch stays in the machine
   * (its parts are inside); SmartFactory moves the waiting operations to machines with the same capability (Spec 10).
   * Passes when at least one of them is assigned to another machine.
   */
  final class MachineFailure extends Run {
    private String machineId;
    private final List<String> waiting = new ArrayList<>(); // "orderId/seq" planned on the machine before the fault

    @Override
    void begin(long now) {
      note(this, now, "Wartet auf eine CNC, die fertigt und weitere Arbeitsgänge eingeplant hat");
    }

    @Override
    void check(long now) {
      if (machineId == null) {
        trigger(now);
        return;
      }
      int moved = 0;
      String to = null;
      for (String key : waiting) {
        int slash = key.lastIndexOf('/');
        String order = key.substring(0, slash);
        int seq = Integer.parseInt(key.substring(slash + 1));
        for (Operation op : sf.operations(order)) {
          if (op.seq() == seq && op.machineId() != null && !op.machineId().equals(machineId)) {
            moved++;
            to = op.machineId();
          }
        }
      }
      if (moved > 0) {
        finish(this, now, true, moved + " von " + waiting.size() + " Arbeitsgängen umgeplant von " + machineId + ", zuletzt auf " + to);
      }
    }

    private void trigger(long now) {
      for (MachineRecord m : sf.machines()) {
        if (m.status() != MachineStatus.RUNNING || SmartFactoryMasterData.machine(m.id()).kind() != MachineKind.CNC) continue;
        Operation running = sf.runningOn(m.id());
        if (running == null) continue;
        List<String> planned = new ArrayList<>();
        for (Plan.PlannedOp op : sf.plan().ops()) {
          boolean isRunning = op.orderId().equals(running.orderId()) && op.seq() == running.seq();
          if (op.machineId().equals(m.id()) && !isRunning) planned.add(op.orderId() + "/" + op.seq());
        }
        if (planned.isEmpty()) continue;
        machineId = m.id();
        waiting.addAll(planned);
        sf.injectFault(machineId, "SERVO");
        String first = planned.get(0).substring(0, planned.get(0).lastIndexOf('/'));
        started(this, now, first, "Störung SERVO an " + machineId + ", " + planned.size()
            + " Arbeitsgänge dort eingeplant, das laufende Los von " + running.orderId() + " bleibt in der Maschine");
        return;
      }
    }

    @Override
    String timeoutReason() {
      return machineId == null ? "Keine CNC fertigte mit weiteren eingeplanten Arbeitsgängen; läuft die Simulation mit Aufträgen?"
          : "Kein Arbeitsgang verliess " + machineId;
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
    private String material;

    @Override
    void begin(long now) {
      String product = SmartFactoryMasterData.products().get(0).id();
      int quantity = 10;
      List<MaterialDemand> demand = sf.catalog().demandFor(product, quantity);
      material = demand.get(0).materialId();
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
      started(this, now, id, "Bestand von " + material + " auf " + Math.min(available, keep) + " gesenkt, Auftrag " + id
          + " braucht " + need);
    }

    @Override
    void check(long now) {
      OrderStatus s = sf.order(orderId).status();
      if (s == OrderStatus.SHORTAGE) {
        finish(this, now, true, "Keine Reservierung, Auftrag wartet auf Wareneingang von " + material);
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
