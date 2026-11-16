# M3-A Scenario Lab, Playwright und Bildrate – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die fünf Szenarien aus Spec 15 (Maschinenausfall, AGV-Ausfall, Materialengpass, Expressauftrag, kompletter Durchlauf) laufen in beiden Betriebsarten, der Bereich Scenario Lab startet und verfolgt sie, Playwright prüft alle fünf gegen Browser- und Server-Betrieb, und die Bildrate von FactoryTwin bei 25-fachem Tempo ist gemessen.

**Architecture:** Ein Szenario ist ein kleiner Ablauf im Kern (`platform/plant/Scenarios.java`): Er greift einmal ein (Störung, Abbuchung, Auftrag) und prüft danach jede simulierte Sekunde, ob das erwartete Verhalten eingetreten ist (bestanden) oder seine Zeitgrenze abläuft (nicht bestanden, mit Grund). Er läuft als eigene Phase im Simulationsschritt, also deterministisch und in beiden Laufzeiten gleich. Gestartet wird über den Kern-Befehl `runScenario`, gelesen über die Abfrage `scenarios`; der Server bietet zusätzlich `GET /api/v1/scenarios` und `POST /api/v1/scenarios/{id}/run` (Spec 12). `scenario.started` und `scenario.finished` tragen die Auftrags-ID als `correlationId`, damit der Event Monitor die ganze Kette eines Szenarios zeigt.

**Tech Stack:** Java 25 (Kern, TeaVM), Spring Boot 4.1.2, React 19, TypeScript, Vitest; neu als Entwicklungsabhängigkeit `@playwright/test` (lokal mit dem installierten Chrome, `channel: 'chrome'`).

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Revision 3), Abschnitt 7 (Ereignisse, `correlationId`), 11 (feste Schrittfolge), 12 (`/scenarios`), 14 (Flüssigkeit: 60 Bilder pro Sekunde bei 25×), 15 (Scenario Lab), 17 (Playwright: Szenarien A bis E gegen das laufende Backend), 18 (M3). Voraussetzung: M2-C ist gemergt (`ad8eadf`).

**Form:** wie M2-C: vollständiger Code für Kern, Logik und Bausteine, vollständiges JSX für die Bereichsseite, vollständige Playwright-Tests.

## Aufteilung von M3

Spec 18 nennt für M3 zwei unabhängige Teile. Dieser Plan ist **M3-A** (Szenarien, Scenario Lab, Playwright in beiden Betriebsarten, Bildrate). **M3-B** folgt als eigener Plan: CI mit GitHub Actions, Docker Compose, GitHub Pages im Browser-Modus und README mit Architekturdiagramm. M3-B baut auf den Playwright-Tests von M3-A auf.

## Entscheidungen dieses Plans

- **Szenarien im Kern, nicht im Controller.** Spec 15 sagt „Backend-Endpunkt“; mit zwei Laufzeiten (Spec 2a) heisst das: dieselbe Logik im Kern, der Server-Endpunkt ruft sie auf, der Browser über den Worker. Kein zweiter Weg.
- **Ein Szenario zur Zeit.** Ein zweiter Start, während einer läuft, wird mit 409 und Grund abgelehnt. So bleibt jede Ereigniskette eindeutig einem Szenario zugeordnet.
- **Zeitgrenzen in Simulationszeit:** A und B 30 min, C 30 min, D 5 min, E 6 h. Läuft die Simulation nicht, läuft auch keine Zeitgrenze ab.
- **`correlationId` der Szenario-Ereignisse ist die Auftrags-ID**, sobald sie feststeht (C, D, E sofort; A und B beim Auslösen, wenn die betroffene CNC bzw. der beladene Transport gefunden ist). Spec 15 verlangt den Ereignisverlauf „gefiltert auf die correlationId des Szenarios“; über die Auftrags-ID sieht man Ursache und Wirkung zusammen.
- **Materialengpass (C) senkt erst den Bestand.** Eine zulässige Menge (1–100) liegt beim Startbestand unter dem Lagerbestand. C bucht deshalb Bestand des ersten Materials ab (`issue`), bis nur die Hälfte des Bedarfs übrig ist, nie unter null, und legt dann den Auftrag an. Das ist ein echter Eingriff in die Anlage und steht so im Szenariotext.
- **Ergebnisse als Schritte mit Zeit und Text** (`steps: { atMs, text }[]`). Die Oberfläche formatiert die Zeit. Der Kern formatiert keine Uhrzeit, weil der Schichtbeginn eine Darstellungsfrage ist.
- **Ein Reset vergisst alle Szenarioläufe.** Ein laufendes Szenario endet ohne `scenario.finished`, weil sein Lauf nicht mehr existiert.
- **Playwright mit dem installierten Chrome** (`channel: 'chrome'`). Lokal ist so kein Browser-Download nötig. In CI (M3-B) wird Chromium installiert. Zwei Konfigurationen: `playwright.config.ts` (Browser-Modus, Port 5173) und `playwright.server.config.ts` (Server auf 8080, Oberfläche auf 5179).
- **Bildrate als Messung, nicht als harte Grenze.** Headless-Chrome rendert ohne GPU, ein fester Grenzwert wäre dort falsch. Der Test misst über 5 s mit `requestAnimationFrame`, schreibt den Wert ins Testergebnis und prüft nur gegen `FPS_MIN`, wenn gesetzt. Gemessen wird sichtbar im Browserfenster (`--headed`) auf diesem Rechner, das Ergebnis steht in der README.

## Global Constraints

- Alles aus M2 gilt weiter: keine Geschäftsregeln im Frontend, TypeScript strikt, keine neuen Laufzeitabhängigkeiten (Playwright ist Entwicklungsabhängigkeit), Texte auf Deutsch, Code und Kommentare auf Englisch.
- Kern: TeaVM-tauglich (keine Streams, kein Reflection-JSON, nur `JsonWriter`), deterministisch (keine Systemzeit, kein `Math.random`), Szenarien nur innerhalb des Simulationsschritts unter der Bus-Sperre.
- Spec 15: Szenarien A–E mit Auslöser und erwartetem Verhalten wie in der Tabelle; `scenario.started` und `scenario.finished` werden gemeldet.
- Spec 12: `GET /scenarios`, `POST /scenarios/{id}/run` unter `/api/v1`.
- Spec 17: „End-to-End (Playwright): Szenarien A bis E gegen das laufende Backend“; Spec 18: Playwright in beiden Betriebsarten.
- Spec 14: Zielwert 60 Bilder pro Sekunde in der 3D-Ansicht bei 25-fachem Tempo auf einem üblichen Laptop.
- Design wie M2-B: Haarlinien, Farbe nur für Zustände, Typskala 11/12/13/15/20/32, Zustandsfarbe nie allein.
- Nach jeder Änderung am Kern `npm run core` in `FactoryTwin`.
- Commits enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Nicht pushen.

## Review Focus

1. **Ein zweites Szenario, während eines läuft.** Erwartet: 409 mit Grund in der Meldungszeile, das erste läuft weiter, in der Oberfläche sind die Start-Knöpfe gesperrt. Tests in Task 1 (`aSecondScenarioWaitsForTheFirst`) und Task 5 (`aRunningScenarioBlocksTheOthers`).
2. **Ein Auslöser, der nie eintritt** (Maschinenausfall ohne laufende CNC, etwa ohne Aufträge). Erwartet: kein endloses Warten, nach der Zeitgrenze „nicht bestanden“ mit Grund. Test in Task 2 (`aScenarioThatCannotTriggerFailsWithItsReason`).
3. **Ein Reset während eines Szenarios.** Erwartet: kein Lauf mehr in der Liste, kein `scenario.finished` aus dem alten Lauf im neuen. Test in Task 1 (`aResetEndsTheScenario`).
4. **Materialengpass bei schon knappem oder leerem Bestand.** Erwartet: nie negativer Bestand, nie mehr abgebucht als verfügbar, das Szenario besteht trotzdem. Test in Task 3 (`shortageNeverBooksBelowZero`).
5. **Expressauftrag ohne andere Aufträge.** Erwartet: bestanden mit „Kein anderer Auftrag verschoben“, kein Fehler. Test in Task 3 (`anExpressOrderAloneShiftsNothing`).

---

### Task 1: Szenario-Ablauf im Kern und kompletter Durchlauf (E)

**Files:**
- Create: `platform/plant/src/main/java/io/nexus/plant/Scenarios.java`
- Modify: `platform/plant/src/main/java/io/nexus/plant/Plant.java` (Feld, Phase, Reset, `startScenario`, `scenariosJson`)
- Modify: `platform/plant/src/main/java/io/nexus/plant/PlantCommands.java` (`runScenario`), `platform/plant/src/main/java/io/nexus/plant/PlantQueries.java` (`scenarios`)
- Test: `platform/plant/src/test/java/io/nexus/plant/ScenariosTest.java`, `FactoryTwin/src/nexus/browser/engine.test.ts`

**Interfaces:**
- Produces (Kern-JSON, beide Betriebsarten):
  - Befehl `{"command":"runScenario","scenario":"E"}` → `{ok:true,result:{runId:"SZ-1"}}`; unbekannt → 404; während eines laufenden → 409 „Szenario … läuft noch“.
  - Abfrage `{"query":"scenarios"}` → `{ definitions: [{id,title,trigger,expected,limitMinutes}], runs: [{runId,scenario,state,orderId,startedAtMs,finishedAtMs,steps:[{atMs,text}]}] }` (neueste zuerst, höchstens 10; `state` ist `WAITING | RUNNING | PASSED | FAILED`).
- `Plant.startScenario(String id): String` und `Plant.scenariosJson(): String`, beide unter der Bus-Sperre.
- `Scenarios.Run` als abstrakte innere Klasse mit `begin(long now)`, `check(long now)`, `timeoutReason()`; Task 2 und 3 ergänzen ihre Läufe im `switch` von `start`.

- [ ] **Step 1: Failing tests schreiben**

`ScenariosTest.java`:

```java
package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.json.JsonReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenariosTest {
  static final long MIN = 60_000;

  @SuppressWarnings("unchecked")
  static Map<String, Object> answer(String json) {
    return (Map<String, Object>) JsonReader.parse(json);
  }

  static Map<String, Object> start(Plant p, String id) {
    return answer(PlantCommands.apply(p, "{\"command\":\"runScenario\",\"scenario\":\"" + id + "\"}"));
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> runs(Plant p) {
    var result = (Map<String, Object>) answer(PlantQueries.apply(p, "{\"query\":\"scenarios\"}")).get("result");
    return (List<Map<String, Object>>) result.get("runs");
  }

  /** Advances minute by minute until the newest run has ended or limitMs have passed. */
  static Map<String, Object> untilDone(Plant p, long limitMs) {
    for (long t = 0; t < limitMs; t += MIN) {
      p.advance(MIN);
      String state = (String) runs(p).get(0).get("state");
      if (state.equals("PASSED") || state.equals("FAILED")) break;
    }
    return runs(p).get(0);
  }

  @SuppressWarnings("unchecked")
  static List<String> texts(Map<String, Object> run) {
    return ((List<Map<String, Object>>) run.get("steps")).stream().map(s -> (String) s.get("text")).toList();
  }

  static List<NexusEvent> ofType(Plant p, String type) {
    return p.events().stream().filter(e -> e.type().equals(type)).toList();
  }

  @Test
  @SuppressWarnings("unchecked")
  void listsTheFiveScenarios() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    var result = (Map<String, Object>) answer(PlantQueries.apply(p, "{\"query\":\"scenarios\"}")).get("result");
    assertThat((List<Map<String, Object>>) result.get("definitions")).extracting(d -> d.get("id"))
        .containsExactly("A", "B", "C", "D", "E");
    assertThat(runs(p)).isEmpty();
  }

  @Test
  void aFullRunEndsWithThePutaway() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "E")).containsEntry("ok", true);
    var run = untilDone(p, 6 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    String orderId = (String) run.get("orderId");
    assertThat(texts(run)).anyMatch(t -> t.contains("freigegeben")).anyMatch(t -> t.startsWith("Schritt 1"));
    assertThat(ofType(p, EventTypes.SCENARIO_STARTED)).singleElement().extracting(NexusEvent::correlationId).isEqualTo(orderId);
    assertThat(ofType(p, EventTypes.SCENARIO_FINISHED)).singleElement().extracting(NexusEvent::correlationId).isEqualTo(orderId);
  }

  @Test
  void aSecondScenarioWaitsForTheFirst() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "E")).containsEntry("ok", true);
    var second = start(p, "D");
    assertThat(second).containsEntry("ok", false).containsEntry("status", 409L);
    assertThat((String) second.get("message")).contains("läuft noch");
    assertThat(runs(p)).hasSize(1);
  }

  @Test
  void anUnknownScenarioIsNotFound() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "Z")).containsEntry("ok", false).containsEntry("status", 404L);
  }

  @Test
  void aResetEndsTheScenario() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    start(p, "E");
    p.advance(MIN);
    p.reset(7);
    assertThat(runs(p)).isEmpty();
    p.advance(10 * MIN);
    assertThat(ofType(p, EventTypes.SCENARIO_FINISHED)).isEmpty();
    assertThat(start(p, "E")).containsEntry("ok", true); // nothing blocks a new run
  }
}
```

`p.reset(7)` setzt hier nur die Anlage zurück, nicht die Speicher (das tut auf dem Server `PlantReset`). Für die Szenarien zählt nur, dass `Scenarios.reset()` aufgerufen wird.

In `engine.test.ts`:

```ts
  it('runs a scenario in the TeaVM core', () => {
    const engine = new BrowserEngine(core, 7);
    const started = engine.handle({ id: 1, kind: 'command', body: { command: 'runScenario', scenario: 'E' } }).answer as {
      ok: boolean;
      result: { runId: string };
    };
    expect(started.ok).toBe(true);
    expect(started.result.runId).toBe('SZ-1');
    const list = engine.handle({ id: 2, kind: 'query', body: { query: 'scenarios' } }).answer as { ok: boolean; result: { runs: unknown[] } };
    expect(list.result.runs).toHaveLength(1);
  });
```

- [ ] **Step 2: Tests laufen lassen, rot**

Run: `./mvnw -B -q -pl platform/plant -am test -Dtest=ScenariosTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"`
Expected: `exit=1`, „Unbekannte Abfrage“ / „Unbekannter Befehl: runScenario“.

- [ ] **Step 3: Umsetzen**

`Scenarios.java`:

```java
package io.nexus.plant;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
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
import io.nexus.smartfactory.store.OperationMaterial;
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
      OrderStatus s = sf.order(orderId).status();
      if (s != last) {
        last = s;
        note(this, now, "Auftrag " + word(s));
      }
      for (Operation op : sf.operations(orderId)) {
        if (op.status() == Operation.Status.DONE && stepsDone.add(op.seq())) {
          note(this, now, "Schritt " + op.seq() + " (" + op.process().name() + ") fertig auf " + op.machineId());
        }
      }
      if (s == OrderStatus.CANCELLED) {
        finish(this, now, false, "Auftrag wurde storniert");
        return;
      }
      if (s != OrderStatus.COMPLETED) return;
      boolean putaway = false;
      for (OperationMaterial m : sf.materials(orderId)) {
        if (m.kind() != TransportKind.PUTAWAY) continue;
        putaway = true;
        if (m.delivered()) {
          finish(this, now, true, "Fertigware eingelagert");
          return;
        }
      }
      if (!putaway) finish(this, now, true, "Auftrag fertig");
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
```

`Plant.java`:
- Feld `private final Scenarios scenarios;`, im privaten Konstruktor nach `this.memoryLog = memoryLog;`: `this.scenarios = new Scenarios(smartFactory, flowPilot, bus);`
- In `step()` nach `phase("FlowPilot", …)`: `phase("Szenarien", () -> scenarios.step(now));`
- In `reset(long seed)` im `synchronized`-Block als erste Zeile: `scenarios.reset();`
- Neue Methoden:

```java
  /** Starts scenario id (A to E) on the running plant; @return the run id. */
  public String startScenario(String id) {
    synchronized (bus) {
      return scenarios.start(id, time.nowMs());
    }
  }

  /** The scenarios and their last runs as JSON (PlantQueries "scenarios"). */
  public String scenariosJson() {
    synchronized (bus) {
      return scenarios.json();
    }
  }
```

`PlantCommands.run`, neuer Fall vor `default`:

```java
      case "runScenario" -> {
        String runId = plant.startScenario(text(c, "scenario"));
        return new JsonWriter().beginObject().name("runId").value(runId).endObject().toString();
      }
```

`PlantQueries.apply`, neuer Fall: `case "scenarios" -> plant.scenariosJson();`

- [ ] **Step 4: Tests grün, Kern für den Browser bauen**

Run: `./mvnw -B -q -pl platform/plant -am test -Dtest='ScenariosTest,PlantQueriesTest,PlantCommandsTest' -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"`
Expected: `exit=0` (Zählung im Surefire-Bericht prüfen).

Run: `cd FactoryTwin && npm run core && npx vitest run src/nexus`
Expected: PASS, auch `runs a scenario in the TeaVM core`.

Hinweis: Der Gleichlauf-Test `Scenario1h` (JVM gegen Browser gegen Server) darf sich nicht ändern; ohne gestartetes Szenario veröffentlicht die neue Phase nichts.

- [ ] **Step 5: Commit**

```bash
git add platform/plant FactoryTwin/src/nexus
git commit -m "feat(plant): Szenario-Ablauf im Kern und kompletter Durchlauf"
```

---

### Task 2: Maschinenausfall (A) und AGV-Ausfall (B)

**Files:**
- Modify: `platform/plant/src/main/java/io/nexus/plant/Scenarios.java`
- Test: `platform/plant/src/test/java/io/nexus/plant/ScenariosTest.java`

**Interfaces:**
- Consumes: `Run`, `started`, `note`, `finish` aus Task 1; `sf.machines()` (`MachineRecord(id, status, …)`), `sf.runningOn(id)`, `sf.injectFault(id, "SERVO")`, `sf.operations(orderId)`, `fp.openTransports()` (`TransportOrder(…, status, agvId, …)`), `fp.injectFault(agvId, "NAV")`.
- Produces: Szenarien A und B im `switch` von `start`.

- [ ] **Step 1: Failing tests schreiben** (in `ScenariosTest`)

```java
  @Test
  void aMachineFailureMovesTheStepToAnotherMachine() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    p.advance(5 * MIN);
    assertThat(start(p, "A")).containsEntry("ok", true);
    var run = untilDone(p, 30 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).last().asString().contains("umgeplant");
  }

  @Test
  void anAgvFailureHandsTheLoadToAnotherAgv() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    p.advance(5 * MIN);
    assertThat(start(p, "B")).containsEntry("ok", true);
    var run = untilDone(p, 30 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.contains("Störung NAV"));
  }

  @Test
  void aScenarioThatCannotTriggerFailsWithItsReason() {
    Plant p = Plant.inMemory(7, Scenario1h.rules()); // no automatic orders: no CNC will ever run
    assertThat(start(p, "A")).containsEntry("ok", true);
    var run = untilDone(p, 31 * MIN);
    assertThat(run).containsEntry("state", "FAILED");
    assertThat(texts(run)).last().asString().contains("Keine CNC");
  }
```

- [ ] **Step 2: Tests laufen lassen, rot**

Run: `./mvnw -B -q -pl platform/plant -am test -Dtest=ScenariosTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"`
Expected: `exit=1`, 404 für „A“ und „B“.

- [ ] **Step 3: Umsetzen**

Imports ergänzen: `io.nexus.contracts.Domain.MachineKind`, `io.nexus.contracts.state.MachineStatus`, `io.nexus.contracts.state.TransportStatus`, `io.nexus.flowpilot.store.TransportOrder`, `io.nexus.smartfactory.store.MachineRecord`.

Im `switch` von `start`: `case "A" -> new MachineFailure();` und `case "B" -> new AgvFailure();`.

```java
  /** A: a fault on a CNC that is producing; passes when its step runs on another machine. */
  final class MachineFailure extends Run {
    private String machineId;
    private int seq;

    @Override
    void begin(long now) {
      note(this, now, "Wartet auf eine CNC, die gerade fertigt");
    }

    @Override
    void check(long now) {
      if (machineId == null) {
        for (MachineRecord m : sf.machines()) {
          if (m.status() != MachineStatus.RUNNING || SmartFactoryMasterData.machine(m.id()).kind() != MachineKind.CNC) continue;
          Operation op = sf.runningOn(m.id());
          if (op == null) continue;
          machineId = m.id();
          seq = op.seq();
          sf.injectFault(machineId, "SERVO");
          started(this, now, op.orderId(), "Störung SERVO an " + machineId + ", Auftrag " + op.orderId() + " Schritt " + seq);
          return;
        }
        return;
      }
      for (Operation op : sf.operations(orderId)) {
        if (op.seq() == seq && op.machineId() != null && !op.machineId().equals(machineId)) {
          finish(this, now, true, "Schritt " + seq + " umgeplant von " + machineId + " auf " + op.machineId());
          return;
        }
      }
    }

    @Override
    String timeoutReason() {
      return machineId == null ? "Keine CNC fertigte; läuft die Simulation mit Aufträgen?"
          : "Schritt " + seq + " blieb auf " + machineId;
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
```

- [ ] **Step 4: Tests grün**

Run: `./mvnw -B -q -pl platform/plant -am test -Dtest=ScenariosTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"`
Expected: `exit=0`. Besteht A oder B mit Seed 7 nicht, ist das ein Befund über den Kern (Spec 15 erwartet genau dieses Verhalten): mit `superpowers:systematic-debugging` die Ursache klären, nicht den Test aufweichen.

- [ ] **Step 5: Commit**

```bash
git add platform/plant
git commit -m "feat(plant): Szenarien Maschinenausfall und AGV-Ausfall"
```

---

### Task 3: Materialengpass (C) und Expressauftrag (D)

**Files:**
- Modify: `platform/plant/src/main/java/io/nexus/plant/Scenarios.java`
- Test: `platform/plant/src/test/java/io/nexus/plant/ScenariosTest.java`

**Interfaces:**
- Consumes: `sf.catalog().demandFor(productId, quantity)` (`List<ProductCatalog.MaterialDemand(stepSeq, materialId, quantity)>`), `fp.stock()` (`StockLine(materialId, locationId, onHand, reserved).available()`), `fp.issue(materialId, locationId, quantity)`, `sf.plan().ops()` (`Plan.PlannedOp(orderId, seq, machineId, startMs, endMs, setupMs)`), `sf.order(id).status()`.
- Produces: Szenarien C und D im `switch` von `start`.

- [ ] **Step 1: Failing tests schreiben** (in `ScenariosTest`)

```java
  @Test
  void aShortageStopsTheOrderBeforeReservation() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "C")).containsEntry("ok", true);
    var run = untilDone(p, 30 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(p.smartFactory().order((String) run.get("orderId")).status().name()).isIn("SHORTAGE", "MATERIAL_PENDING", "RELEASED");
    assertThat(texts(run)).anyMatch(t -> t.contains("Wareneingang"));
  }

  @Test
  void shortageNeverBooksBelowZero() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    // empty the first material of the first product completely before the scenario
    String product = io.nexus.smartfactory.master.SmartFactoryMasterData.products().get(0).id();
    String material = p.smartFactory().catalog().demandFor(product, 1).get(0).materialId();
    for (var line : p.flowPilot().stock()) {
      if (line.materialId().equals(material) && line.available() > 0) p.flowPilot().issue(material, line.locationId(), line.available());
    }
    assertThat(start(p, "C")).containsEntry("ok", true);
    assertThat(p.flowPilot().stock()).allSatisfy(l -> assertThat(l.onHand()).isNotNegative());
    assertThat(untilDone(p, 30 * MIN)).containsEntry("state", "PASSED");
  }

  @Test
  void anExpressOrderShiftsLaterOrders() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    p.advance(20 * MIN);
    assertThat(start(p, "D")).containsEntry("ok", true);
    var run = untilDone(p, 5 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.endsWith("min später")); // at least one order really moved
    assertThat(texts(run)).last().asString().endsWith("Aufträge verschoben");
  }

  @Test
  void anExpressOrderAloneShiftsNothing() {
    Plant p = Plant.inMemory(7, Scenario1h.rules()); // no other orders
    assertThat(start(p, "D")).containsEntry("ok", true);
    var run = untilDone(p, 5 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).last().isEqualTo("Kein anderer Auftrag verschoben");
  }
```

Zum ersten Test: SHORTAGE ist der Zustand, in dem das Szenario besteht. Danach darf ein automatischer Nachschub (Meldebestand) den Auftrag weiterbringen; geprüft wird darum, dass er nicht schon fertig ist und der Lauf bestanden hat.

- [ ] **Step 2: Tests laufen lassen, rot**

Run: `./mvnw -B -q -pl platform/plant -am test -Dtest=ScenariosTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"`
Expected: `exit=1`, 404 für „C“ und „D“.

- [ ] **Step 3: Umsetzen**

Imports ergänzen: `io.nexus.contracts.port.ProductCatalog.MaterialDemand`, `io.nexus.flowpilot.store.StockLine`, `io.nexus.smartfactory.planning.Plan`, `java.util.Map`, `java.util.TreeMap`.

Im `switch` von `start`: `case "C" -> new Shortage();` und `case "D" -> new Express();`.

```java
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
```

- [ ] **Step 4: Tests grün, Kern bauen, ganze Plant-Suite**

Run: `./mvnw -B -q -pl platform/plant -am test; echo "exit=$?"`
Expected: `exit=0`.

Run: `cd FactoryTwin && npm run core && npx vitest run src/nexus`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add platform/plant FactoryTwin
git commit -m "feat(plant): Szenarien Materialengpass und Expressauftrag"
```

---

### Task 4: Server-Endpunkte `/api/v1/scenarios`

**Files:**
- Create: `platform/app/src/main/java/io/nexus/app/plant/ScenarioController.java`
- Modify: `platform/app/src/main/java/io/nexus/app/plant/SimController.java` (`answer` paketsichtbar)
- Test: `platform/app/src/test/java/io/nexus/app/plant/ScenarioApiTest.java`

**Interfaces:**
- Consumes: `PlantCommands.apply`, `PlantQueries.apply` (Task 1).
- Produces: `GET /api/v1/scenarios` → Antwort der Abfrage `scenarios`; `POST /api/v1/scenarios/{id}/run` → Antwort des Befehls `runScenario` (200, 404, 409 im Format `{ok,status,message}`).

- [ ] **Step 1: Failing test schreiben**

`ScenarioApiTest.java` (Muster wie `SimApiTest`):

```java
package io.nexus.app.plant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded")
class ScenarioApiTest {
  @Value("${local.server.port}") int port;
  @Autowired SimulationLoop loop;
  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<String> send(String method, String path) throws Exception {
    var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
        .method(method, method.equals("POST") && path.endsWith("/reset")
            ? HttpRequest.BodyPublishers.ofString("{\"seed\":11}") : HttpRequest.BodyPublishers.noBody());
    return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
  }

  @BeforeEach
  void freshPlant() throws Exception {
    assertThat(send("POST", "/api/v1/sim/reset").statusCode()).isEqualTo(204);
  }

  @AfterEach
  void stop() {
    loop.pause();
    loop.setSpeed(1);
  }

  @Test
  void listsAndStartsScenarios() throws Exception {
    var list = send("GET", "/api/v1/scenarios");
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body()).contains("\"id\":\"A\"").contains("\"id\":\"E\"").contains("\"runs\":[]");

    var started = send("POST", "/api/v1/scenarios/D/run");
    assertThat(started.statusCode()).isEqualTo(200);
    assertThat(started.body()).contains("\"runId\":\"SZ-1\"");

    assertThat(send("POST", "/api/v1/scenarios/E/run").statusCode()).isEqualTo(409);
    assertThat(send("POST", "/api/v1/scenarios/Z/run").statusCode()).isEqualTo(404);
  }
}
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `./mvnw -B -q -pl platform/app -am test -Dtest=ScenarioApiTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"`
Expected: `exit=1`, 404 auf `GET /api/v1/scenarios`.

- [ ] **Step 3: Umsetzen**

In `SimController` `private static ResponseEntity<String> answer(String json)` zu `static ResponseEntity<String> answer(String json)` machen.

`ScenarioController.java`:

```java
package io.nexus.app.plant;

import io.nexus.contracts.json.JsonWriter;
import io.nexus.plant.Plant;
import io.nexus.plant.PlantCommands;
import io.nexus.plant.PlantQueries;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Scenario Lab (Spec 12, 15): the core's scenarios, the same logic the browser runs in its worker. */
@RestController
@RequestMapping("/api/v1/scenarios")
class ScenarioController {
  private final Plant plant;

  ScenarioController(Plant plant) {
    this.plant = plant;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> list() {
    return SimController.answer(PlantQueries.apply(plant, "{\"query\":\"scenarios\"}"));
  }

  @PostMapping(value = "/{id}/run", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> run(@PathVariable String id) {
    String json = new JsonWriter().beginObject().name("command").value("runScenario").name("scenario").value(id)
        .endObject().toString();
    return SimController.answer(PlantCommands.apply(plant, json));
  }
}
```

- [ ] **Step 4: Test grün, App-Suite**

Run: `./mvnw -B -q -pl platform/app -am test -Dtest=ScenarioApiTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"`
Expected: `exit=0`.

- [ ] **Step 5: Commit**

```bash
git add platform/app
git commit -m "feat(app): Endpunkte für Szenarien unter /api/v1/scenarios"
```

---

### Task 5: Bereich Scenario Lab

**Files:**
- Create: `FactoryTwin/src/areas/scenarios/scenarioView.ts`, `FactoryTwin/src/areas/scenarios/ScenariosArea.tsx`
- Modify: `FactoryTwin/src/nexus/types.ts` (`ScenarioRun`, `ScenarioList`), `FactoryTwin/src/ui/format.ts` (`SCENARIO_STATE`), `FactoryTwin/src/shell/Shell.tsx`, `FactoryTwin/src/shell/areas.ts` (`scenarios`: `comesWith: 'M3'` → `null`), `FactoryTwin/src/shell/Placeholder.tsx` (Zeile `scenarios` aus `MEANWHILE`)
- Test: `FactoryTwin/src/areas/scenarios/scenarioView.test.ts`

**Interfaces:**
- Consumes: `usePlantQuery`, `send`, `useFactory(s => s.sim.running)`, `CodeTag`, `Section`, `Button`, `hrefOf`, `clock`.
- Produces:

```ts
export type ScenarioState = 'WAITING' | 'RUNNING' | 'PASSED' | 'FAILED';
export interface ScenarioRun {
  runId: string;
  scenario: string;
  state: ScenarioState;
  orderId: string | null;
  startedAtMs: number;
  finishedAtMs: number | null;
  steps: { atMs: number; text: string }[];
}
export interface ScenarioList {
  definitions: { id: string; title: string; trigger: string; expected: string; limitMinutes: number }[];
  runs: ScenarioRun[];
}
```

  `activeRun(list: ScenarioList | null): ScenarioRun | null`, `lastRunOf(list: ScenarioList | null, id: string): ScenarioRun | null`.

- [ ] **Step 1: Failing test schreiben**

`scenarioView.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { ScenarioList, ScenarioRun } from '../../nexus/types';
import { activeRun, lastRunOf } from './scenarioView';

const run = (runId: string, scenario: string, state: ScenarioRun['state']): ScenarioRun => ({
  runId,
  scenario,
  state,
  orderId: null,
  startedAtMs: 0,
  finishedAtMs: null,
  steps: [],
});
const list = (runs: ScenarioRun[]): ScenarioList => ({ definitions: [], runs });

describe('scenario view', () => {
  it('aRunningScenarioBlocksTheOthers', () => {
    expect(activeRun(null)).toBeNull();
    expect(activeRun(list([run('SZ-2', 'A', 'PASSED')]))).toBeNull();
    expect(activeRun(list([run('SZ-3', 'B', 'WAITING'), run('SZ-2', 'A', 'PASSED')]))?.runId).toBe('SZ-3');
    expect(activeRun(list([run('SZ-3', 'B', 'RUNNING')]))?.runId).toBe('SZ-3');
  });

  it('shows the newest run of each scenario', () => {
    const l = list([run('SZ-3', 'A', 'FAILED'), run('SZ-2', 'B', 'PASSED'), run('SZ-1', 'A', 'PASSED')]);
    expect(lastRunOf(l, 'A')?.runId).toBe('SZ-3');
    expect(lastRunOf(l, 'E')).toBeNull();
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/areas/scenarios`
Expected: FAIL, `./scenarioView` fehlt.

- [ ] **Step 3: Umsetzen**

Typen aus dem Interfaces-Block an `src/nexus/types.ts` anfügen.

`scenarioView.ts`:

```ts
import type { ScenarioList, ScenarioRun } from '../../nexus/types';

/** The run that still waits for its trigger or watches the plant; while it exists, no other scenario may start. */
export function activeRun(list: ScenarioList | null): ScenarioRun | null {
  return list?.runs.find((r) => r.state === 'WAITING' || r.state === 'RUNNING') ?? null;
}

/** Runs come newest first from the core. */
export function lastRunOf(list: ScenarioList | null, id: string): ScenarioRun | null {
  return list?.runs.find((r) => r.scenario === id) ?? null;
}
```

In `format.ts` anfügen:

```ts
export const SCENARIO_STATE: StateMap = {
  WAITING: { text: 'Wartet auf Auslöser', color: STATE_HEX.wait },
  RUNNING: { text: 'Läuft', color: STATE_HEX.setup },
  PASSED: { text: 'Bestanden', color: STATE_HEX.run },
  FAILED: { text: 'Nicht bestanden', color: STATE_HEX.fault },
};
```

`ScenariosArea.tsx`:

```tsx
import { useState } from 'react';
import type { ScenarioList, ScenarioRun } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { send, useFactory } from '../../store/factoryStore';
import { clock, SCENARIO_STATE } from '../../ui/format';
import { CodeTag, Section } from '../../ui/kit';
import { Button } from '../../ui/primitives';
import { activeRun, lastRunOf } from './scenarioView';

const link = 'text-[12px] text-txt-2 underline decoration-line-2 underline-offset-4 hover:text-txt-1';

function RunSteps({ run }: { run: ScenarioRun }) {
  return (
    <ol className="mt-2 flex flex-col gap-1 border-l border-line pl-3">
      {run.steps.map((s, i) => (
        <li key={i} className="grid grid-cols-[64px_minmax(0,1fr)] gap-2 text-[12px]">
          <span className="font-mono text-txt-3 tnum">{clock(s.atMs / 1000)}</span>
          <span className="text-txt-1">{s.text}</span>
        </li>
      ))}
    </ol>
  );
}

/** Spec 15: five scripted interventions, started here and watched by the core until they pass or time out. */
export function ScenariosArea() {
  const running = useFactory((s) => s.sim.running);
  const { data, reload } = usePlantQuery<ScenarioList>({ query: 'scenarios' }, 1_000);
  const active = activeRun(data);
  const [busy, setBusy] = useState(false);
  const startScenario = async (id: string) => {
    setBusy(true);
    try {
      await send({ command: 'runScenario', scenario: id }); // a refusal (409, 404) shows its reason in the notice line
      reload();
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="flex flex-col gap-10 px-4 py-6 lg:px-6 lg:py-8">
      {!running && (
        <p className="text-[13px] text-txt-2">
          Die Simulation ist pausiert. Szenarien laufen in der Simulationszeit; starte die Simulation oben, Tempo 25× verkürzt das Warten.
        </p>
      )}
      <Section title="Szenarien">
        {!data ? (
          <p className="text-[13px] text-txt-3">Szenarien werden geladen …</p>
        ) : (
          <ul className="flex flex-col">
            {data.definitions.map((d) => {
              const last = lastRunOf(data, d.id);
              return (
                <li
                  key={d.id}
                  aria-label={`Szenario ${d.id}: ${d.title}`}
                  className="grid grid-cols-1 gap-x-6 gap-y-2 border-b border-line py-4 last:border-b-0 lg:grid-cols-[minmax(0,1fr)_200px_auto] lg:items-start"
                >
                  <div className="min-w-0">
                    <h3 className="text-[15px] font-semibold text-txt-1">
                      <span className="mr-2 font-mono text-txt-3">{d.id}</span>
                      {d.title}
                    </h3>
                    <p className="mt-1 text-[13px] text-txt-2">Auslöser: {d.trigger}</p>
                    <p className="text-[13px] text-txt-2">Erwartet: {d.expected}</p>
                    <p className="text-[12px] text-txt-3">Zeitgrenze {d.limitMinutes} min Simulationszeit</p>
                  </div>
                  <div className="flex flex-col gap-1">
                    {last ? <CodeTag map={SCENARIO_STATE} code={last.state} /> : <span className="text-[12px] text-txt-3">Noch nicht gelaufen</span>}
                    {last?.orderId && (
                      <span className="flex gap-3">
                        <a href={hrefOf('events', last.orderId)} className={link}>
                          Ereignisse
                        </a>
                        <a href={hrefOf('smartfactory', last.orderId)} className={link}>
                          Auftrag {last.orderId}
                        </a>
                      </span>
                    )}
                  </div>
                  <Button onClick={() => void startScenario(d.id)} disabled={busy || active !== null}>
                    Starten
                  </Button>
                </li>
              );
            })}
          </ul>
        )}
      </Section>
      <Section title="Verlauf">
        {!data || data.runs.length === 0 ? (
          <p className="text-[13px] text-txt-3">Noch kein Szenario gelaufen.</p>
        ) : (
          <ul className="flex flex-col">
            {data.runs.map((r) => {
              const d = data.definitions.find((x) => x.id === r.scenario);
              return (
                <li key={r.runId} aria-label={`Lauf ${r.runId}`} className="border-b border-line py-3 last:border-b-0">
                  <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
                    <span className="font-mono text-[12px] text-txt-3">{r.runId}</span>
                    <span className="text-[13px] text-txt-1">
                      {r.scenario} {d?.title}
                    </span>
                    <CodeTag map={SCENARIO_STATE} code={r.state} />
                    <span className="font-mono text-[12px] text-txt-3 tnum">ab {clock(r.startedAtMs / 1000)}</span>
                  </div>
                  <RunSteps run={r} />
                </li>
              );
            })}
          </ul>
        )}
      </Section>
    </div>
  );
}
```

`Shell.tsx`: `case 'scenarios': return <ScenariosArea />;` (Import `import { ScenariosArea } from '../areas/scenarios/ScenariosArea';`). `areas.ts`: beim Eintrag `scenarios` `comesWith: 'M3'` → `comesWith: null`. `Placeholder.tsx`: Zeile `scenarios: { … }` aus `MEANWHILE` löschen.

- [ ] **Step 4: Tests, Typprüfung, Sichtprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS.

Sichtprüfung (`twin-browser`, Seite neu laden nach `npm run core`): Simulation 25×, Szenario D starten → „Bestanden“ mit verschobenen Aufträgen, Knöpfe währenddessen gesperrt; Szenario E starten und ein zweites versuchen → Knöpfe gesperrt; „Ereignisse“ öffnet `#/events/PO-…` mit `scenario.started` und `scenario.finished`.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): Scenario Lab mit Start, Zustand und Schrittfolge je Szenario"
```

---

### Task 6: Playwright – Szenarien A bis E in beiden Betriebsarten, Bildrate

**Files:**
- Modify: `FactoryTwin/package.json` (Entwicklungsabhängigkeit `@playwright/test`, Skripte `e2e`, `e2e:server`), `FactoryTwin/vite.config.ts` (Vitest ignoriert `e2e/`), `FactoryTwin/.gitignore` (`test-results/`, `playwright-report/`)
- Create: `FactoryTwin/playwright.config.ts`, `FactoryTwin/playwright.server.config.ts`, `FactoryTwin/e2e/scenarios.spec.ts`, `FactoryTwin/e2e/fps.spec.ts`

**Interfaces:**
- Consumes: Bereich Scenario Lab (Task 5): Zeilen `aria-label="Szenario <id>: <Titel>"` mit Knopf „Starten“ und Zustandswort „Bestanden“, Link „Ereignisse“; Simulationsleiste: Knöpfe „Start“, „25×“, „Reset: Simulation zurücksetzen“, „Ja“.
- Produces: `npm run e2e` (Browser-Modus) und `npm run e2e:server` (Server-Modus); M3-B ruft beide in CI auf.

- [ ] **Step 1: Playwright einrichten**

Run: `cd FactoryTwin && npm install --save-dev @playwright/test`
Expected: `@playwright/test` in `devDependencies`. Kein `npx playwright install`: lokal läuft der installierte Chrome.

`package.json`, in `scripts`:

```json
    "e2e": "playwright test",
    "e2e:server": "playwright test -c playwright.server.config.ts"
```

`vite.config.ts`: `import { configDefaults, defineConfig } from 'vitest/config';` und `test: { environment: 'node', exclude: [...configDefaults.exclude, 'e2e/**'] },`.

`.gitignore` in `FactoryTwin` um `test-results/` und `playwright-report/` ergänzen (Datei anlegen, falls es sie nicht gibt).

`playwright.config.ts`:

```ts
import { defineConfig } from '@playwright/test';

/** Shared by both runtimes. One worker: every test resets the one plant it talks to. */
export const base = defineConfig({
  testDir: 'e2e',
  timeout: 8 * 60_000,
  expect: { timeout: 15_000 },
  workers: 1,
  reporter: [['list']],
  use: {
    channel: process.env.CI ? undefined : 'chrome', // locally the installed Chrome, no browser download
    viewport: { width: 1440, height: 900 },
  },
});

/** Browser runtime: the Java core in the page's web worker (Spec 14, GitHub Pages mode). */
export default defineConfig({
  ...base,
  use: { ...base.use, baseURL: 'http://localhost:5173' },
  webServer: { command: 'npm run dev -- --port 5173 --strictPort', url: 'http://localhost:5173', reuseExistingServer: true, timeout: 120_000 },
});
```

`playwright.server.config.ts`:

```ts
import { defineConfig } from '@playwright/test';
import { base } from './playwright.config';

/** Server runtime: Spring Boot with PostgreSQL and OPC UA on 8080, the interface in server mode on 5179. */
export default defineConfig({
  ...base,
  testIgnore: ['fps.spec.ts'], // the frame rate is a property of the interface, measured once in browser mode
  use: { ...base.use, baseURL: 'http://localhost:5179' },
  webServer: [
    { command: 'node scripts/run-server.mjs', url: 'http://localhost:8080/api/v1/sim', reuseExistingServer: true, timeout: 300_000 },
    { command: 'npm run dev -- --mode server --port 5179 --strictPort', url: 'http://localhost:5179', reuseExistingServer: true, timeout: 120_000 },
  ],
});
```

- [ ] **Step 2: Szenario-Tests schreiben**

`e2e/scenarios.spec.ts`:

```ts
import { expect, test, type Page } from '@playwright/test';

/** A fresh run of the plant at 25×, so every scenario starts from the same seed and state. */
async function freshPlant(page: Page) {
  await page.goto('/#/scenarios');
  await expect(page.getByText(/(Browser|Server) · verbunden/)).toBeVisible({ timeout: 60_000 });
  await page.getByRole('button', { name: /^Reset/ }).click();
  await page.getByRole('button', { name: 'Ja', exact: true }).click();
  await page.getByRole('button', { name: '25×', exact: true }).click();
  await page.getByRole('button', { name: 'Start', exact: true }).click();
}

const SCENARIOS: [id: string, title: string][] = [
  ['A', 'Maschinenausfall'],
  ['B', 'AGV-Ausfall'],
  ['C', 'Materialengpass'],
  ['D', 'Expressauftrag'],
  ['E', 'Kompletter Durchlauf'],
];

for (const [id, title] of SCENARIOS) {
  test(`Szenario ${id}: ${title}`, async ({ page }) => {
    test.setTimeout(id === 'E' ? 20 * 60_000 : 8 * 60_000);
    await freshPlant(page);
    const row = page.getByRole('listitem', { name: `Szenario ${id}: ${title}` });
    await row.getByRole('button', { name: 'Starten' }).click();
    // E needs up to six simulated hours: at 25× that is under 15 real minutes, usually far less
    await expect(row.getByText('Bestanden', { exact: true })).toBeVisible({ timeout: id === 'E' ? 15 * 60_000 : 6 * 60_000 });

    // the chain of the scenario in the event monitor, filtered by its order
    await row.getByRole('link', { name: 'Ereignisse' }).click();
    await expect(page).toHaveURL(/#\/events\/PO-/);
    await expect(page.getByRole('cell', { name: 'scenario.started' })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'scenario.finished' })).toBeVisible();
  });
}
```

- [ ] **Step 3: Bildrate messen**

`e2e/fps.spec.ts`:

```ts
import { expect, test } from '@playwright/test';

/**
 * Spec 14: 60 frames per second in the 3D view at 25×, on a usual laptop. Headless Chrome renders without a GPU, so
 * the test only measures and reports; it enforces a floor only when FPS_MIN is set (run headed on the laptop).
 */
test('FactoryTwin bei 25-fachem Tempo', async ({ page }, info) => {
  await page.goto('/#/twin');
  await expect(page.getByText(/Browser · verbunden/)).toBeVisible({ timeout: 60_000 });
  await page.getByRole('button', { name: '25×', exact: true }).click();
  await page.getByRole('button', { name: 'Start', exact: true }).click();
  await page.waitForTimeout(5_000); // let the scene load and the plant fill up
  const fps = await page.evaluate(
    () =>
      new Promise<number>((resolve) => {
        let frames = 0;
        const t0 = performance.now();
        const tick = () => {
          frames++;
          const dt = performance.now() - t0;
          if (dt < 5_000) requestAnimationFrame(tick);
          else resolve((frames * 1000) / dt);
        };
        requestAnimationFrame(tick);
      }),
  );
  info.annotations.push({ type: 'fps', description: fps.toFixed(1) });
  console.log(`FactoryTwin bei 25×: ${fps.toFixed(1)} Bilder/s`);
  expect(fps).toBeGreaterThanOrEqual(Number(process.env.FPS_MIN ?? 0));
});
```

- [ ] **Step 4: Laufen lassen, beide Betriebsarten**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit` (Vitest darf `e2e/` nicht anfassen)
Expected: PASS.

Run (im Hintergrund, die Ausgabe in eine Datei): `cd FactoryTwin && npm run e2e`
Expected: 6 passed (A–E und Bildrate).

Run: laufende Server von Hand stoppen (Vorschau-Server, `java`, eingebettetes `postgres` aus früheren Läufen), dann `cd FactoryTwin && npm run e2e:server`
Expected: 5 passed (A–E).

Run (sichtbar, auf diesem Rechner): `cd FactoryTwin && npx playwright test fps --headed`
Expected: eine Zeile „FactoryTwin bei 25×: … Bilder/s“. Den Wert für die README notieren; liegt er deutlich unter 60, ist das ein Befund fürs Ledger (Spec 14), kein Fehlschlag dieses Tasks.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/package.json FactoryTwin/package-lock.json FactoryTwin/vite.config.ts FactoryTwin/.gitignore FactoryTwin/playwright.config.ts FactoryTwin/playwright.server.config.ts FactoryTwin/e2e
git commit -m "test(e2e): Playwright für die Szenarien A bis E in beiden Betriebsarten und die Bildrate"
```

---

### Task 7: Feinschliff, README, ganze Prüfung

**Files:**
- Modify: Dateien aus Task 5 nach den Befunden; `FactoryTwin/README.md`
- Test: bestehende Suiten

- [ ] **Step 1: Design-Prüfung** mit dem Skill `impeccable` (`critique` des Bereichs Scenario Lab). Befunde, die Spec 14/15 oder den Global Constraints widersprechen, beheben; Geschmacksfragen ins Ledger als „minor (deferred)“.

- [ ] **Step 2: Barrierefreiheit:** axe-core auf `#/scenarios` (vor und nach einem Szenariolauf); `serious` und `critical` beheben. Per Tastatur: Szenario starten, Link „Ereignisse“ erreichen.

- [ ] **Step 3: Sichtprüfung** bei 1440 × 900, 1024 × 768 und 390 × 844 in beiden Betriebsarten: kein seitliches Überlaufen, Schrittfolge lesbar, gesperrte Knöpfe während eines Laufs.

- [ ] **Step 4: README**
  - Tabelle „Bereiche“: Scenario Lab auf „fertig“ mit einem Halbsatz Inhalt.
  - Neuer Abschnitt „Scenario Lab“: die fünf Szenarien mit Auslöser, Erwartung und Zeitgrenze. Dazu der Hinweis, dass C Bestand abbucht und ein Reset alle Läufe vergisst.
  - Neuer Abschnitt „End-to-End-Tests“: `npm run e2e` und `npm run e2e:server`, der installierte Chrome lokal, die Laufzeit und dass die Server-Variante Java 25 braucht.
  - Bildrate: der gemessene Wert mit Datum, Rechner (allgemein, ohne persönliche Angaben), Browser und Messweg (`npx playwright test fps --headed`).
  - Endpunkte: `GET /api/v1/scenarios`, `POST /api/v1/scenarios/{id}/run`.

- [ ] **Step 5: Ganze Prüfung und Commit**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit && npx vite build`, dann im Wurzelverzeichnis `./mvnw -B verify` (vorher `java`- und eingebettete `postgres`-Prozesse aus den E2E-Läufen beenden; den Exitcode von Maven prüfen).
Expected: alles grün.

```bash
git add FactoryTwin
git commit -m "feat(ui): Feinschliff Scenario Lab, README mit Szenarien, E2E und Bildrate"
```

---

## Nicht in diesem Plan

- **M3-B:** CI mit GitHub Actions (Backend, Frontend, Playwright gegen das gestartete Backend), Docker Compose (nur in CI prüfbar, Spec 20), GitHub Pages im Browser-Modus, README mit Architekturdiagramm.
- **Erweiterte Szenarien mit Lieferlogistik** und der Vergleich „ursprünglicher gegen neuer Plan“ für Produktion und Lieferung: M4 (Spec 15a).
- **Mehrere Szenarien gleichzeitig:** bewusst nicht; eine Kette je Szenario bleibt eindeutig.
