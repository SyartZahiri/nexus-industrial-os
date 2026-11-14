# M2-A Datenweg und 3D-Twin auf dem Java-Kern – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FactoryTwin läuft nicht mehr auf seiner eigenen TypeScript-Engine, sondern auf dem Java-Kern: im Modus `browser` über das TeaVM-Modul in einem Web Worker, im Modus `server` über REST und WebSocket. Die 3D-Halle, die Kopfleiste, das Ereignisprotokoll und die Detailansicht mit Befehlen funktionieren in beiden Modi gleich.

**Architecture:** Die Oberfläche spricht nur mit der Schnittstelle `NexusClient` (Spec 14) und bekommt von ihr dieselben vier Nachrichten: `frame`, `events`, `sim`, `reset`. Ein Twin-Laufzeitobjekt hält den letzten und vorletzten Frame und interpoliert dazwischen. Ein Szenenmodell bildet den Frame auf die Formen ab, die die bestehenden 3D-Komponenten lesen, damit `src/scene` fast unverändert bleibt. Befehle und Abfragen haben in beiden Modi dasselbe JSON, ausgeführt vom selben Kern (`PlantCommands`, neu `PlantQueries`).

**Tech Stack:** Java 25, Spring Boot 4.1.2, TeaVM 0.16.0; React 19, TypeScript 7, Vite 8, Vitest 5, React Three Fiber 9, Zustand 5.

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Revision 3), Abschnitte 2a (letzte Regel), 3, 12, 14. Voraussetzung: M1 ist gemergt (`0736c8d`).

**Form:** wie M1-D2. Vollständiger Code für alles, was neu und knifflig ist (Takt im Worker, Wiederverbinden, Interpolation, Szenenmodell, Kernabfragen). Für die Umstellung bestehender Szenendateien eine genaue Liste der Ersetzungen.

## M2 in drei Plänen

Das Frontend rechnet heute mit einer eigenen Engine, die einzelne Werkstücke, Puffer je Maschine und vier gefüllte Förderbänder kennt. Der Java-Kern arbeitet mit Losen (ein Behälter je Arbeitsgang) und nur `CV-03` trägt Teile. M2 wird deshalb geteilt:

- **M2-A (dieser Plan):** Datenweg und 3D-Twin. `NexusClient` in beiden Modi, Worker, Twin, Szenenmodell. Die alte Engine und `src/integration` entfallen. Von den Panels bleiben Kopfleiste, Ereignisprotokoll und Detailansicht, angepasst an den Frame. Aufträge, Kennzahlen, Regeln und Integration entfallen vorerst; M2-B und M2-C bauen sie im neuen Designsystem neu.
- **M2-B:** Shell, Hash-Routing für die elf Bereiche, Designsystem (Spec 14), Overview, FactoryTwin, Event Monitor, Settings.
- **M2-C:** SmartFactory, FlowPilot, Machines, Warehouse, Analytics; RouteMind als Platzhalter bis M4.

## Entscheidungen dieses Plans

- **Ein Befehlsformat für beide Modi.** Der Server bekommt `POST /api/v1/sim/commands` und `POST /api/v1/sim/queries`, die genau das JSON von `PlantCommands` und dem neuen `PlantQueries` annehmen und beantworten. Die bestehenden REST-Endpunkte bleiben für andere Clients und für Swagger. So prüft der Kern einen Befehl in beiden Modi gleich (Spec 14: „vom selben Kern validiert").
- **Simulationszustand als Nachricht.** Der WebSocket sendet zusätzlich `{"type":"sim","running":…,"speed":…,"seed":…}` beim Verbinden und bei jeder Änderung; der Worker sendet dieselbe Nachricht. Kein Heartbeat: ein toter Server zeigt sich am `close`-Ereignis des WebSockets.
- **Hallengeometrie bleibt im Frontend.** `src/sim/layout.ts`, `src/sim/config.ts` und die Maschinen-, Band- und Ladeplatz-Definitionen aus `src/sim/initialState.ts` sind reine Zeichendaten und ziehen nach `src/twin/plantLayout.ts` und `src/twin/drawing.ts`. Die Fachdaten dazu (Knoten, Kanten, Maschinen) stehen in FlowPilot und SmartFactory; ein Abgleich-Test sichert, dass die IDs übereinstimmen.
- **Szenenmodell statt Szenen-Neubau.** `toScene(frame)` liefert Objekte mit den Feldnamen, die `src/scene` heute liest (`m.job.progress`, `a.pos.x`, `c.items`, `inv.raw` …). Felder ohne Gegenstück im Kern bekommen einen festen Wert: `lastVerdict = null`, `inBuffer`/`outBuffer` leer, Bänder ausser `CV-03` laufen leer, `blockedBy = null`.
- **Takt im Worker** wie `SimulationLoop` auf dem Server: alle 50 ms Echtzeit, Tempo 0,25 bis 25, höchstens 50 Schritte je Takt, Rest verworfen. Der Worker sendet nach jedem Takt mit Fortschritt einen Frame, also bis zu 20 je Sekunde.
- **Ereignisse im Browser über die Position.** Neuer Export `plantEventsFrom(int)` liefert die Ereignisse ab einer Position, jedes mit `seq` = Position + 1, wie der Server. Der bestehende `plantEvents(afterEventId)` durchsucht jedes Mal alle Ereignisse und bleibt nur für die Node-Tests.
- **Modus über `VITE_NEXUS_MODE`**, Standard `browser`. Im Modus `server` leitet der Vite-Dev-Server `/api` und `/ws` an `http://localhost:8080` weiter.

## Global Constraints

- Alles aus `2026-11-10-m1a2-portabler-kern.md`, Abschnitt Global Constraints, gilt weiter, insbesondere: Fachlogik nur in Java; TypeScript enthält keine Geschäftsregeln.
- `platform/plant` bleibt Kern (kein Spring, keine Threads, keine Systemzeit); `PlantQueries` liegt dort.
- Frontend: TypeScript strikt (`tsconfig.json` wie heute), keine neuen Laufzeitabhängigkeiten. Vitest für Tests.
- Texte für Benutzer auf Deutsch, Code und Kommentare auf Englisch.
- Das TeaVM-Modul liegt nach `./mvnw -B -pl platform/web -am package -DskipTests` unter `platform/web/target/javascript/nexus-core.js`; das Frontend importiert es über den Alias `@nexus-core`.
- Commits enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Nicht pushen.

## Review Focus

1. **Tab im Hintergrund.** Browser drosseln Timer in Hintergrund-Tabs; kommt der Tab zurück, darf der Worker nicht Minuten nachholen und die Oberfläche einfrieren. Erwartet: höchstens 50 Schritte je Takt, der Rest verfällt. Test in Task 2 (`aLongPauseOfTheTimerRunsAtMostFiftySteps`).
2. **Server neu gestartet oder Reset von anderer Stelle.** Der WebSocket meldet `reset`; die Oberfläche verwirft Ereignisse und Frames des alten Laufs und zeigt keine Mischung. Test in Task 3 (`aResetDropsTheOldRun`) und Task 4 (`resetForgetsBothFrames`).
3. **Verbindung reisst ab.** Der Client verbindet mit Backoff 1 s bis 30 s neu und holt verpasste Ereignisse über `GET /api/v1/events?after=<seq>`; kein Ereignis doppelt, keins fehlt. Test in Task 3 (`aReconnectFillsTheGapWithoutDuplicates`).
4. **Befehl wird abgelehnt.** Ein ungültiger Befehl (Maschine im falschen Zustand) liefert in beiden Modi dieselbe deutsche Meldung und denselben Status; die Oberfläche zeigt sie an, statt still nichts zu tun. Test in Task 1 (Server) und Task 2 (Worker).
5. **AGV-Sprung.** Kommt ein Frame zu spät oder fehlt einer, springt kein AGV quer durch die Halle: die Interpolation endet beim letzten Frame, sie extrapoliert nicht. Test in Task 4 (`interpolationStopsAtTheLatestFrame`).

---

### Task 1: Befehle und Abfragen über denselben Kern, Simulationszustand im WebSocket

**Files:**
- Create: `platform/plant/src/main/java/io/nexus/plant/PlantQueries.java`
- Modify: `platform/plant/src/main/java/io/nexus/plant/PlantCommands.java` (Hilfsmethoden `error`, `text`, `whole` paketintern statt privat)
- Modify: `platform/web/src/main/java/io/nexus/web/WebRuntime.java` (Exporte `plantEventsFrom`, `plantQuery`)
- Modify: `platform/app/src/main/java/io/nexus/app/plant/SimController.java` (Endpunkte `commands`, `queries`)
- Modify: `platform/app/src/main/java/io/nexus/app/plant/PlantSocket.java`, `SocketConfig.java` (Nachricht `sim`)
- Test: `platform/plant/src/test/java/io/nexus/plant/PlantQueriesTest.java`, `platform/web/src/test/java/io/nexus/web/WebRuntimeTest.java` (erweitern), `platform/app/src/test/java/io/nexus/app/plant/SimApiTest.java` (erweitern), `PlantSocketTest.java` (erweitern)

**Interfaces:**
- Produces:
  - `PlantQueries.apply(Plant plant, String json) → String`. Antwort wie `PlantCommands`: `{"ok":true,"result":…}` oder `{"ok":false,"status":400|404|409|500,"error":"…"}`.
  - Abfragen: `{"query":"kpis","windowMinutes":60}` (1 bis 1440, Standard 60) → `{"fromMs","toMs","throughputPerHour","avgLeadTimeS","p90LeadTimeS","onTimeRate","bottleneckMachineId","machines":{"<id>":{"utilization","availability","performance","quality","oee","timeInStatusMs":{"<STATUS>":ms}}}}`; `{"query":"rules"}` → `{"randomFaults","autoRepair","autoMaintenance","maintenanceThreshold","autoOrders","autoOrderIntervalMinutes","maxOpenOrders","shipping","dispatchRule"}`.
  - `WebRuntime.plantEventsFrom(int from) → String` (JSON-Array, je Ereignis zuerst `"seq":position+1`), `WebRuntime.plantQuery(String json) → String`.
  - `POST /api/v1/sim/commands` und `POST /api/v1/sim/queries`: Body ist das Befehls- bzw. Abfrage-JSON, Antwort ist die Kern-Antwort unverändert, HTTP-Status = `status` der Antwort, sonst 200.
  - WebSocket-Nachricht `{"type":"sim","running":true,"speed":1.0,"seed":4711}` beim Verbinden und bei jeder Änderung.

- [ ] **Step 1: Failing tests schreiben**

`platform/plant/src/test/java/io/nexus/plant/PlantQueriesTest.java`:

```java
package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.json.JsonReader;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlantQueriesTest {
  @SuppressWarnings("unchecked")
  private static Map<String, Object> answer(Plant plant, String json) {
    return (Map<String, Object>) JsonReader.parse(PlantQueries.apply(plant, json));
  }

  @Test
  @SuppressWarnings("unchecked")
  void kpisCoverEveryMachineOfTheWindow() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    plant.advance(30 * 60_000);
    var a = answer(plant, "{\"query\":\"kpis\",\"windowMinutes\":30}");
    assertThat(a).containsEntry("ok", true);
    var result = (Map<String, Object>) a.get("result");
    assertThat(result).containsKeys("throughputPerHour", "onTimeRate", "bottleneckMachineId");
    assertThat((Map<String, Object>) result.get("machines")).containsOnlyKeys("CNC-01", "CNC-02", "CNC-03", "CNC-04",
        "QC-01", "ROB-01", "ROB-02");
    assertThat(result).containsEntry("toMs", 1_800_000L).containsEntry("fromMs", 0L);
  }

  @Test
  void rulesAreTheCurrentOnes() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(PlantQueries.apply(plant, "{\"query\":\"rules\"}"))
        .contains("\"autoOrderIntervalMinutes\":6").contains("\"dispatchRule\":\"PRIORITY\"");
  }

  @Test
  void aBadQueryIsAnsweredLikeABadCommand() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(answer(plant, "{\"query\":\"kpis\",\"windowMinutes\":0}")).containsEntry("ok", false)
        .containsEntry("status", 400L);
    assertThat(answer(plant, "{\"query\":\"geheim\"}")).containsEntry("status", 400L);
    assertThat(answer(plant, "kein json")).containsEntry("status", 400L);
  }
}
```

In `WebRuntimeTest` ergänzen:

```java
  @Test
  void eventsFromAPositionCarryTheirSeq() {
    WebRuntime.plantStart(3);
    WebRuntime.plantAdvance(10 * 60_000);
    String all = WebRuntime.plantEventsFrom(0);
    String later = WebRuntime.plantEventsFrom(2);
    assertThat(all).startsWith("[{\"seq\":1,");
    assertThat(later).startsWith("[{\"seq\":3,");
    assertThat(WebRuntime.plantQuery("{\"query\":\"rules\"}")).contains("\"ok\":true");
  }
```

In `SimApiTest` ergänzen:

```java
  @Test
  void commandsAndQueriesUseTheCoresFormat() throws Exception {
    var refused = send("POST", "/api/v1/sim/commands", "{\"command\":\"machineRepair\",\"machineId\":\"CNC-01\"}");
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body()).contains("\"ok\":false").contains("CNC-01");
    var created = send("POST", "/api/v1/sim/commands",
        "{\"command\":\"createOrder\",\"productId\":\"FP12\",\"quantity\":1,\"priority\":\"NORMAL\",\"dueInMinutes\":60}");
    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(created.body()).contains("\"ok\":true");
    var rules = send("POST", "/api/v1/sim/queries", "{\"query\":\"rules\"}");
    assertThat(rules.statusCode()).isEqualTo(200);
    assertThat(rules.body()).contains("\"dispatchRule\":\"PRIORITY\"");
  }
```

Die Felder von `createOrder` (`productId`, `quantity`, `priority` mit `NORMAL`/`HOCH`/`EILIG`, `dueInMinutes`) sind die von `PlantCommands`.

In `PlantSocketTest` ergänzen:

```java
  @Autowired SimulationLoop loop;

  @Test
  void theSimulationStateIsSentOnConnectAndOnChange() throws Exception {
    var client = new Client().connect(null);
    assertThat(client.next("sim")).containsEntry("running", false).containsEntry("seed", 5L);
    loop.setSpeed(5);
    assertThat(client.next("sim").get("speed")).isEqualTo(5.0);
    loop.setSpeed(1);
    client.session.close();
  }
```

- [ ] **Step 2: Tests laufen lassen, rot**

Run: `./mvnw -B -pl platform/plant,platform/web,platform/app -am test -Dtest='PlantQueriesTest,WebRuntimeTest,SimApiTest,PlantSocketTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Kompilierfehler (`PlantQueries`, `plantEventsFrom` fehlen).

- [ ] **Step 3: `PlantQueries` anlegen**

In `PlantCommands` die Methoden `error(int, String)`, `text(Map, String)` und `whole(Map, String)` von `private static` auf paketintern `static` ändern.

`platform/plant/src/main/java/io/nexus/plant/PlantQueries.java`:

```java
package io.nexus.plant;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.json.JsonReader;
import io.nexus.contracts.json.JsonWriter;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.smartfactory.analytics.Kpis;
import java.util.Map;

/**
 * Read-only questions to the plant as JSON, answered like PlantCommands. Server and browser run the same code, so
 * the user interface gets the same answer in both runtimes.
 */
public final class PlantQueries {
  private static final long MINUTE_MS = 60_000;

  private PlantQueries() {}

  public static String apply(Plant plant, String json) {
    try {
      Object parsed = JsonReader.parse(json);
      if (!(parsed instanceof Map<?, ?> raw)) throw DomainException.invalid("Abfrage muss ein JSON-Objekt sein");
      @SuppressWarnings("unchecked") Map<String, Object> q = (Map<String, Object>) raw;
      String result = switch (PlantCommands.text(q, "query")) {
        case "kpis" -> kpis(plant, q);
        case "rules" -> rules(plant.rules());
        default -> throw DomainException.invalid("Unbekannte Abfrage");
      };
      return new JsonWriter().beginObject().name("ok").value(true).name("result").raw(result).endObject().toString();
    } catch (DomainException e) {
      return PlantCommands.error(switch (e.kind()) {
        case NOT_FOUND -> 404;
        case INVALID -> 400;
        case CONFLICT -> 409;
      }, e.getMessage());
    } catch (RuntimeException e) {
      String type = e.getClass().getName();
      return PlantCommands.error(500, "Interner Fehler (" + type.substring(type.lastIndexOf('.') + 1) + ")");
    }
  }

  private static String kpis(Plant plant, Map<String, Object> q) {
    int window = q.containsKey("windowMinutes") ? PlantCommands.whole(q, "windowMinutes") : 60;
    if (window < 1 || window > 1440) throw DomainException.invalid("windowMinutes muss zwischen 1 und 1440 liegen");
    long now = plant.nowMs();
    Kpis k = plant.smartFactory().kpis(Math.max(0, now - window * MINUTE_MS), now);
    var w = new JsonWriter().beginObject().name("fromMs").value(k.fromMs()).name("toMs").value(k.toMs())
        .name("throughputPerHour").value(k.throughputPerHour()).name("avgLeadTimeS").value(k.avgLeadTimeS())
        .name("p90LeadTimeS").value(k.p90LeadTimeS()).name("onTimeRate").value(k.onTimeRate())
        .name("bottleneckMachineId").value(k.bottleneckMachineId()).name("machines").beginObject();
    for (var e : k.machines().entrySet()) {
      Kpis.MachineKpi m = e.getValue();
      w.name(e.getKey()).beginObject().name("utilization").value(m.utilization())
          .name("availability").value(m.availability()).name("performance").value(m.performance())
          .name("quality").value(m.quality()).name("oee").value(m.oee()).name("timeInStatusMs").beginObject();
      for (MachineStatus s : MachineStatus.values()) w.name(s.name()).value(m.timeInStatusMs().get(s));
      w.endObject().endObject();
    }
    return w.endObject().endObject().toString();
  }

  private static String rules(PlantRules r) {
    return new JsonWriter().beginObject()
        .name("randomFaults").value(r.machines().randomFaults()).name("autoRepair").value(r.machines().autoRepair())
        .name("autoMaintenance").value(r.machines().autoMaintenance())
        .name("maintenanceThreshold").value(r.machines().maintenanceThreshold())
        .name("autoOrders").value(r.autoOrders()).name("autoOrderIntervalMinutes").value(r.autoOrderIntervalMs() / MINUTE_MS)
        .name("maxOpenOrders").value(r.maxOpenOrders()).name("shipping").value(r.shipping())
        .name("dispatchRule").value(r.dispatchRule().name()).endObject().toString();
  }
}
```

Die Zeitfenster-Grenze `Math.max(0, …)` sorgt dafür, dass am Anfang eines Laufs `fromMs` 0 ist (Test: 30 min Fenster nach 30 min Lauf → `fromMs` 0).

- [ ] **Step 4: Browser-Exporte**

In `WebRuntime.java` nach `plantEvents`:

```java
  /** Events of the run from position from on (0 = all), each with its position + 1 as seq, as the server sends them. */
  @JSExport
  public static String plantEventsFrom(int from) {
    List<NexusEvent> all = running().events();
    var w = new JsonWriter().beginArray();
    for (int i = Math.max(0, from); i < all.size(); i++) {
      String json = PlantJson.event(all.get(i), true);
      w.raw("{\"seq\":" + (i + 1) + "," + json.substring(1));
    }
    return w.endArray().toString();
  }

  /** A read-only question as JSON (see PlantQueries); the answer is JSON too. */
  @JSExport
  public static String plantQuery(String json) {
    return PlantQueries.apply(running(), json);
  }
```

Importe `io.nexus.contracts.json.JsonWriter` und `io.nexus.plant.PlantQueries` ergänzen.

- [ ] **Step 5: Server-Endpunkte und `sim`-Nachricht**

In `SimController.java` (Importe `org.springframework.http.ResponseEntity`, `io.nexus.contracts.json.JsonReader`, `io.nexus.plant.PlantCommands`, `io.nexus.plant.PlantQueries`, `java.util.Map`):

```java
  /** A command in the core's JSON (see PlantCommands): the same format and checks as in the browser. */
  @PostMapping(value = "/commands", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> command(@RequestBody String json) {
    return answer(PlantCommands.apply(plant, json));
  }

  /** A read-only question in the core's JSON (see PlantQueries). */
  @PostMapping(value = "/queries", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> query(@RequestBody String json) {
    return answer(PlantQueries.apply(plant, json));
  }

  /** The core's answer unchanged; its status field, if any, becomes the HTTP status. */
  private static ResponseEntity<String> answer(String json) {
    Object status = ((Map<?, ?>) JsonReader.parse(json)).get("status");
    return ResponseEntity.status(status instanceof Long s ? s.intValue() : 200)
        .contentType(MediaType.APPLICATION_JSON).body(json);
  }
```

`PlantSocket`: Konstruktor bekommt zusätzlich `SimulationLoop loop` (Feld). Neues Feld `private String sentSim;`. In `afterConnectionEstablished` im `synchronized (this)`-Block `sentSim = null;` neben `sentFrame = null;`. In `broadcast()`, im `synchronized (bus)`-Block:

```java
      String sim = new JsonWriter().beginObject().name("type").value("sim").name("running").value(loop.running())
          .name("speed").value(loop.speed()).name("seed").value(run.seed()).endObject().toString();
      if (!sim.equals(sentSim)) simMessage = sim;
```

(`String simMessage = null;` vor dem Block deklarieren) und nach dem Senden von `reset`: `if (simMessage != null) { send(simMessage); sentSim = simMessage; }`. Bei einem neuen Lauf (`currentRun != sentRun`) auch `sentSim = null` setzen, damit nach `reset` der Zustand folgt. In `SocketConfig.Beans.plantSocket` den Parameter `SimulationLoop loop` ergänzen und durchreichen.

- [ ] **Step 6: Tests laufen lassen, grün**

Run: `./mvnw -B -pl platform/plant,platform/web,platform/app -am test -Dtest='PlantQueriesTest,WebRuntimeTest,SimApiTest,PlantSocketTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

Run: `./mvnw -B verify`
Expected: BUILD SUCCESS (Node-Vergleich JS==JVM unverändert grün).

- [ ] **Step 7: Commit**

```bash
git add platform
git commit -m "feat: Befehle und Abfragen im Kernformat für beide Laufzeiten, Simulationszustand im WebSocket"
```

---

### Task 2: `NexusClient` im Browser-Modus (Worker mit dem TeaVM-Modul)

**Files:**
- Modify: `FactoryTwin/vite.config.ts` (Alias `@nexus-core`, Worker-Format `es`, Vitest-Umgebung `node`)
- Modify: `FactoryTwin/package.json` (Skript `core`)
- Create: `FactoryTwin/src/nexus/types.ts`, `FactoryTwin/src/nexus/client.ts`, `FactoryTwin/src/nexus/loop.ts`, `FactoryTwin/src/nexus/core.d.ts`, `FactoryTwin/src/nexus/browser/engine.ts`, `FactoryTwin/src/nexus/browser/nexus.worker.ts`, `FactoryTwin/src/nexus/browser/BrowserClient.ts`
- Test: `FactoryTwin/src/nexus/loop.test.ts`, `FactoryTwin/src/nexus/browser/engine.test.ts`

**Interfaces:**
- Consumes: Exporte des TeaVM-Moduls `plantStart(seed)`, `plantAdvance(ms)`, `plantFrame()`, `plantEventsFrom(from)`, `plantCommand(json)`, `plantQuery(json)` (Task 1).
- Produces (`src/nexus/types.ts`):

```ts
export type MachineStatus =
  | 'IDLE' | 'SETUP' | 'RUNNING' | 'BLOCKED' | 'STOPPED' | 'FAULT' | 'REPAIR' | 'MAINTENANCE' | 'UNKNOWN';
export type AgvStatus =
  | 'IDLE' | 'TO_PICKUP' | 'LOADING' | 'TO_DROP' | 'UNLOADING' | 'TO_CHARGE' | 'CHARGING' | 'FAULT' | 'OFFLINE';

export interface FrameMachine {
  id: string; status: MachineStatus; orderId: string | null; stepSeq: number; progress: number;
  temperature: number; vibration: number; power: number; spindle: number; wear: number; faultLabel: string | null;
}
export interface FrameConveyor { conveyorId: string; items: { orderId: string; stepSeq: number; positionM: number }[] }
export interface FrameAgv {
  id: string; name: string; status: AgvStatus; x: number; z: number; heading: number; speed: number;
  battery: number; transportId: string | null; route: string[];
}
export interface FrameStock { materialId: string; locationId: string; onHand: number; reserved: number }
export interface FrameOrder {
  id: string; productId: string; quantity: number; priority: string; status: string; dueAtMs: number;
  good: number; scrapped: number; stepsDone: number; steps: number;
}
export interface FrameTransport {
  id: string; kind: string; orderId: string | null; materialId: string | null; quantity: number; status: string;
  agvId: string | null; from: string; to: string;
}
export interface Frame {
  simTimeMs: number; machines: FrameMachine[]; conveyors: FrameConveyor[]; agvs: FrameAgv[]; stock: FrameStock[];
  orders: FrameOrder[]; transports: FrameTransport[];
}
export interface NexusEvent {
  seq: number; eventId: string; simTimeMs: number; type: string; source: string;
  severity: 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR'; correlationId: string | null; payload: Record<string, unknown>;
  /** Only the browser runtime sends it (PlantJson); the server's event table does not. */
  payloadType?: string;
}
export interface SimState { running: boolean; speed: number; seed: number }
export type Answer<T = unknown> =
  | { ok: true; result: T }
  | { ok: false; status: number; error: string };
export type Command = { command: string } & Record<string, unknown>;
export type Query = { query: string } & Record<string, unknown>;

/** What both clients deliver, in this order within one tick: reset, sim, frame, events. */
export type NexusMessage =
  | { type: 'reset'; seed: number }
  | { type: 'sim'; running: boolean; speed: number; seed: number }
  | { type: 'frame'; frame: Frame }
  | { type: 'events'; items: NexusEvent[] }
  | { type: 'connection'; state: 'connecting' | 'open' | 'closed' };
```

- `src/nexus/client.ts`:

```ts
import type { Answer, Command, NexusMessage, Query } from './types';

/** The one way the user interface talks to the plant (Spec 14), in the browser or against the server. */
export interface NexusClient {
  readonly mode: 'browser' | 'server';
  /** Starts the stream; returns a function that stops it. */
  connect(onMessage: (m: NexusMessage) => void): () => void;
  command(c: Command): Promise<Answer>;
  query<T>(q: Query): Promise<Answer<T>>;
  play(): Promise<void>;
  pause(): Promise<void>;
  setSpeed(speed: number): Promise<void>;
  reset(seed?: number): Promise<void>;
}

export async function createClient(mode = import.meta.env.VITE_NEXUS_MODE ?? 'browser'): Promise<NexusClient> {
  if (mode === 'server') return new (await import('./server/ServerClient')).ServerClient('');
  return new (await import('./browser/BrowserClient')).BrowserClient();
}
```

- `src/nexus/loop.ts`: `export class Loop { constructor(advance: (ms: number) => void); running: boolean; speed: number; tick(realMs: number): number; setSpeed(s: number): void }` (Code unten).
- `src/nexus/browser/engine.ts`: `export class BrowserEngine { constructor(core: NexusCore, seed: number); tick(realMs: number): NexusMessage[]; handle(r: WorkerRequest): { answer: unknown; messages: NexusMessage[] } }` mit `WorkerRequest` = `{ id: number; kind: 'command'; body: Command } | { id; kind: 'query'; body: Query } | { id; kind: 'play' | 'pause' } | { id; kind: 'speed'; speed: number } | { id; kind: 'reset'; seed?: number } | { id; kind: 'hello' }`.

- [ ] **Step 1: Vite, Vitest und Kernmodul verdrahten**

`FactoryTwin/vite.config.ts`:

```ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath } from 'node:url';

const core = fileURLToPath(new URL('../platform/web/target/javascript/nexus-core.js', import.meta.url));

// Relative base so the build works on GitHub Pages under any repository name.
export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@nexus-core': core } },
  worker: { format: 'es' },
  build: { target: 'es2022', chunkSizeWarningLimit: 1600 },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  test: { environment: 'node' },
});
```

`FactoryTwin/package.json`, `scripts`: `"core": "cd .. && ./mvnw -B -q -pl platform/web -am package -DskipTests"`.

`FactoryTwin/src/nexus/core.d.ts`:

```ts
declare module '@nexus-core' {
  export function plantStart(seed: number): void;
  export function plantAdvance(simMs: number): number;
  export function plantFrame(): string;
  export function plantEventsFrom(from: number): string;
  export function plantCommand(json: string): string;
  export function plantQuery(json: string): string;
}
```

Run: `cd FactoryTwin && npm run core && ls ../platform/web/target/javascript/nexus-core.js`
Expected: Datei vorhanden.

- [ ] **Step 2: Failing tests schreiben**

`FactoryTwin/src/nexus/loop.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { Loop } from './loop';

describe('Loop', () => {
  it('advances real time times speed in whole 100 ms steps and keeps the rest', () => {
    const steps: number[] = [];
    const loop = new Loop((ms) => steps.push(ms));
    loop.running = true;
    loop.setSpeed(2);
    expect(loop.tick(120)).toBe(2);                 // 240 ms owed: two steps, 40 ms kept
    expect(loop.tick(30)).toBe(1);                  // 40 + 60 = 100
    expect(steps).toEqual([100, 100, 100]);
  });

  it('aLongPauseOfTheTimerRunsAtMostFiftySteps', () => {
    let n = 0;
    const loop = new Loop(() => n++);
    loop.running = true;
    loop.setSpeed(25);
    expect(loop.tick(60_000)).toBe(50);
    expect(loop.tick(50)).toBe(12);                 // the rest was dropped, not carried
    expect(n).toBe(62);
  });

  it('does nothing while paused and forgets what was owed', () => {
    let n = 0;
    const loop = new Loop(() => n++);
    loop.tick(1_000);
    loop.running = true;
    expect(loop.tick(50)).toBe(0);
    expect(n).toBe(0);
  });

  it('refuses speeds outside 0.25 to 25', () => {
    const loop = new Loop(() => {});
    expect(() => loop.setSpeed(0.1)).toThrow();
    expect(() => loop.setSpeed(30)).toThrow();
  });
});
```

`FactoryTwin/src/nexus/browser/engine.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import * as core from '@nexus-core';
import { BrowserEngine } from './engine';
import type { NexusMessage } from '../types';

function ofType<T extends NexusMessage['type']>(ms: NexusMessage[], t: T) {
  return ms.filter((m): m is Extract<NexusMessage, { type: T }> => m.type === t);
}

describe('BrowserEngine', () => {
  it('greets with reset, sim and frame, then sends frames and numbered events while running', () => {
    const engine = new BrowserEngine(core, 7);
    const hello = engine.handle({ id: 1, kind: 'hello' }).messages;
    expect(hello.map((m) => m.type)).toEqual(['reset', 'sim', 'frame']);
    engine.handle({ id: 2, kind: 'speed', speed: 25 });
    engine.handle({ id: 3, kind: 'play' });
    const seqs: number[] = [];
    for (let i = 0; i < 200; i++) {
      for (const m of ofType(engine.tick(50), 'events')) seqs.push(...m.items.map((e) => e.seq));
    }
    expect(seqs.length).toBeGreaterThan(0);
    expect(seqs).toEqual(seqs.map((_, i) => i + 1));    // 1, 2, 3 … without gaps or repeats
  });

  it('answers a refused command with the core message and status', () => {
    const engine = new BrowserEngine(core, 7);
    const { answer } = engine.handle({ id: 1, kind: 'command', body: { command: 'machineRepair', machineId: 'CNC-01' } });
    expect(answer).toMatchObject({ ok: false, status: 409 });
    expect((answer as { error: string }).error).toContain('CNC-01');
  });

  it('starts a new run on reset and numbers its events from 1 again', () => {
    const engine = new BrowserEngine(core, 7);
    engine.handle({ id: 1, kind: 'play' });
    for (let i = 0; i < 50; i++) engine.tick(50);
    const after = engine.handle({ id: 2, kind: 'reset', seed: 9 }).messages;
    expect(after[0]).toEqual({ type: 'reset', seed: 9 });
    engine.handle({ id: 3, kind: 'speed', speed: 25 });
    engine.handle({ id: 4, kind: 'play' });
    let first: number | undefined;
    for (let i = 0; i < 200 && first === undefined; i++) {
      first = ofType(engine.tick(50), 'events')[0]?.items[0]?.seq;
    }
    expect(first).toBe(1);
  });
});
```

- [ ] **Step 3: Tests laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/nexus`
Expected: FAIL, `./loop` und `./engine` nicht gefunden.

- [ ] **Step 4: Umsetzen**

`FactoryTwin/src/nexus/loop.ts`:

```ts
const STEP_MS = 100;
export const MAX_STEPS_PER_TICK = 50;

/**
 * The clock of the browser plant, as SimulationLoop on the server: real time times speed in whole steps. After a
 * stall (a throttled background tab) at most MAX_STEPS_PER_TICK steps run and the rest is dropped.
 */
export class Loop {
  running = false;
  speed = 1;
  private owedMs = 0;

  constructor(private readonly advance: (ms: number) => void) {}

  setSpeed(speed: number): void {
    if (!(speed >= 0.25 && speed <= 25)) throw new Error('Tempo muss zwischen 0,25 und 25 liegen');
    this.speed = speed;
  }

  /** realMs of real time passed; returns the steps run. */
  tick(realMs: number): number {
    if (!this.running) {
      this.owedMs = 0;
      return 0;
    }
    this.owedMs += realMs * this.speed;
    let steps = Math.floor(this.owedMs / STEP_MS);
    if (steps > MAX_STEPS_PER_TICK) {
      steps = MAX_STEPS_PER_TICK;
      this.owedMs = 0;
    } else {
      this.owedMs -= steps * STEP_MS;
    }
    for (let i = 0; i < steps; i++) this.advance(STEP_MS);
    return steps;
  }
}
```

`FactoryTwin/src/nexus/browser/engine.ts`:

```ts
import { Loop } from '../loop';
import type { Answer, Command, Frame, NexusEvent, NexusMessage, Query } from '../types';

export interface NexusCore {
  plantStart(seed: number): void;
  plantAdvance(simMs: number): number;
  plantFrame(): string;
  plantEventsFrom(from: number): string;
  plantCommand(json: string): string;
  plantQuery(json: string): string;
}

export type WorkerRequest =
  | { id: number; kind: 'hello' }
  | { id: number; kind: 'command'; body: Command }
  | { id: number; kind: 'query'; body: Query }
  | { id: number; kind: 'play' }
  | { id: number; kind: 'pause' }
  | { id: number; kind: 'speed'; speed: number }
  | { id: number; kind: 'reset'; seed?: number };

/**
 * The plant in the browser: the Java core (TeaVM) driven by a Loop. Pure, so it runs in a worker and in tests; the
 * worker only adds the timer and postMessage. Messages have the same form as the server's WebSocket.
 */
export class BrowserEngine {
  private readonly loop: Loop;
  private seed: number;
  private sentEvents = 0;

  constructor(private readonly core: NexusCore, seed: number) {
    this.seed = seed;
    this.loop = new Loop((ms) => core.plantAdvance(ms));
    core.plantStart(seed);
  }

  tick(realMs: number): NexusMessage[] {
    if (this.loop.tick(realMs) === 0) return [];
    return [this.frame(), ...this.events()];
  }

  handle(r: WorkerRequest): { answer: unknown; messages: NexusMessage[] } {
    switch (r.kind) {
      case 'hello':
        return { answer: null, messages: [{ type: 'reset', seed: this.seed }, this.sim(), this.frame(), ...this.events()] };
      case 'command': {
        const answer = JSON.parse(this.core.plantCommand(JSON.stringify(r.body))) as Answer;
        return { answer, messages: [this.frame(), ...this.events()] };
      }
      case 'query':
        return { answer: JSON.parse(this.core.plantQuery(JSON.stringify(r.body))), messages: [] };
      case 'play':
        this.loop.running = true;
        return { answer: null, messages: [this.sim()] };
      case 'pause':
        this.loop.running = false;
        return { answer: null, messages: [this.sim()] };
      case 'speed':
        this.loop.setSpeed(r.speed);
        return { answer: null, messages: [this.sim()] };
      case 'reset':
        this.seed = r.seed ?? this.seed;
        this.core.plantStart(this.seed);
        this.sentEvents = 0;
        return { answer: null, messages: [{ type: 'reset', seed: this.seed }, this.sim(), this.frame()] };
    }
  }

  private sim(): NexusMessage {
    return { type: 'sim', running: this.loop.running, speed: this.loop.speed, seed: this.seed };
  }

  private frame(): NexusMessage {
    return { type: 'frame', frame: JSON.parse(this.core.plantFrame()) as Frame };
  }

  private events(): NexusMessage[] {
    const items = JSON.parse(this.core.plantEventsFrom(this.sentEvents)) as NexusEvent[];
    if (items.length === 0) return [];
    this.sentEvents += items.length;
    return [{ type: 'events', items }];
  }
}
```

`FactoryTwin/src/nexus/browser/nexus.worker.ts`:

```ts
/// <reference lib="webworker" />
import * as core from '@nexus-core';
import { BrowserEngine, type WorkerRequest } from './engine';

const TICK_MS = 50;
const seedParam = Number(new URLSearchParams(self.location.search).get('seed'));
const engine = new BrowserEngine(core, Number.isInteger(seedParam) && seedParam > 0 ? seedParam : 4711);
let last = performance.now();

setInterval(() => {
  const now = performance.now();
  const messages = engine.tick(now - last);
  last = now;
  for (const m of messages) postMessage({ message: m });
}, TICK_MS);

onmessage = (e: MessageEvent<WorkerRequest>) => {
  let answer: unknown;
  let messages: ReturnType<BrowserEngine['handle']>['messages'] = [];
  try {
    ({ answer, messages } = engine.handle(e.data));
  } catch (err) {
    answer = { ok: false, status: 400, error: err instanceof Error ? err.message : 'Befehl fehlgeschlagen' };
  }
  postMessage({ id: e.data.id, answer });
  for (const m of messages) postMessage({ message: m });
};
```

Der Seed kommt aus der Seiten-URL: `BrowserClient` hängt `?seed=` aus `window.location.search` an die Worker-URL.

`FactoryTwin/src/nexus/browser/BrowserClient.ts`:

```ts
import type { NexusClient } from '../client';
import type { Answer, Command, NexusMessage, Query } from '../types';
import type { WorkerRequest } from './engine';

type Pending = (answer: unknown) => void;
type WithoutId<T> = T extends unknown ? Omit<T, 'id'> : never;

/** NexusClient against the Java core in a web worker (Spec 14, mode browser). */
export class BrowserClient implements NexusClient {
  readonly mode = 'browser' as const;
  private readonly worker: Worker;
  private readonly pending = new Map<number, Pending>();
  private nextId = 1;
  private listener: (m: NexusMessage) => void = () => {};

  constructor() {
    const url = new URL('./nexus.worker.ts', import.meta.url);
    url.search = window.location.search;
    this.worker = new Worker(url, { type: 'module' });
    this.worker.onmessage = (e: MessageEvent<{ id?: number; answer?: unknown; message?: NexusMessage }>) => {
      if (e.data.message) this.listener(e.data.message);
      if (e.data.id !== undefined) {
        this.pending.get(e.data.id)?.(e.data.answer);
        this.pending.delete(e.data.id);
      }
    };
  }

  private request(r: WithoutId<WorkerRequest>): Promise<unknown> {
    const id = this.nextId++;
    return new Promise((resolve) => {
      this.pending.set(id, resolve);
      this.worker.postMessage({ ...r, id });
    });
  }

  connect(onMessage: (m: NexusMessage) => void): () => void {
    this.listener = onMessage;
    onMessage({ type: 'connection', state: 'open' });
    void this.request({ kind: 'hello' });
    return () => {
      this.listener = () => {};
      this.worker.terminate();
    };
  }

  command(c: Command): Promise<Answer> {
    return this.request({ kind: 'command', body: c }) as Promise<Answer>;
  }

  query<T>(q: Query): Promise<Answer<T>> {
    return this.request({ kind: 'query', body: q }) as Promise<Answer<T>>;
  }

  async play() { await this.request({ kind: 'play' }); }

  async pause() { await this.request({ kind: 'pause' }); }

  async setSpeed(speed: number) { await this.request({ kind: 'speed', speed }); }

  async reset(seed?: number) { await this.request({ kind: 'reset', seed }); }
}
```

- [ ] **Step 5: Tests laufen lassen, grün**

Run: `cd FactoryTwin && npx vitest run src/nexus`
Expected: PASS (`loop` 4, `engine` 3).

- [ ] **Step 6: Commit**

```bash
git add FactoryTwin/vite.config.ts FactoryTwin/package.json FactoryTwin/src/nexus
git commit -m "feat(twin): NexusClient im Browser über das TeaVM-Modul im Web Worker"
```

---

### Task 3: `NexusClient` im Server-Modus

**Files:**
- Create: `FactoryTwin/src/nexus/server/ServerClient.ts`
- Test: `FactoryTwin/src/nexus/server/ServerClient.test.ts`

**Interfaces:**
- Consumes: `NexusClient`, Typen aus Task 2; Server-Endpunkte aus Task 1 und M1-D2 (`/ws`, `GET /api/v1/events?after=`, `POST /api/v1/sim/play|pause|reset`, `PUT /api/v1/sim/speed`).
- Produces: `new ServerClient(base: string, deps?: { WebSocket?: typeof WebSocket; fetch?: typeof fetch; setTimeout?: typeof setTimeout })`.

Verhalten:
- `connect` öffnet `ws(s)://<host>/ws` (aus `base` oder `window.location`), meldet `connection: connecting/open/closed`.
- Nachrichten `reset`, `sim`, `frame` gehen durch. `events`: Einträge mit `seq <= lastSeq` werden verworfen (doppelt), `lastSeq` merkt die höchste `seq`. Nach `reset` ist `lastSeq = 0`.
- Bei `close`: nach Backoff (1 s, 2 s, 4 s … höchstens 30 s; nach erfolgreichem `open` zurück auf 1 s) neu verbinden. Nach dem Wiederverbinden zuerst die Lücke holen: `GET /api/v1/events?after=<lastSeq>&limit=1000` und als `events` melden, solange 1000 zurückkommen, weiter blättern.
- `command`/`query`: `POST` an `/api/v1/sim/commands` bzw. `/queries`, Antwort-JSON unverändert (auch bei 4xx). Netzwerkfehler → `{ ok: false, status: 0, error: 'Server nicht erreichbar' }`.
- `play/pause/reset/setSpeed`: die bestehenden Endpunkte; ein Fehlerstatus wird als `Error` mit der Meldung aus dem Problem-JSON (`detail`) geworfen.

- [ ] **Step 1: Failing test schreiben**

`FactoryTwin/src/nexus/server/ServerClient.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest';
import { ServerClient } from './ServerClient';
import type { NexusEvent, NexusMessage } from '../types';

class FakeSocket {
  static all: FakeSocket[] = [];
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  constructor(readonly url: string) { FakeSocket.all.push(this); }
  open() { this.onopen?.(); }
  receive(m: unknown) { this.onmessage?.({ data: JSON.stringify(m) }); }
  drop() { this.onclose?.(); }
  close() {}
}

const event = (seq: number): NexusEvent => ({
  seq, eventId: `1-${seq}`, simTimeMs: seq * 100, type: 'order.created', source: 'smartfactory', severity: 'INFO',
  correlationId: null, payload: {},
});

function setup(gap: NexusEvent[] = []) {
  FakeSocket.all = [];
  const timers: (() => void)[] = [];
  const fetch = vi.fn(async (url: string) => new Response(JSON.stringify(url.includes('/events') ? gap : {}), { status: 200 }));
  const client = new ServerClient('http://host:5173', {
    WebSocket: FakeSocket as unknown as typeof WebSocket,
    fetch: fetch as unknown as typeof globalThis.fetch,
    setTimeout: ((fn: () => void) => { timers.push(fn); return 0; }) as unknown as typeof setTimeout,
  });
  const seen: NexusMessage[] = [];
  client.connect((m) => seen.push(m));
  return { client, seen, fetch, timers, socket: () => FakeSocket.all[FakeSocket.all.length - 1] };
}

const seqs = (seen: NexusMessage[]) =>
  seen.flatMap((m) => (m.type === 'events' ? m.items.map((e) => e.seq) : []));

describe('ServerClient', () => {
  it('connects to /ws of the page host', () => {
    const { socket } = setup();
    expect(socket().url).toBe('ws://host:5173/ws');
  });

  it('drops events it has already seen', () => {
    const { seen, socket } = setup();
    socket().open();
    socket().receive({ type: 'events', items: [event(1), event(2)] });
    socket().receive({ type: 'events', items: [event(2), event(3)] });
    expect(seqs(seen)).toEqual([1, 2, 3]);
  });

  it('aReconnectFillsTheGapWithoutDuplicates', async () => {
    const { seen, socket, timers, fetch } = setup([event(3), event(4)]);
    socket().open();
    socket().receive({ type: 'events', items: [event(1), event(2)] });
    socket().drop();
    expect(seen.at(-1)).toEqual({ type: 'connection', state: 'closed' });
    timers.shift()!();                                  // backoff over: reconnect
    socket().open();
    await vi.waitFor(() => expect(seqs(seen)).toEqual([1, 2, 3, 4]));
    expect(fetch).toHaveBeenCalledWith('http://host:5173/api/v1/events?after=2&limit=1000', expect.anything());
    socket().receive({ type: 'events', items: [event(4), event(5)] });
    expect(seqs(seen)).toEqual([1, 2, 3, 4, 5]);
  });

  it('aResetDropsTheOldRun', () => {
    const { seen, socket } = setup();
    socket().open();
    socket().receive({ type: 'events', items: [event(1), event(2)] });
    socket().receive({ type: 'reset', seed: 9 });
    socket().receive({ type: 'events', items: [event(1)] });
    expect(seqs(seen)).toEqual([1, 2, 1]);
  });

  it('passes a refused command through with its status', async () => {
    const { client, fetch } = setup();
    fetch.mockResolvedValueOnce(new Response('{"ok":false,"status":409,"error":"nein"}', { status: 409 }));
    await expect(client.command({ command: 'machineRepair', machineId: 'CNC-01' }))
      .resolves.toEqual({ ok: false, status: 409, error: 'nein' });
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/nexus/server`
Expected: FAIL, `./ServerClient` nicht gefunden.

- [ ] **Step 3: Umsetzen**

`FactoryTwin/src/nexus/server/ServerClient.ts`:

```ts
import type { NexusClient } from '../client';
import type { Answer, Command, NexusEvent, NexusMessage, Query } from '../types';

interface Deps {
  WebSocket: typeof WebSocket;
  fetch: typeof fetch;
  setTimeout: typeof setTimeout;
}

const PAGE = 1000;
const MAX_BACKOFF_MS = 30_000;

/**
 * NexusClient against the server (Spec 14, mode server): commands over REST, frames and events over the WebSocket.
 * After a lost connection it reconnects with backoff and fetches the events it missed before passing new ones on.
 */
export class ServerClient implements NexusClient {
  readonly mode = 'server' as const;
  private readonly deps: Deps;
  private readonly base: string;
  private socket: WebSocket | null = null;
  private listener: (m: NexusMessage) => void = () => {};
  private lastSeq = 0;
  private backoffMs = 1_000;
  private stopped = false;
  private filling = false;
  private held: NexusEvent[] = [];

  constructor(base: string, deps: Partial<Deps> = {}) {
    this.base = base || window.location.origin;
    this.deps = {
      WebSocket: deps.WebSocket ?? globalThis.WebSocket,
      fetch: deps.fetch ?? globalThis.fetch.bind(globalThis),
      setTimeout: deps.setTimeout ?? globalThis.setTimeout.bind(globalThis),
    };
  }

  connect(onMessage: (m: NexusMessage) => void): () => void {
    this.listener = onMessage;
    this.stopped = false;
    this.open();
    return () => {
      this.stopped = true;
      this.socket?.close();
    };
  }

  private open(): void {
    this.listener({ type: 'connection', state: 'connecting' });
    const url = this.base.replace(/^http/, 'ws') + '/ws';
    const socket = new this.deps.WebSocket(url);
    this.socket = socket;
    socket.onopen = () => {
      this.backoffMs = 1_000;
      this.listener({ type: 'connection', state: 'open' });
      if (this.lastSeq > 0) void this.fillGap();
    };
    socket.onmessage = (e: MessageEvent<string>) => this.receive(JSON.parse(e.data) as NexusMessage);
    socket.onclose = () => {
      this.listener({ type: 'connection', state: 'closed' });
      if (this.stopped) return;
      const wait = this.backoffMs;
      this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS);
      this.deps.setTimeout(() => this.open(), wait);
    };
  }

  private receive(m: NexusMessage): void {
    if (m.type === 'reset') {
      this.lastSeq = 0;
      this.held = [];
      this.listener(m);
    } else if (m.type === 'events') {
      if (this.filling) this.held.push(...m.items);
      else this.pass(m.items);
    } else {
      this.listener(m);
    }
  }

  /** Passes on events in seq order, each once. */
  private pass(items: NexusEvent[]): void {
    const fresh = items.filter((e) => e.seq > this.lastSeq).sort((a, b) => a.seq - b.seq);
    if (fresh.length === 0) return;
    this.lastSeq = fresh[fresh.length - 1].seq;
    this.listener({ type: 'events', items: fresh });
  }

  /** Events missed while the socket was closed; new ones from the socket wait until the gap is filled. */
  private async fillGap(): Promise<void> {
    this.filling = true;
    try {
      for (;;) {
        const r = await this.deps.fetch(`${this.base}/api/v1/events?after=${this.lastSeq}&limit=${PAGE}`, {
          headers: { Accept: 'application/json' },
        });
        if (!r.ok) break;
        const page = (await r.json()) as NexusEvent[];
        this.pass(page);
        if (page.length < PAGE) break;
      }
    } catch {
      // the next reconnect tries again
    } finally {
      this.filling = false;
      const held = this.held;
      this.held = [];
      this.pass(held);
    }
  }

  private async post(path: string, body: unknown): Promise<Answer> {
    try {
      const r = await this.deps.fetch(this.base + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(body),
      });
      return (await r.json()) as Answer;
    } catch {
      return { ok: false, status: 0, error: 'Server nicht erreichbar' };
    }
  }

  command(c: Command): Promise<Answer> {
    return this.post('/api/v1/sim/commands', c);
  }

  query<T>(q: Query): Promise<Answer<T>> {
    return this.post('/api/v1/sim/queries', q) as Promise<Answer<T>>;
  }

  private async control(method: string, path: string, body?: unknown): Promise<void> {
    const r = await this.deps.fetch(this.base + path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok) {
      const problem = (await r.json().catch(() => ({}))) as { detail?: string };
      throw new Error(problem.detail ?? `Fehler ${r.status}`);
    }
  }

  play() { return this.control('POST', '/api/v1/sim/play'); }

  pause() { return this.control('POST', '/api/v1/sim/pause'); }

  setSpeed(speed: number) { return this.control('PUT', '/api/v1/sim/speed', { speed }); }

  reset(seed?: number) { return this.control('POST', '/api/v1/sim/reset', seed === undefined ? {} : { seed }); }
}
```

- [ ] **Step 4: Test laufen lassen, grün**

Run: `cd FactoryTwin && npx vitest run src/nexus/server`
Expected: PASS (5 Tests).

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src/nexus/server
git commit -m "feat(twin): NexusClient gegen den Server mit Wiederverbinden und Lückenfüllung"
```

---

### Task 4: Twin-Laufzeit und Szenenmodell

**Files:**
- Create: `FactoryTwin/src/twin/twin.ts`, `FactoryTwin/src/twin/plantLayout.ts`, `FactoryTwin/src/twin/sceneModel.ts`
- Move: Inhalt von `FactoryTwin/src/sim/layout.ts` und `FactoryTwin/src/sim/config.ts` unverändert nach `FactoryTwin/src/twin/layout.ts` und `FactoryTwin/src/twin/config.ts`; aus `FactoryTwin/src/sim/initialState.ts` nur `MACHINE_DEFS`, `CHARGER_NODES` und die Band-Definitionen (Start, Ende, Länge, Name) nach `plantLayout.ts`
- Create: `FactoryTwin/src/twin/types.ts` (die Typen aus `src/sim/types.ts`, die Szene und Panels lesen: `Vec2`, `ProcessType`, `ProductId`, `MachineKind`, `ProductDef`, `MachineConfig`, `MachineSensors`, `RoutingStep`, `FaultType`; `MachineStatus` und `AgvState` aus `src/nexus/types.ts` re-exportiert)
- Test: `FactoryTwin/src/twin/twin.test.ts`, `FactoryTwin/src/twin/sceneModel.test.ts`, `FactoryTwin/src/twin/plantLayout.test.ts`

**Interfaces:**
- Consumes: `Frame`, `NexusMessage` (Task 2).
- Produces:
  - `class Twin { apply(m: NexusMessage, now: number): void; latest: Frame | null; previous: Frame | null; alpha(now: number): number; agvPose(id: string, now: number): { x: number; z: number; heading: number } | null; readonly scene: SceneState }`.
  - `toScene(frame: Frame): SceneState` mit `SceneState = { time: number; machines: SceneMachine[]; conveyors: SceneConveyor[]; agvs: SceneAgv[]; chargers: SceneCharger[]; inventory: SceneInventory; workpieces: Map<string, { product: ProductId; orderId: string; step: number }>; rawQueue: string[] }`. Feldnamen genau wie heute in `src/sim/types.ts` (`Machine`, `Conveyor`, `Agv`, `Charger`, `Inventory`), soweit `src/scene` sie liest (Liste unten).
  - Zuordnung (für `sceneModel.ts`):

| Szene liest | aus dem Frame |
|---|---|
| `m.id`, `m.name`, `m.kind`, `m.model`, `m.capabilities`, `m.position`, `m.rotation`, `m.input`, `m.output`, `m.dockNode` | `MACHINE_DEFS` (Zeichendaten) |
| `m.status` | `machine.status` (neu möglich: `UNKNOWN`) |
| `m.job` | `machine.orderId ? { wpId: orderId + '/' + stepSeq, product, progress: machine.progress, duration: 1, finished: progress >= 1 } : null`; `product` aus `frame.orders` |
| `m.sensors` | `{ temperature, vibration, power, spindle }` |
| `m.wear`, `m.fault` | `machine.wear`; `faultLabel ? { typeId: '', label: faultLabel, since: 0 } : null` |
| `m.config` | `{ ...DEFAULT_MACHINE_CONFIG, ...configs[id] }` (Override und Kühlung merkt sich der Store, wenn er `machineConfigure` sendet) |
| `m.inBuffer`, `m.outBuffer`, `m.lastVerdict` | `[]`, `[]`, `null` |
| `c.*` Geometrie | Band-Definitionen; `status: 'RUNNING'`; `items` nur für `CV-03`: `{ wpId: orderId + '/' + stepSeq, s: positionM }` |
| `c.beltOffset` | `frame.simTimeMs / 1000 * speed` modulo 1 |
| `a.id`, `a.name`, `a.heading`, `a.speed`, `a.battery`, `a.route` | gleichnamige Felder (`battery` 0 bis 100) |
| `a.pos` | `{ x, z }` (die Szene nimmt in `useFrame` die Interpolation aus `twin.agvPose`) |
| `a.state` | `status`; `FAULT` bleibt `FAULT` (neuer Wert im Typ) |
| `a.load` | Transport des AGV (`transportId` → `frame.transports`), wenn `status` `TO_DROP`/`UNLOADING`: `wpId` = `orderId + '/' + stepSeq` für WIP/PUTAWAY, sonst Material-ID; sonst `null` |
| `a.fault`, `a.blockedBy` | `status === 'FAULT' ? { typeId: '', label: 'Störung', since: 0 } : null`, `null` |
| `ch.*` | `CHARGER_NODES`; `occupiedBy`: AGV mit Status `CHARGING` im Umkreis von 1 m um `nodePos(ch.node)` |
| `inv.raw`, `inv.finished`, `inv.scrap`, Kapazitäten | Summe `onHand` aller `RM-*`; `finished[p]` = `onHand` von `FG-<p>`; `scrap` = Summe `scrapped` aller Aufträge; Kapazitäten aus `DEFAULT` von heute |
| `s.workpieces` | aus Maschinenjobs, `CV-03`-Teilen und AGV-Ladungen, Produkt aus `frame.orders` |

- [ ] **Step 1: Failing tests schreiben**

`FactoryTwin/src/twin/twin.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { Twin } from './twin';
import type { Frame } from '../nexus/types';

const frame = (t: number, x: number): Frame => ({
  simTimeMs: t, machines: [], conveyors: [], stock: [], orders: [], transports: [],
  agvs: [{ id: 'AGV-01', name: 'AGV 1', status: 'TO_PICKUP', x, z: 0, heading: 0, speed: 1, battery: 80,
    transportId: null, route: [] }],
});

describe('Twin', () => {
  it('interpolates an AGV between the two latest frames over the time they arrived apart', () => {
    const twin = new Twin();
    twin.apply({ type: 'frame', frame: frame(0, 0) }, 1_000);
    twin.apply({ type: 'frame', frame: frame(100, 10) }, 1_100);
    expect(twin.agvPose('AGV-01', 1_150)!.x).toBeCloseTo(5);
  });

  it('interpolationStopsAtTheLatestFrame', () => {
    const twin = new Twin();
    twin.apply({ type: 'frame', frame: frame(0, 0) }, 1_000);
    twin.apply({ type: 'frame', frame: frame(100, 10) }, 1_100);
    expect(twin.agvPose('AGV-01', 5_000)!.x).toBe(10);
  });

  it('resetForgetsBothFrames', () => {
    const twin = new Twin();
    twin.apply({ type: 'frame', frame: frame(0, 0) }, 1_000);
    twin.apply({ type: 'reset', seed: 3 }, 1_050);
    expect(twin.latest).toBeNull();
    expect(twin.previous).toBeNull();
    twin.apply({ type: 'frame', frame: frame(0, 7) }, 1_100);
    expect(twin.agvPose('AGV-01', 1_200)!.x).toBe(7);
  });
});
```

`FactoryTwin/src/twin/sceneModel.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import * as core from '@nexus-core';
import { toScene } from './sceneModel';
import type { Frame } from '../nexus/types';

function runningFrame(): Frame {
  core.plantStart(7);
  core.plantAdvance(40 * 60_000);
  return JSON.parse(core.plantFrame()) as Frame;
}

describe('toScene', () => {
  it('places every machine, conveyor, AGV and charger of the plant', () => {
    const s = toScene(runningFrame());
    expect(s.machines.map((m) => m.id)).toEqual(['CNC-01', 'CNC-02', 'CNC-03', 'CNC-04', 'QC-01', 'ROB-01', 'ROB-02']);
    expect(s.conveyors.map((c) => c.id)).toEqual(['CV-01', 'CV-02', 'CV-03', 'CV-04']);
    expect(s.agvs).toHaveLength(6);
    expect(s.chargers).toHaveLength(6);
  });

  it('gives every busy machine a job whose workpiece is known', () => {
    const s = toScene(runningFrame());
    const busy = s.machines.filter((m) => m.job);
    expect(busy.length).toBeGreaterThan(0);
    for (const m of busy) {
      expect(s.workpieces.get(m.job!.wpId)).toBeDefined();
      expect(m.job!.progress).toBeGreaterThanOrEqual(0);
      expect(m.job!.progress).toBeLessThanOrEqual(1);
    }
  });

  it('counts raw and finished stock from the warehouse', () => {
    const s = toScene(runningFrame());
    expect(s.inventory.raw).toBeGreaterThan(0);
    expect(Object.keys(s.inventory.finished).sort()).toEqual(['AW50', 'FP12', 'GH200', 'MT7']);
  });
});
```

`FactoryTwin/src/twin/plantLayout.test.ts` (Abgleich der Zeichendaten mit dem Kern):

```ts
import { describe, expect, it } from 'vitest';
import * as core from '@nexus-core';
import { CHARGER_NODES, MACHINE_DEFS } from './plantLayout';
import { PATH_GRAPH } from './layout';
import type { Frame } from '../nexus/types';

describe('plantLayout', () => {
  it('draws exactly the machines the core simulates', () => {
    core.plantStart(1);
    const frame = JSON.parse(core.plantFrame()) as Frame;
    expect(MACHINE_DEFS.map((m) => m.id).sort()).toEqual(frame.machines.map((m) => m.id).sort());
  });

  it('puts every charger on a node of the path graph', () => {
    for (const c of CHARGER_NODES) expect(PATH_GRAPH.nodes.has(c.node), c.id).toBe(true);
  });
});
```

- [ ] **Step 2: Tests laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/twin`
Expected: FAIL, Module fehlen.

- [ ] **Step 3: Umsetzen**

`FactoryTwin/src/twin/twin.ts`:

```ts
import type { Frame, NexusMessage } from '../nexus/types';
import { toScene, type SceneState } from './sceneModel';

/**
 * Holds the latest and the previous frame (Spec 14). The scene reads interpolated poses from here in useFrame;
 * panels read the scene state through the store. Between two frames it interpolates over the real time they arrived
 * apart and never beyond the latest one: a late frame makes the picture wait, it does not make an AGV jump ahead.
 */
export class Twin {
  latest: Frame | null = null;
  previous: Frame | null = null;
  private latestAt = 0;
  private previousAt = 0;
  private sceneCache: SceneState | null = null;

  apply(m: NexusMessage, now: number): void {
    if (m.type === 'reset') {
      this.latest = this.previous = null;
      this.sceneCache = null;
    } else if (m.type === 'frame') {
      this.previous = this.latest;
      this.previousAt = this.latestAt;
      this.latest = m.frame;
      this.latestAt = now;
      this.sceneCache = null;
    }
  }

  /** 0 at the previous frame, 1 at the latest; never more. */
  alpha(now: number): number {
    if (!this.previous) return 1;
    const span = this.latestAt - this.previousAt;
    if (span <= 0) return 1;
    return Math.min(1, Math.max(0, (now - this.latestAt) / span));
  }

  agvPose(id: string, now: number): { x: number; z: number; heading: number } | null {
    const b = this.latest?.agvs.find((a) => a.id === id);
    if (!b) return null;
    const a = this.previous?.agvs.find((v) => v.id === id) ?? b;
    const t = this.alpha(now);
    let dh = b.heading - a.heading;
    if (dh > Math.PI) dh -= 2 * Math.PI;
    if (dh < -Math.PI) dh += 2 * Math.PI;
    return { x: a.x + (b.x - a.x) * t, z: a.z + (b.z - a.z) * t, heading: a.heading + dh * t };
  }

  get scene(): SceneState | null {
    if (!this.latest) return null;
    this.sceneCache ??= toScene(this.latest);
    return this.sceneCache;
  }
}
```

Zur Interpolation: Der Frame kommt mit Abstand `span` an; die Szene stellt um genau einen Frame verzögert dar und läuft in `span` vom vorletzten zum letzten Frame. Der Test `interpolates…` erwartet bei `now = 1150` (50 ms nach dem letzten Frame, Abstand 100 ms) die Hälfte.

`FactoryTwin/src/twin/plantLayout.ts`: `MACHINE_DEFS` und `CHARGER_NODES` aus `src/sim/initialState.ts` unverändert übernehmen; die Band-Geometrie, die `createInitialState` heute für die Bänder setzt (`id`, `name`, `start`, `end`, `length`, `speed`), als `export const CONVEYOR_DEFS` herausziehen. `DEFAULT_INVENTORY = { rawCapacity, finishedCapacity }` mit den Werten aus `createInitialState`.

`FactoryTwin/src/twin/sceneModel.ts`: setzt die Zuordnungstabelle oben um. Struktur:

```ts
export function toScene(frame: Frame, configs: Record<string, Partial<MachineConfig>> = {}): SceneState {
  const productOf = new Map(frame.orders.map((o) => [o.id, o.productId as ProductId]));
  const workpieces = new Map<string, { product: ProductId; orderId: string; step: number }>();
  const wp = (orderId: string, step: number) => {
    const id = `${orderId}/${step}`;
    const product = productOf.get(orderId);
    if (product) workpieces.set(id, { product, orderId, step });
    return id;
  };
  const machines = MACHINE_DEFS.map((def) => {
    const m = frame.machines.find((x) => x.id === def.id)!;
    return {
      ...def,
      status: m.status,
      job: m.orderId ? { wpId: wp(m.orderId, m.stepSeq), progress: m.progress, duration: 1, finished: m.progress >= 1 } : null,
      sensors: { temperature: m.temperature, vibration: m.vibration, power: m.power, spindle: m.spindle },
      wear: m.wear,
      fault: m.faultLabel ? { typeId: '', label: m.faultLabel, since: 0 } : null,
      config: { ...DEFAULT_MACHINE_CONFIG, ...configs[def.id] },
      inBuffer: [] as string[],
      outBuffer: [] as string[],
      lastVerdict: null,
    };
  });
  // conveyors, agvs, chargers, inventory as in the table; rawQueue: []
  …
}
```

Jede Zeile der Tabelle wird zu genau einer Zuweisung; was die Szene liest und hier nicht steht, ist ein Fehler im Plan → Ruling im Ledger.

- [ ] **Step 4: Tests laufen lassen, grün**

Run: `cd FactoryTwin && npx vitest run src/twin`
Expected: PASS (3 + 3 + 2).

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src/twin
git commit -m "feat(twin): Twin-Laufzeit mit Interpolation und Szenenmodell aus dem Frame"
```

---

### Task 5: Store und 3D-Szene auf den Twin umstellen, alte Engine entfernen

**Files:**
- Rewrite: `FactoryTwin/src/store/factoryStore.ts`
- Modify: `FactoryTwin/src/scene/*.tsx` (Ersetzungen unten), `FactoryTwin/src/ui/format.ts` (Farben und Texte für `UNKNOWN` und `FAULT`)
- Delete: `FactoryTwin/src/sim/` (ganz, nachdem Task 4 `layout.ts` und `config.ts` verschoben hat), `FactoryTwin/src/integration/`, `FactoryTwin/src/ui/panels/KpiPanel.tsx`, `OrdersPanel.tsx`, `RulesPanel.tsx`, `IntegrationPanel.tsx`, `FactoryTwin/src/ui/charts/TimeChart.tsx` (kommt in M2-C neu)
- Test: `FactoryTwin/src/store/factoryStore.test.ts`

**Interfaces:**
- Consumes: `NexusClient`, `createClient` (Task 2), `Twin`, `SceneState` (Task 4).
- Produces (`factoryStore.ts`):
  - `export const twin: Twin` (die Szene liest daraus in `useFrame`).
  - `export function startNexus(client: NexusClient): () => void` (verbindet, leitet Nachrichten an `twin` und Store).
  - Zustand-Store `useFactory` mit: `scene: SceneState | null`, `sim: SimState`, `connection: 'connecting' | 'open' | 'closed'`, `events: NexusEvent[]` (die letzten 500), `notice: string | null` (letzte Fehlermeldung eines Befehls), `configs: Record<string, Partial<MachineConfig>>` sowie die bisherigen UI-Felder (`selected`, `hovered`, `camera`, `showLabels`, `showPaths`, `quality`, `leftOpen`, `rightOpen`, …) und Aktionen `select`, `hover`, `setCamera`, `toggle`, `setPanel`.
  - `export async function send(c: Command): Promise<boolean>` (setzt bei Ablehnung `notice` auf die Meldung, gibt `false` zurück).
  - `export const control = { play, pause, setSpeed, reset }` (über den Client).

Ersetzungen in `src/scene` (jede Fundstelle):

| alt | neu |
|---|---|
| `import { engine } from '../store/factoryStore'` | `import { twin } from '../store/factoryStore'` |
| `engine.state.machines` / `.conveyors` / `.agvs` / `.chargers` / `.inventory` / `.time` | `twin.scene?.machines ?? []` usw.; `time` → `twin.scene?.time ?? 0` |
| `engine.machine(id)` / `engine.conveyor(id)` / `engine.agv(id)` | `twin.scene?.machines.find((m) => m.id === id)` usw. |
| `engine.state.running` | `useFactory.getState().sim.running` |
| `a.pos.x`, `a.pos.z`, `a.heading` in `useFrame` der AGVs | `const p = twin.agvPose(a.id, performance.now())` und `p.x`, `p.z`, `p.heading` |
| `s.workpieces.get(id)` | `twin.scene?.workpieces.get(id)` |
| `s.rawQueue` | `twin.scene?.rawQueue ?? []` |
| Importe aus `../sim/...` | aus `../twin/...` |

Die Listen der Komponenten (`FactoryScene.tsx` Zeilen 70–73, `Logistics.tsx` 230 und 276) rendern einmal die IDs aus `MACHINE_DEFS`, `CONVEYOR_DEFS` und `CHARGER_NODES` und der AGV-Liste des ersten Frames; jede Komponente liest ihre aktuellen Daten in `useFrame`.

- [ ] **Step 1: Failing test schreiben**

`FactoryTwin/src/store/factoryStore.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { NexusClient } from '../nexus/client';
import type { Answer, NexusMessage } from '../nexus/types';
import { send, startNexus, twin, useFactory } from './factoryStore';

function fakeClient(answer: Answer): { client: NexusClient; push: (m: NexusMessage) => void } {
  let listener: (m: NexusMessage) => void = () => {};
  const client: NexusClient = {
    mode: 'browser',
    connect: (l) => { listener = l; return () => {}; },
    command: async () => answer,
    query: async () => ({ ok: true, result: {} }) as Answer<never>,
    play: async () => {}, pause: async () => {}, setSpeed: async () => {}, reset: async () => {},
  };
  return { client, push: (m) => listener(m) };
}

describe('factoryStore', () => {
  it('keeps the simulation state, the newest 500 events and the frame', () => {
    const { client, push } = fakeClient({ ok: true, result: null });
    startNexus(client);
    push({ type: 'sim', running: true, speed: 5, seed: 7 });
    push({ type: 'events', items: Array.from({ length: 600 }, (_, i) => ({
      seq: i + 1, eventId: `1-${i + 1}`, simTimeMs: i, type: 'order.created', source: 'smartfactory',
      severity: 'INFO' as const, correlationId: null, payload: {} })) });
    expect(useFactory.getState().sim).toEqual({ running: true, speed: 5, seed: 7 });
    expect(useFactory.getState().events).toHaveLength(500);
    expect(useFactory.getState().events.at(-1)!.seq).toBe(600);
  });

  it('shows the reason when the plant refuses a command', async () => {
    const { client } = fakeClient({ ok: false, status: 409, error: 'Ungültiger Zustandsübergang für CNC-01' });
    startNexus(client);
    await expect(send({ command: 'machineRepair', machineId: 'CNC-01' })).resolves.toBe(false);
    expect(useFactory.getState().notice).toContain('CNC-01');
  });

  it('forgets the old run on reset', () => {
    const { client, push } = fakeClient({ ok: true, result: null });
    startNexus(client);
    push({ type: 'events', items: [{ seq: 1, eventId: '1-1', simTimeMs: 0, type: 'x', source: 's',
      severity: 'INFO', correlationId: null, payload: {} }] });
    push({ type: 'reset', seed: 9 });
    expect(useFactory.getState().events).toEqual([]);
    expect(twin.latest).toBeNull();
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/store`
Expected: FAIL (`startNexus`, `send`, `twin` fehlen).

- [ ] **Step 3: Store neu schreiben, Szene umstellen, Alte Engine löschen**

`factoryStore.ts`: die UI-Felder und Aktionen von heute übernehmen (Selektion, Kamera, Anzeigeoptionen, Panels), alles mit `engine`, `api`, `Snapshot`, `Kpis`, `buildSnapshot` entfernen. `startNexus` verbindet den Client und verarbeitet jede Nachricht so:

```ts
export function startNexus(client: NexusClient): () => void {
  current = client;
  return client.connect((m) => {
    twin.apply(m, performance.now());
    switch (m.type) {
      case 'reset': useFactory.setState({ events: [], scene: null }); break;
      case 'sim': useFactory.setState({ sim: { running: m.running, speed: m.speed, seed: m.seed } }); break;
      case 'frame': useFactory.setState({ scene: twin.scene }); break;
      case 'events': useFactory.setState((s) => ({ events: [...s.events, ...m.items].slice(-MAX_EVENTS) })); break;
      case 'connection': useFactory.setState({ connection: m.state }); break;
    }
  });
}
```

`send` schreibt bei `machineConfigure` vorher `configs[machineId] = { override, coolant }` in den Store. Panels lesen künftig `s.scene` statt `s.snap`.

In `src/main.tsx` (oder wo `App` gerendert wird) vor dem Rendern: `startNexus(await createClient())`.

`src/ui/format.ts`: `STATUS_COLOR.UNKNOWN = '#6b7280'`, `STATUS_LABEL.UNKNOWN = 'Nicht erreichbar'`, `STATUS_SHORT.UNKNOWN = '?'`; `AGV_COLOR.FAULT = '#ef4444'`, `AGV_LABEL.FAULT = 'Störung'`. Typen auf `MachineStatus`/`AgvStatus` aus `src/nexus/types.ts` umstellen.

Szene: Ersetzungen aus der Tabelle; danach dürfen in `src` keine Importe aus `../sim` oder `../integration` mehr stehen.

Run: `cd FactoryTwin && grep -rn "sim/\|integration/\|engine\b" src --include=*.ts --include=*.tsx | grep -v "nexus/browser/engine"`
Expected: keine Treffer.

- [ ] **Step 4: Tests, Typprüfung, Build**

Run: `cd FactoryTwin && npx vitest run && npm run typecheck && npm run build`
Expected: Vitest PASS (alle Dateien unter `src/nexus`, `src/twin`, `src/store`; die alten Engine-Tests sind gelöscht), `tsc` ohne Fehler, `vite build` erzeugt `dist/` mit einem Worker-Chunk.

- [ ] **Step 5: Commit**

```bash
git add -A FactoryTwin/src
git commit -m "feat(twin): Szene und Store lesen den Java-Kern; eigene TypeScript-Engine entfernt"
```

---

### Task 6: Kopfleiste, Ereignisprotokoll, Detailansicht; beide Modi im Browser geprüft

**Files:**
- Modify: `FactoryTwin/src/ui/panels/TopBar.tsx`, `FactoryTwin/src/App.tsx`
- Rewrite: `FactoryTwin/src/ui/panels/EventLog.tsx`, `FactoryTwin/src/ui/panels/DetailPanel.tsx`
- Modify: `FactoryTwin/README.md` (Abschnitt „Betriebsarten")
- Test: `FactoryTwin/src/ui/panels/eventText.test.ts`

**Interfaces:**
- Consumes: `useFactory`, `send`, `control` (Task 5), `NexusEvent` (Task 2).
- Produces: `eventText(e: NexusEvent): string` in `FactoryTwin/src/ui/panels/eventText.ts` (deutsche Kurzbeschreibung je Ereignistyp, für Ereignisprotokoll und später Event Monitor).

Kopfleiste und Tastatur: jede Stelle mit `command((e) => …)` ersetzen: `e.play()`/`e.pause()` → `control.play()`/`control.pause()` nach `useFactory.getState().sim.running`; `e.setSpeed(v)` → `control.setSpeed(v)`; `e.reset()` → `control.reset()`. Uhrzeit aus `s.scene?.time`. Neu rechts in der Kopfleiste: Verbindungsanzeige (`StatusDot`: grün `open`, gelb `connecting`, rot `closed`) mit Text „Browser" bzw. „Server" (Spec 20: die Oberfläche weist die Betriebsart aus). Ist `notice` gesetzt, eine Zeile unter der Kopfleiste mit der Meldung und einem Schliessen-Knopf (`notice: null`).

Ereignisprotokoll: Liste der letzten 200 aus `s.events`, neueste oben, je Zeile Simulationszeit (`clock(simTimeMs / 1000)`), Schweregrad-Farbe (`ERROR` rot, `WARNING` gelb, `SUCCESS` grün, sonst grau), `eventText(e)`, `correlationId` in Mono. Klick auf eine Zeile mit `machineId` oder `agvId` in der Nutzlast wählt das Objekt (`select`).

Detailansicht (je nach `selected.type`):
- Maschine: Status mit Farbe, Auftrag und Arbeitsgang, Fortschritt (`Meter`), Temperatur, Vibration, Leistung, Drehzahl, Verschleiss, Störungstext; Knöpfe Start, Stopp, Störung, Reparatur, Wartung (`send({ command: 'machineStart', machineId })` usw.); Override (`Slider` 50–130) und Kühlung (`Toggle`) → `machineConfigure`.
- AGV: Status, Akku (`Meter`), Geschwindigkeit, Transport mit Von/Nach aus `frame.transports`; Knöpfe Aktivieren/Deaktivieren, Laden, Störung (`agvEnable`, `agvDisable`, `agvCharge`, `agvFault`).
- Band: Teile auf dem Band mit Auftrag und Position.
- Lager (Klick auf Regal): Bestände je Material und Lagerplatz aus `twin.latest.stock`, verfügbar = `onHand − reserved`.

Die Befehlsnamen und Felder stehen in `PlantCommands.java` (`machineStart`, `machineStop`, `machineFault` mit `faultTypeId` optional, `machineRepair`, `machineMaintain`, `machineConfigure` mit `override` und `coolant`, `agvEnable`, `agvDisable`, `agvCharge`, `agvFault`). Vor dem Schreiben dort nachsehen.

- [ ] **Step 1: Failing test schreiben**

`FactoryTwin/src/ui/panels/eventText.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { eventText } from './eventText';
import type { NexusEvent } from '../../nexus/types';

const e = (type: string, payload: Record<string, unknown>): NexusEvent => ({
  seq: 1, eventId: '1-1', simTimeMs: 0, type, source: 'x', severity: 'INFO', correlationId: null, payload,
});

describe('eventText', () => {
  it('describes the common events in German', () => {
    expect(eventText(e('machine.status', { machineId: 'CNC-01', from: 'IDLE', to: 'UNKNOWN' })))
      .toBe('CNC-01: Bereit → Nicht erreichbar');
    expect(eventText(e('order.created', { orderId: 'PO-0001', productId: 'FP12', quantity: 3 })))
      .toBe('Auftrag PO-0001 angelegt: 3 × FP12');
    expect(eventText(e('opcua.connection', { message: 'OPC UA getrennt, Maschinen gelten als unbekannt' })))
      .toBe('OPC UA getrennt, Maschinen gelten als unbekannt');
  });

  it('falls back to the type for events it does not know', () => {
    expect(eventText(e('route.replanned', {}))).toBe('route.replanned');
  });
});
```

Die Feldnamen der Nutzlasten stehen in `platform/contracts/src/main/java/io/nexus/contracts/event/Payloads.java`; `eventText` deckt mindestens ab: `order.*`, `operation.started/completed`, `machine.status`, `machine.fault`, `transport.created/delivered/failed`, `agv.fault`, `stock.changed`, `material.shortage`, `opcua.connection`, `decision` (Text `subject: reason`).

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/ui`
Expected: FAIL, `./eventText` fehlt.

- [ ] **Step 3: Umsetzen** (`eventText.ts`, Kopfleiste, Ereignisprotokoll, Detailansicht, `App.tsx` wie oben; Tabs „Aufträge", „Regeln", „Integration" aus der rechten Spalte entfernen, es bleiben „Details" und „Ereignisse".)

- [ ] **Step 4: Tests, Typprüfung, Build**

Run: `cd FactoryTwin && npx vitest run && npm run typecheck && npm run build`
Expected: alles grün.

- [ ] **Step 5: Im Browser prüfen, Modus `browser`**

Run (Hintergrund): `cd FactoryTwin && npm run dev`; im Browser-Pane `http://localhost:5173` öffnen.
Prüfen und im Ledger festhalten: 3D-Halle erscheint; Start → Zeit läuft, AGVs fahren flüssig, Maschinen wechseln den Status; Tempo 25; Klick auf `CNC-01` → Details, „Störung" → Maschine rot, Ereignis erscheint; „Reparatur" bei laufender Maschine → Meldung „Ungültiger Zustandsübergang …" unter der Kopfleiste; Reset → Zeit 0. Konsole ohne Fehler (`read_console_messages`).

- [ ] **Step 6: Im Browser prüfen, Modus `server`**

Run (Hintergrund): `./mvnw -B -pl platform/app -am spring-boot:run -DskipTests` (Profil `embedded` ist Standard); dann `cd FactoryTwin && VITE_NEXUS_MODE=server npm run dev -- --port 5179`.
Dieselben Prüfungen wie in Step 5 unter `http://localhost:5179`; zusätzlich: Server stoppen → Verbindungsanzeige rot; Server starten → grün, Ereignisse laufen weiter ohne Lücke.

- [ ] **Step 7: README und Commit**

`FactoryTwin/README.md`, Abschnitt „Betriebsarten": `npm run core` (TeaVM-Modul bauen), `npm run dev` (Browser), `VITE_NEXUS_MODE=server npm run dev` mit laufendem Server, `?seed=` in der URL.

```bash
git add FactoryTwin
git commit -m "feat(twin): Kopfleiste, Ereignisse und Details auf NexusClient; beide Betriebsarten geprüft"
```

---

## Nicht in diesem Plan

- **Shell, Navigation und Designsystem** (Spec 14: elf Bereiche, Hash-Routing): M2-B.
- **Aufträge, Kennzahlen, Regeln, Lager, Analytics als Bereiche**: M2-C; die Abfrage `kpis` steht ab Task 1 bereit.
- **Messung „60 Bilder pro Sekunde bei 25-fachem Tempo"**: M3 zusammen mit Playwright; in Step 5 von Task 6 nur Sichtprüfung.
- **Werkstück-Animation zwischen Maschinen und AGV** (Behälter auf dem AGV, Ablage am Dock): das Szenenmodell liefert `a.load`; eine eigene Behälter-Darstellung kommt mit M2-C.
