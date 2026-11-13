# M1-D1 Simulation und Anlage – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Maschinen simulieren und FlowPilot, SmartFactory und Maschinen zu einer laufenden Anlage verbinden, sodass ein Auftrag im Arbeitsspeicher und im Browser-Build vollständig vom Anlegen bis ins Fertiglager läuft, deterministisch und auf JVM und JavaScript gleich.

**Architecture:** `platform/sim` bekommt die Maschinenphysik (Rüsten, Bearbeiten, Temperatur, Verschleiss, Störung, Reparatur, Wartung, Bandübergabe, Prüfergebnis) und implementiert `MachineControl`; Rückmeldungen gehen über `MachineFeedback` an SmartFactory. Ein neues Kernmodul `platform/plant` verdrahtet Simulation, FlowPilot, SmartFactory und EventBus zu einer `Plant` mit festem Schritt, Regeln (zufällige Störungen, automatische Aufträge, Versand) und einem Zustandsbild (`PlantFrame`) für die Oberfläche. Das Browser-Modul bekommt eine Schnittstelle, über die ein Web Worker die Anlage starten, laufen lassen und abfragen kann.

**Tech Stack:** Java 25, Maven, TeaVM 0.16.0, Node 24 (Vergleichslauf), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Revision 2), Abschnitte 1, 2a, 4, 8, 11, 14, 17. Grundlage: `main` ab `0ff1da0` (M1-A bis M1-C gemergt).

**Einordnung:** M1-D ist in drei Pläne geteilt. M1-D1 (dieser) läuft vollständig im Kern und im Browser. M1-D2 bringt die Anlage auf den Server (Simulationsschleife, Reset, REST, WebSocket, Vergleich Server gegen Speicher). M1-D3 legt OPC UA zwischen Simulation und SmartFactory; er wird nach M1-D2 gegen den dann echten Code geschrieben.

**Form:** wie M1-B/M1-C: Schnittstellen, Verhalten und Tests festgelegt, wörtlicher Code nur bei heiklen Stellen.

## Entscheidungen dieses Plans

- **Neues Kernmodul `platform/plant` (`nexus-plant`).** Es hängt von allen Kernmodulen ab und enthält die einzige Stelle, die Schrittfolge und Regeln kennt. Server (M1-D2) und Browser nutzen dieselbe `Plant`, nur mit anderen Speichern. Die Spec (Abschnitt 4) wird um das Modul ergänzt.
- **Schrittfolge** (Spec 11), Schritt 100 ms Simulationszeit: `SimTime.advance` → Regeln (Aufträge, Versand) → Maschinen (`Simulation.step`, Rückmeldungen an SmartFactory) → `FlowPilot.step` → `EventBus.retryFailed`.
- **Versand ins Leere.** Bis RouteMind (M4) Fertigware abholt, nimmt eine Versandregel Fertigware aus dem Lager, sobald ein Lagerplatz zu mehr als 70 % gefüllt ist. FlowPilot bekommt dafür einen Warenausgang `issue`. Ohne das liefe das Fertiglager nach rund einer Stunde Simulationszeit voll.
- **Offene Punkte aus M1-B/M1-C, hier entschieden:**
  - *Abgebrochener Transport:* FlowPilot gibt einen Transport nie auf (`FAILED` wird immer neu zugewiesen), stornieren kann nur SmartFactory. Ein voller Fertigwarenplatz lässt den Transport warten, bis die Versandregel Platz schafft. Ein Ende-zu-Ende-Test sichert das ab; SmartFactory muss `transport.failed` deshalb nicht verarbeiten.
  - *Doppeltes `machine.status`:* Nur SmartFactory veröffentlicht `machine.status`. Simulation und später der OPC-UA-Client melden ausschliesslich über `MachineFeedback`.
- **Prüfergebnis je Los.** Die Prüfzelle bewertet jedes Teil des Loses mit einer Ausschusswahrscheinlichkeit, die aus der Vorgeschichte des Auftrags wächst (Verschleiss, Übertemperatur, Override an den vorherigen Maschinen), wie in `FactoryTwin/src/sim/engine.ts`.
- **Förderbänder** übergeben nur Lose von den Montagezellen zur Prüfzelle. Bandstörungen gibt es in M1-D noch nicht.
- **JSON im Kern.** Der Browser-Build hat kein Jackson. Ein kleiner `JsonWriter` und `JsonReader` im Kern erzeugen und lesen das Zustandsbild und Befehle. M1-D2 prüft, dass der Server über Jackson exakt dasselbe JSON liefert.

## Global Constraints

- Alles aus `2026-11-10-m1a2-portabler-kern.md`, Abschnitt Global Constraints, gilt weiter (Kern ohne Spring/JDBC, `SeededRandom`, `SimTime`, Zeichenketten-IDs, Determinismusregeln aus Spec 2a).
- Physik-Werte aus `FactoryTwin/src/sim/config.ts` (`MACHINE_PHYSICS`, `AMBIENT_TEMP 22`, `THERMAL_TAU 110`, `FAULT_TYPES`, `DEFAULT_MACHINE_CONFIG`, `DEFAULT_RULES`); Formeln aus `engine.ts` (`updateMachine`, `completeJob`, `injectFault`, `beginRepair`, `startMaintenance`). Gaussches Rauschen aus `SeededRandom` als Summe von 12 `nextDouble()` minus 6 (Irwin-Hall). Nur Addition: `log`, `sin` und `cos` dürfen zwischen JVM und JavaScript im letzten Bit abweichen und sind im Kern verboten (Spec 2a). `Math.pow` mit ganzzahligem Exponenten als Multiplikation, `Math.pow(ov, 1.6)` aus `engine.ts` als vorab tabellierter Wert je Override-Prozent (50..130).
- `platform/plant` steht unter dem Portabilitäts-Enforcer wie die anderen Kernmodule.
- Commits enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Störung mitten im Rüsten oder Bearbeiten.** Eine Störung ohne Ausschuss lässt das Los nach der Reparatur an derselben Stelle weiterlaufen; mit Ausschuss meldet die Simulation `faultRaised(…, scrapped = true)` und kein `jobFinished`. Test in Task 1.
2. **Befehl im falschen Zustand.** `startJob` an eine nicht freie Maschine wird abgelehnt und ändert nichts (SmartFactory prüft vorher, die Simulation ist die zweite Sicherung). Test in Task 1.
3. **Volles Fertiglager.** Ein Auftrag endet trotzdem in `COMPLETED`, sobald die Versandregel Platz schafft. Test in Task 4.
4. **Lange Läufe.** Vier Stunden Simulationszeit mit zufälligen Störungen: kein Auftrag hängt länger als eine Stunde ohne Fortschritt, keine Invariante von FlowPilot und SmartFactory verletzt. Test in Task 4.
5. **JVM und Browser weichen ab.** Eine Stunde Simulationszeit mit gleichem Seed ergibt auf beiden Laufzeiten dieselbe Ereignisfolge und dasselbe Zustandsbild. Test in Task 5.

---

### Task 1: Maschinensimulation

**Files:**
- Create: `platform/sim/src/main/java/io/nexus/sim/MachinePhysics.java`, `FaultType.java`, `MachineSim.java`, `Simulation.java`, `SimRules.java`
- Test: `platform/sim/src/test/java/io/nexus/sim/MachineSimTest.java`, `SimulationTest.java`

**Interfaces (Produces):**

```java
public record SimRules(boolean randomFaults, boolean autoRepair, boolean autoMaintenance, double maintenanceThreshold) {
  public static final SimRules DEFAULT = new SimRules(true, true, true, 0.85);
}

/** All machines of the plant. Implements MachineControl; reports through MachineFeedback. Single-threaded. */
public final class Simulation implements MachineControl {
  public record MachineSpec(String id, MachineKind kind) {}
  public record MachineView(String id, MachineStatus status, String orderId, int stepSeq, double progress,
                            double temperature, double vibration, double power, double spindle, double wear,
                            String faultLabel) {}

  public Simulation(List<MachineSpec> machines, SeededRandom random, MachineFeedback feedback);
  public void setRules(SimRules rules);
  public SimRules rules();
  /** Advances every machine by dtMs in id order. Feedback calls happen inside this method. */
  public void step(long dtMs);
  public List<MachineView> machines();                       // ordered by id
  public void configure(String machineId, int overridePercent, boolean coolant);   // 50..130, CNC only for coolant
}
```

Verhalten je Maschine (Status aus `MachineStatus`, Übergänge nur nach `MachineStatus.RULES`, jede Änderung über `feedback.statusChanged`):
- `startJob(job)`: nur aus `IDLE`, sonst `IllegalTransitionException`. `setupMs > 0` → `SETUP`, sonst `RUNNING`. Rüsten und Bearbeiten laufen mit `override/100` schneller.
- Fertig: `feedback.jobFinished(machine, order, seq, quantity, 0)` (Ausschuss entsteht nur in der Prüfzelle, Task 2), dann `IDLE`.
- Temperatur, Vibration, Leistung, Drehzahl nach den Formeln aus `updateMachine`; Thermoschutz bei `tempTrip` löst `OVERTEMP` aus.
- Verschleiss wächst beim Bearbeiten (`lifetime`), Wartung setzt ihn zurück; automatische Wartung aus `IDLE` ab `maintenanceThreshold`.
- Zufällige Störungen beim Bearbeiten nach der Hazard-Formel (MTBF, Verschleiss, Temperatur, Override), Typen ohne `OVERTEMP` und `ESTOP`.
- `injectFault(id, typeId)`: aus jedem Betriebszustand; unbekannter oder unpassender Typ → `DomainException.invalid`; null wählt zufällig. Ablauf: `feedback.faultRaised(…, scrapped)`, dann `FAULT`. Mit `scrapsWorkpiece` und laufendem Los ist das Los verloren (kein `jobFinished`). Sonst bleibt der Fortschritt erhalten.
- Reparatur: automatisch nach 15–35 s (`autoRepair`) oder per `repair`; Dauer = `repairTime × range(0.85, 1.2)`; danach zurück in den Zustand vor der Störung (`resumeAfterDowntime`).
- `start`, `stop`, `maintain` wie in `engine.ts` (`stopMachine` hält ein laufendes Los an, `start` setzt es fort).

**Tests (`MachineSimTest`, `SimulationTest`, Feedback als aufzeichnender Fake):**
- `jobRunsSetupThenProcessingAndReportsFinished` (Dauer exakt `setupMs + processMs` bei Override 100)
- `overrideShortensTheJobAndHeatsTheMachine`
- `startJobOnABusyMachineIsRejected` (Review Focus 2)
- `faultWithoutScrapResumesAfterRepairWhereItStopped` (Review Focus 1)
- `scrappingFaultLosesTheBatchAndSendsNoJobFinished` (Review Focus 1)
- `thermalProtectionTripsWithoutCoolantAtHighOverride`
- `autoRepairStartsAfterTheDelayAndEndsAfterTheRepairTime`
- `wornMachineGoesToMaintenanceWhenIdle`
- `randomFaultsFollowTheSeed`: zwei Simulationen mit gleichem Seed, gleiche Störungszeitpunkte; mit `randomFaults = false` keine.
- `everyStatusChangeIsALegalTransition` (Eigenschaftstest über 8 h zufälliger Befehle)

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B -pl platform/sim -am test` grün.
- [ ] Commit `feat(sim): Maschinensimulation mit Physik, Störungen und Wartung`.

---

### Task 2: Bandübergabe und Prüfergebnis

**Files:**
- Create: `platform/sim/src/main/java/io/nexus/sim/ConveyorSim.java`, `QualityModel.java`
- Modify: `Simulation.java`
- Test: `platform/sim/src/test/java/io/nexus/sim/ConveyorAndQualityTest.java`

**Interfaces (Produces):**
- `Simulation` bekommt zusätzlich `List<ConveyorLink>` (`fromMachineId`, `conveyorId`, `toMachineId`, `distanceM`, `speedMps`) im Konstruktor. Montagezellen legen ihr fertiges Los auf das Band; es kommt nach `distance / speed` an der Prüfzelle an (`feedback.conveyorDelivered`). Abstände aus `initialState.ts`: ROB-01 legt bei 6,7 m auf CV-03 ab, ROB-02 bei 15,7 m, die Prüfzelle nimmt am Ende bei 27 m ab; Geschwindigkeit 0,6 m/s.
- `Simulation.ConveyorView(String conveyorId, List<Item>)` mit `Item(orderId, stepSeq, positionM)` für das Zustandsbild.
- `QualityModel`: je Auftrag ein Risiko, das jedes beendete Los erhöht: `0.003 + 0.05 × wear² + (temperature > tempWarn ? 0.07 : 0) + (override > 110 ? (override/100 − 1.1) × 0.25 : 0)`. Die Prüfzelle zieht für jedes Teil des Loses einmal `nextDouble() < risk`; Ausschuss = Anzahl Treffer. `jobFinished` der Prüfzelle meldet `good`/`scrap`.

**Tests:**
- `assemblyBatchArrivesAtQcAfterTheConveyorTime` (ROB-01: (27 − 6,7) / 0,6 s)
- `twoBatchesOnTheBeltKeepTheirOrder`
- `qcScrapGrowsWithWearAndHeatOfEarlierSteps` (gleicher Seed: verschlissene, heisse Vorgeschichte ergibt mehr Ausschuss als neue Maschinen über 200 Lose)
- `goodPlusScrapIsTheBatchQuantity`

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, Lauf grün.
- [ ] Commit `feat(sim): Bandübergabe zur Prüfzelle und Prüfergebnis je Los`.

---

### Task 3: Warenausgang in FlowPilot

**Files:**
- Modify: `FlowPilot/src/main/java/io/nexus/flowpilot/material/MaterialServiceImpl.java`, `FlowPilot/src/main/java/io/nexus/flowpilot/FlowPilot.java`
- Modify: `platform/app/src/main/java/io/nexus/app/flowpilot/FlowPilotController.java` (`POST /api/v1/stock/issues`)
- Test: `FlowPilot/src/test/java/io/nexus/flowpilot/material/MaterialServiceTest.java`, `platform/app/src/test/java/io/nexus/app/flowpilot/FlowPilotApiTest.java`

**Interfaces (Produces):** `FlowPilot.issue(String materialId, String locationId, int quantity)` (unter dem Plattform-Lock, eigene Einheit): nimmt nur freie Menge (`onHand − reserved`); zu wenig → `DomainException.conflict`; Menge ≤ 0 → `invalid`; Ereignis `stock.changed`.

**Tests:** `issueTakesOnlyUnreservedStock`, `issueOfMoreThanAvailableIsAConflict`, API: 204, 400, 409.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `feat(flowpilot): Warenausgang`.

---

### Task 4: Modul `platform/plant`: Anlage, Regeln, Ende-zu-Ende

**Files:**
- Create: `platform/plant/pom.xml` (`nexus-plant`, abhängig von `nexus-contracts`, `nexus-sim`, `flowpilot`, `smartfactory`; Enforcer aktiv)
- Modify: `pom.xml` (Modul vor `platform/app`), `platform/web/pom.xml` und `platform/app/pom.xml` (Abhängigkeit `nexus-plant`)
- Create: `platform/plant/src/main/java/io/nexus/plant/Plant.java`, `PlantRules.java`, `OrderGenerator.java`, `Shipping.java`, `PlantFrame.java`
- Test: `platform/plant/src/test/java/io/nexus/plant/PlantEndToEndTest.java`, `PlantLongRunTest.java`
- Modify: `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Abschnitt 4: Modul `platform/plant`; Abschnitt 11: Schrittfolge wie oben; Abschnitt 2a: Kernmodule)

**Interfaces (Produces):**

```java
public record PlantRules(SimRules machines, boolean autoOrders, long autoOrderIntervalMs, int maxOpenOrders,
                         boolean shipping, DispatchRule dispatchRule) {
  public static final PlantRules DEFAULT =
      new PlantRules(SimRules.DEFAULT, true, 360_000, 6, true, DispatchRule.PRIORITY);
}

public final class Plant {
  public static final long STEP_MS = 100;
  /** Everything in memory: the browser runtime and core tests. */
  public static Plant inMemory(long seed, PlantRules rules);
  /** With existing facades: the server runtime (M1-D2) passes its JDBC-backed modules. */
  public Plant(EventBus bus, SimTime time, SeededRandom random, FlowPilot flowPilot, SmartFactory smartFactory,
               Simulation simulation, PlantRules rules);
  /** Runs whole steps of STEP_MS until at least simMs have passed; returns the number of steps. */
  public int advance(long simMs);
  public void setRules(PlantRules rules);
  public PlantRules rules();
  public FlowPilot flowPilot();
  public SmartFactory smartFactory();
  public Simulation simulation();
  public PlantFrame frame();
  public long nowMs();
}

public record PlantFrame(long simTimeMs, List<Simulation.MachineView> machines, List<Simulation.ConveyorView> conveyors,
                         List<FlowPilot.VehicleView> agvs, List<StockView> stock, List<OrderView> orders,
                         List<TransportView> transports) {
  public record StockView(String materialId, String locationId, int onHand, int reserved) {}
  public record OrderView(String id, String productId, int quantity, String priority, String status, long dueAtMs,
                          int good, int scrapped, int stepsDone, int steps) {}
  public record TransportView(String id, String kind, String orderId, String materialId, int quantity,
                              String status, String agvId, String from, String to) {}
}
```

Verhalten:
- `inMemory`: ein `EventBus` mit `MemoryEventLog`/`MemoryUnitOfWork`, `MemoryIdSource`, `SeededRandom(seed)`; Teilströme `fork("sim")`, `fork("orders")`; FlowPilot und SmartFactory `inMemory`; `Simulation` mit den Maschinen aus `SmartFactoryMasterData` und den Bandverbindungen; SmartFactory bekommt die Simulation als `MachineControl`, die Simulation SmartFactory als `MachineFeedback`.
- `advance`: je Schritt `time.advance(STEP_MS)`, `OrderGenerator.step` (bei `autoOrders`: im Abstand `autoOrderIntervalMs`, solange weniger als `maxOpenOrders` offen sind, ein Auftrag mit zufälligem Produkt, Menge 3..10, Priorität 70 % NORMAL / 20 % HOCH / 10 % EILIG, Termin 45..90 min), `Shipping.step` (bei `shipping`: Fertigwarenplatz über 70 % → `flowPilot.issue` der grössten freien Menge bis auf 50 %), `simulation.step`, `flowPilot.step`, `bus.retryFailed()`.
- `frame()`: Momentaufnahme in fester Reihenfolge (Maschinen, Bänder, AGVs nach ID; Bestand nach Material, Platz; offene und die letzten 20 abgeschlossenen Aufträge nach ID; aktive Transporte nach ID).

**Tests (`PlantEndToEndTest`, `autoOrders = false`, `randomFaults = false`):**
- `everyProductRunsFromOrderToFinishedStock`: je Produkt ein Auftrag über `smartFactory().createOrder`; `advance` bis alle `COMPLETED` (höchstens 2 h). Erwartet: Ereignisfolge je Auftrag enthält in dieser Reihenfolge `order.created`, `material.reserved`, `transport.created`, `transport.delivered`, `order.released`, `operation.started`, `operation.completed`, `order.completed`; der Fertigwarenbestand ist um die Gutteile gestiegen, der Rohstoff um die Menge gesunken.
- `machineFaultDuringAnOrderIsReplannedAndTheOrderCompletes` (Störung an der Fräse des Auftrags, Ausweichmaschine)
- `agvFaultDuringASupplyTransportIsRescuedAndTheOrderCompletes`
- `fullFinishedStockDelaysButDoesNotBlockAnOrder` (Fertiglager vorher bis auf 2 Plätze gefüllt, `shipping = true`; Review Focus 3)
- `sameSeedSameEvents`

**Test (`PlantLongRunTest`):** 4 h Simulationszeit, Standardregeln, Seeds 1..4. Nach jedem Schritt die Invarianten aus `FleetInvariantTest` (FlowPilot) und: kein Auftrag ohne Statuswechsel oder Arbeitsgangfortschritt länger als 60 min (ausser in `SHORTAGE` mit laufender Nachbestellung); mindestens 15 abgeschlossene Aufträge je Lauf (Review Focus 4).

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `feat(plant): Anlage aus Simulation, FlowPilot und SmartFactory mit Regeln und Zustandsbild`.

---

### Task 5: JSON im Kern und Browser-Schnittstelle

**Files:**
- Create: `platform/contracts/src/main/java/io/nexus/contracts/json/JsonWriter.java`, `JsonReader.java`
- Create: `platform/plant/src/main/java/io/nexus/plant/PlantJson.java`, `PlantCommands.java`, `Scenario1h.java`
- Modify: `platform/web/src/main/java/io/nexus/web/WebRuntime.java`
- Create: `platform/web/src/test/js/plant-run.mjs`
- Modify: `platform/web/pom.xml` (zweiter Node-Lauf in `verify`)
- Test: `platform/contracts/src/test/java/io/nexus/contracts/json/JsonTest.java`, `platform/plant/src/test/java/io/nexus/plant/PlantJsonTest.java`, `PlantCommandsTest.java`, `platform/web/src/test/java/io/nexus/web/WebRuntimeTest.java`

**Interfaces (Produces):**
- `JsonWriter`: Objekte, Arrays, Zeichenketten (Escape nach RFC 8259, Nicht-ASCII unverändert), `long`, `int`, `boolean`, `null`, `double` als kürzeste Darstellung, die `Double.parseDouble` zurück auf denselben Wert bringt (eigene Implementierung über `Double.toString`, wie Jackson; keine Ausgabe von `NaN`/`Infinity` – dort `IllegalArgumentException`).
- `JsonReader`: liest Objekte, Arrays, Zeichenketten, Zahlen, `true`/`false`/`null` in `Map<String,Object>` (LinkedHashMap), `List<Object>`, `String`, `Double`/`Long`, `Boolean`; Fehler mit Position.
- `PlantJson.frame(PlantFrame)` und `PlantJson.events(List<NexusEvent>)`: Feldnamen wie die Record-Komponenten, Enums als Name; Nutzlasten mit dem Typnamen ihres Records in `"payloadType"`.
- `PlantCommands.apply(Plant, String json)` → JSON-Antwort `{"ok":true,"result":…}` oder `{"ok":false,"status":400|404|409,"message":…}`. Befehle (Feld `"command"`): `createOrder`, `changeOrder`, `cancelOrder`, `setDispatchRule`, `machineStart`, `machineStop`, `machineFault`, `machineRepair`, `machineMaintain`, `machineConfigure`, `agvEnable`, `agvDisable`, `agvCharge`, `agvFault`, `edgeBlock`, `edgeUnblock`, `stockReceipt`, `setRules`. Fehler der Fachlogik (`DomainException`, `IllegalTransitionException`) werden zu Status wie auf dem Server (Spec 16).
- `Scenario1h.run(Plant)` → `String`: je Produkt ein Auftrag bei t = 0, Störung an `CNC-01` bei 20 min, AGV-Störung an `AGV-02` bei 30 min, Expressauftrag bei 40 min, `advance` bis 60 min. Ergebnis: eine Zeile je Ereignis (Typ, Simulationszeit, Korrelation, Nutzlast als JSON aus `PlantJson`; ohne Laufnummer), danach das Zustandsbild. M1-D2 vergleicht den Server mit genau diesem Text.
- `WebRuntime` (`@JSExport`): `plantStart(int seed)`, `plantAdvance(double simMs)` → Anzahl Schritte, `plantFrame()` → JSON, `plantEvents(String afterEventId)` → JSON-Array der Ereignisse danach, `plantCommand(String json)` → JSON. Eine Anlage je Modulinstanz (der Web Worker hält genau eine).

**Tests:**
- `JsonTest`: Rundlauf beliebiger Strukturen, Escapes, Umlaute, `0.1 + 0.2`, `1e-7`, große `long`, Fehlerposition bei kaputtem JSON.
- `PlantJsonTest`: Zustandsbild nach 10 min ist gültiges JSON und enthält alle Maschinen, AGVs und Aufträge.
- `PlantCommandsTest`: je Befehl ein Erfolgsfall; unbekannter Befehl 400; unbekannte Maschine 404; Reparatur einer laufenden Maschine 409; kaputtes JSON 400.
- `WebRuntimeTest` schreibt `Scenario1h.run(Plant.inMemory(4711, PlantRules.DEFAULT mit autoOrders = false))` nach `target/plant-jvm.txt`; `WebRuntime` exportiert dafür zusätzlich `plantScenario1h(int seed)`. `plant-run.mjs` erzeugt dasselbe über das TeaVM-Modul und vergleicht Zeichen für Zeichen (Review Focus 5).

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün einschliesslich beider Node-Läufe.
- [ ] Commit `feat: JSON im Kern und Anlagen-Schnittstelle für den Browser`.
