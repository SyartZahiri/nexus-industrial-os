package io.nexus.plant;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.json.JsonWriter;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.smartfactory.SmartFactory;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.store.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
