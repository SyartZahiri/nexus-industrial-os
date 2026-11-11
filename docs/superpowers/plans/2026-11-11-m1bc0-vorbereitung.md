# M1-BC0 Vorbereitung für die parallele Entwicklung – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Alles anlegen, was FlowPilot (M1-B) und SmartFactory (M1-C) gemeinsam brauchen, damit beide Zweige danach parallel arbeiten, ohne dieselbe Datei anzufassen.

**Architecture:** Drei kleine Bausteine auf `main`: eine ID-Quelle als Port im Kern, die Maschinen-Ports für Aufträge an Maschinen und Rückmeldungen, und die Build-Verdrahtung (Test-JARs, Selbsttest-Stubs im Browser-Modul). Danach berührt M1-B nur `FlowPilot/` und `platform/app/.../flowpilot`, M1-C nur `SmartFactory/` und `platform/app/.../smartfactory`.

**Tech Stack:** wie M1-A2 (Java 25, Maven, Spring Boot 4.1.2, PostgreSQL 18 embedded, TeaVM 0.16.0).

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Revision 2), Abschnitte 2a, 5, 6, 10, 11.

**Form:** wie M1-A2. Schnittstellen, Verhalten und Tests sind festgelegt, wörtlicher Code nur bei heiklen Stellen.

## Global Constraints

- Gilt alles aus `2026-11-10-m1a2-portabler-kern.md`, Abschnitt Global Constraints.
- `V1`..`V3` sind eingefroren. Neue Migrationen: diese Vorbereitung `V4`, FlowPilot `V100`..`V199`, SmartFactory `V200`..`V299`.
- Commits enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **ID-Kollision nach Reset.** Nach `IdSource.reset()` beginnen die Zähler neu; das ist nur zulässig, weil der Reset (M1-D) die Fachtabellen leert. Test in Task 1 prüft, dass Zähler je Präfix unabhängig und nach Reset wieder bei 1 sind.
2. **Gleichzeitige ID-Vergabe auf dem Server.** Zwei Threads dürfen nie dieselbe ID bekommen. Test in Task 1.

---

### Task 1: ID-Quelle

**Files:**
- Create: `platform/contracts/src/main/java/io/nexus/contracts/port/IdSource.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/memory/MemoryIdSource.java`
- Create: `platform/contracts/src/test/java/io/nexus/contracts/port/IdSourceContract.java`, `MemoryIdSourceTest.java`
- Create: `platform/app/src/main/resources/db/migration/V4__id_counter.sql`
- Create: `platform/app/src/main/java/io/nexus/app/event/JdbcIdSource.java`
- Create: `platform/app/src/test/java/io/nexus/app/event/JdbcIdSourceTest.java`

**Interfaces (Produces):**

```java
/** Readable, deterministic business ids such as PO-0001 or TO-0042. */
public interface IdSource {
  /** Next id for the prefix: prefix + "-" + counter, counter zero-padded to 4 digits, starting at 1. */
  String next(String prefix);
  /** Restarts every counter at 1. Only called by the simulation reset, which also clears the business tables. */
  void reset();
}
```

- `MemoryIdSource`: `LinkedHashMap<String,Integer>`; Zählerstand ist Teil der `MemoryUnitOfWork` (bei Rollback zurückgesetzt, über `onRollback`).
- `V4__id_counter.sql`: `create table id_counter (prefix text primary key, value bigint not null)`.
- `JdbcIdSource`: `insert … on conflict (prefix) do update set value = id_counter.value + 1 returning value` (zählt in der Transaktion des Aufrufers mit; ein Rollback gibt die Nummer frei, das ist gewollt und deterministisch). `reset()`: `delete from id_counter`.

**Tests (`IdSourceContract`, abstrakt, läuft gegen Speicher und PostgreSQL):**
- `firstIdIsOneAndPadded`: `next("PO")` = `PO-0001`, dann `PO-0002`.
- `prefixesCountIndependently`: `next("TO")` nach zwei `PO` ist `TO-0001`.
- `resetStartsAgainAtOne`
- `rolledBackUnitReturnsTheNumber`: `next` in `inNewTransaction`, Ausnahme, danach liefert `next` dieselbe Nummer erneut.
- `JdbcIdSourceTest` zusätzlich `concurrentCallersNeverGetTheSameId`: 8 Threads je 50 Aufrufe, 400 verschiedene IDs.
- Ab 9999 wird nicht abgeschnitten: `PO-10000` (Test `widensBeyondFourDigits` mit vorbereitetem Zähler).

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `feat: IdSource für lesbare Geschäfts-IDs`.

---

### Task 2: Maschinen-Ports

**Files:**
- Modify: `platform/contracts/src/main/java/io/nexus/contracts/port/MachineControl.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/port/MachineFeedback.java`

**Interfaces (Produces):**

```java
/** Implemented by the simulation (server: through OPC UA; browser: directly). Called by SmartFactory. */
public interface MachineControl {
  void start(String machineId);
  void stop(String machineId);
  void injectFault(String machineId, String faultTypeId);   // null picks a fault type valid for the machine kind
  void repair(String machineId);
  void maintain(String machineId);
  /** Hands a batch to a machine whose material is in place. The machine reports back through MachineFeedback. */
  void startJob(MachineJob job);

  /** setupMs is 0 when the machine already ran this product. */
  record MachineJob(String machineId, String orderId, int stepSeq, String productId, int quantity,
                    long setupMs, long processMs) {}
}

/** Implemented by SmartFactory. Called by the simulation when something happens at a machine. */
public interface MachineFeedback {
  void statusChanged(String machineId, MachineStatus from, MachineStatus to);
  /** Called right before statusChanged(…, FAULT); scrappedWorkpiece means the running batch is lost. */
  void faultRaised(String machineId, String faultTypeId, String label, boolean scrappedWorkpiece);
  /** good + scrap == quantity of the job. */
  void jobFinished(String machineId, String orderId, int stepSeq, int good, int scrap);
  /** Parts arrived at a machine that is fed by a conveyor (assembly cells hand over to QC on CV-03). */
  void conveyorDelivered(String machineId, String orderId, int stepSeq, int quantity);
}
```

Reine Schnittstellen ohne Verhalten, deshalb ohne eigenen Test; `./mvnw -B -pl platform/contracts verify` muss grün bleiben.

- [ ] Umsetzen, Lauf grün.
- [ ] Commit `feat(contracts): Maschinenaufträge und Rückmeldungen als Ports`.

---

### Task 3: Build-Verdrahtung für zwei parallele Zweige

**Files:**
- Modify: `FlowPilot/pom.xml`, `SmartFactory/pom.xml` (Test-JAR bauen, wie `platform/contracts`)
- Modify: `platform/app/pom.xml` (Test-JARs von `flowpilot` und `smartfactory`, Scope `test`)
- Create: `FlowPilot/src/main/java/io/nexus/flowpilot/FlowPilotSelfTest.java`
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/SmartFactorySelfTest.java`
- Modify: `platform/web/src/main/java/io/nexus/web/WebRuntime.java`
- Modify: `platform/web/src/test/java/io/nexus/web/WebRuntimeTest.java`

**Interfaces (Produces):**
- `FlowPilotSelfTest.run(long seed)` und `SmartFactorySelfTest.run(long seed)`: `public static String`, hier zunächst `"bereit"`. M1-B und M1-C ersetzen nur den Rumpf ihrer eigenen Klasse.
- `WebRuntime.selfTest(seed)` hängt zwei Zeilen an: `flowpilot=<Ausgabe>` und `smartfactory=<Ausgabe>`; mehrzeilige Ausgaben werden mit ` | ` verbunden, damit die Zeilenzahl des Vergleichs stabil bleibt.

**Tests:**
- `WebRuntimeTest`: Ausgabe enthält `flowpilot=bereit` und `smartfactory=bereit`.
- Der Node-Vergleich in `platform/web` deckt die neuen Zeilen automatisch ab.

**Regel für M1-B und M1-C (gehört in beide Pläne, hier festgehalten):** Die Spring-Konfiguration eines Moduls bezieht Ports des anderen Moduls (`MaterialService`, `TransportService`, `ProductCatalog`) und der Simulation (`MachineControl`) über `ObjectProvider` erst beim ersten Aufruf. So startet der Anwendungskontext auf jedem Zweig, auch wenn das andere Modul dort noch fehlt; Tests stellen Fakes über `@TestConfiguration` bereit.

- [ ] Test anpassen, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün einschliesslich Node-Vergleich.
- [ ] Commit `build: Selbsttest-Stubs und Test-JARs für FlowPilot und SmartFactory`.
