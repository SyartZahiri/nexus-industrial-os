# M1-D2 Server-Laufzeit der Anlage – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die `Plant` aus M1-D1 läuft auf dem Server mit PostgreSQL: Simulationsschleife mit Start, Pause, Tempo und Reset, REST-Steuerung, WebSocket-Strom mit Zustandsbild und Ereignissen, und der Nachweis, dass Server, Arbeitsspeicher und Browser bei gleichem Seed dasselbe tun.

**Architecture:** `platform/app` erzeugt die `Plant` mit den vorhandenen JDBC-gestützten Fassaden. Eine `SimulationLoop` (ein Thread) ruft `Plant.advance` im Takt; alle anderen Zugriffe gehen wie bisher über die Fassaden unter dem gemeinsamen Bus-Lock. Reset leert die Bewegungstabellen in einer Transaktion und setzt Uhr, Zähler, Lauf und die Fassaden zurück. Der WebSocket sendet das Zustandsbild im selben JSON wie der Browser (`PlantJson` aus dem Kern), damit die Oberfläche beide Betriebsarten gleich liest.

**Tech Stack:** Java 25, Spring Boot 4.1.2 (Web MVC, WebSocket), PostgreSQL 18 (embedded), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Revision 2), Abschnitte 1, 7 (Threading), 11, 12, 13, 16, 17. Voraussetzung: M1-D1 ist gemergt.

**Form:** wie M1-D1.

## Entscheidungen dieses Plans

- **Reset über `reset()`-Methoden** statt Neuaufbau der Spring-Beans: `FlowPilot`, `SmartFactory`, `EventBus` und `Plant` bekommen je ein `reset`, das ihren Arbeitsspeicher-Zustand aus den (frisch zurückgesetzten) Speichern neu lädt. Controller und Tests behalten dieselben Bean-Instanzen.
- **Bewegungstabellen** (werden beim Reset geleert oder auf Stammdaten zurückgesetzt): `transport_order`, `reservation`, `stock` (auf Anfangsbestand), `agv` (an die Ladeplätze, 100 %), `graph_edge.blocked`, `production_order`, `operation`, `operation_material`, `machine` (Status `IDLE`, kein letztes Produkt), `machine_state_interval`, `maintenance_window`, `id_counter`, `event_delivery_failure`, `processed_event`. Die Tabelle `event` bleibt: Ereignisse alter Läufe bleiben unter ihrer Laufnummer erhalten.
- **WebSocket ohne STOMP**: ein schlichter Text-Handler unter `/ws`. Nachrichten: `{"type":"frame","frame":{…}}` höchstens 10-mal pro Sekunde und nur bei Änderung, `{"type":"events","items":[…]}` mit allen neuen Ereignissen seit der letzten Nachricht, `{"type":"reset","seed":…}` nach einem Reset.
- **MachineControl ist bis M1-D3 die Simulation selbst** (direkter Aufruf, wie im Browser). M1-D3 ersetzt das durch OPC UA.

## Global Constraints

- Alles aus `2026-11-10-m1a2-portabler-kern.md`, Abschnitt Global Constraints, gilt weiter.
- Threading-Regel aus Spec 7: Die Schleife ruft `Plant.advance` und damit `EventBus.retryFailed` ausserhalb jeder offenen Transaktion. Nur ein Thread führt Simulationsschritte aus.
- REST-Fehler als Problem-JSON nach Spec 16.
- Commits enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Befehl während eines Schritts.** Ein REST-Befehl (Auftrag anlegen, Maschine stören) mitten in einem laufenden Schritt wartet auf den Lock und wird danach angewendet; nichts geht verloren, nichts hängt. Test in Task 2.
2. **Reset während die Schleife läuft.** Die Schleife hält an, der Reset läuft, danach ist der Zustand gleich einem frischen Start, und die Schleife läuft weiter, wenn sie vorher lief. Test in Task 1.
3. **Langsamer Client am WebSocket.** Ein Client, der nicht liest, bremst weder die Simulation noch andere Clients; nach einem Rückstand bekommt er das neueste Zustandsbild statt aller alten. Test in Task 3.
4. **Fehler im Schritt.** Wirft ein Schritt eine unerwartete Ausnahme, hält die Schleife an, meldet `decision` mit Schweregrad `ERROR` („Simulation angehalten") und lässt sich danach wieder starten. Test in Task 2.
5. **Server weicht vom Speicher ab.** Gleicher Seed und dieselben Befehle ergeben auf dem Server dieselbe Ereignisfolge und dasselbe Zustandsbild wie `Plant.inMemory`. Test in Task 4.

---

### Task 1: Reset im Kern und auf dem Server

**Files:**
- Modify: `FlowPilot/src/main/java/io/nexus/flowpilot/FlowPilot.java` (`reset()`), `SmartFactory/src/main/java/io/nexus/smartfactory/SmartFactory.java` (`reset()`), `platform/contracts/src/main/java/io/nexus/contracts/event/EventBus.java` (`reset()`), `platform/plant/src/main/java/io/nexus/plant/Plant.java` (`reset(long seed)`)
- Create: `platform/app/src/main/java/io/nexus/app/plant/PlantReset.java`
- Test: `FlowPilot/src/test/java/io/nexus/flowpilot/FlowPilotResetTest.java`, `SmartFactory/src/test/java/io/nexus/smartfactory/SmartFactoryResetTest.java`, `platform/plant/src/test/java/io/nexus/plant/PlantResetTest.java`, `platform/app/src/test/java/io/nexus/app/plant/ServerResetTest.java`

**Interfaces (Produces):**
- `EventBus.reset()`: leert die Warteschlange; registrierte Verbraucher bleiben.
- `FlowPilot.reset()`: lädt Fahrzeuge (Position = Knoten aus dem Speicher, Geschwindigkeit 0, keine Route), Kantensperren und offene Transporte neu, verwirft Nachbestellungen unterwegs.
- `SmartFactory.reset()`: verwirft den zwischengespeicherten Plan, setzt die Dispositionsregel auf `PRIORITY`, plant neu.
- `Plant.reset(long seed)`: neuer `SeededRandom(seed)` mit denselben Teilströmen, neue `Simulation` (Maschinen kalt, kein Verschleiss), Generatoren zurückgesetzt. Erwartet, dass Speicher, Uhr, Zähler und Lauf vorher zurückgesetzt wurden (im Speicher: `Plant.inMemory` neu erzeugen; auf dem Server: `PlantReset`).
- `PlantReset.reset(long seed)` (Server): hält die Schleife an, setzt in einer Transaktion die Bewegungstabellen zurück (Liste oben), dann `IdSource.reset`, `SimRun.restart(seed)`, `SimTime.reset`, `bus.reset`, `flowPilot.reset`, `smartFactory.reset`, `plant.reset(seed)`; startet die Schleife wieder, wenn sie lief.

**Tests:**
- Kern: nach Reset sind Fahrzeuge an ihren Ladeplätzen, Plan leer, Warteschlange leer.
- `PlantResetTest`: Lauf 30 min, Reset mit Seed 7, dann 30 min → Ereignisfolge gleich einem frischen `Plant.inMemory(7)` über 30 min.
- `ServerResetTest`: dasselbe auf dem Server (Ereignisse des neuen Laufs, ohne Laufnummer verglichen); Reset während laufender Schleife (Review Focus 2); `event` enthält danach Ereignisse beider Läufe unter verschiedenen Laufnummern.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `feat: Reset der Anlage im Kern und auf dem Server`.

---

### Task 2: Anlage auf dem Server, Simulationsschleife, REST

**Files:**
- Create: `platform/app/src/main/java/io/nexus/app/plant/PlantConfig.java`, `SimulationLoop.java`, `SimController.java`
- Modify: `platform/app/src/main/java/io/nexus/app/smartfactory/SmartFactoryConfig.java` (MachineControl kommt aus `PlantConfig`)
- Test: `platform/app/src/test/java/io/nexus/app/plant/SimulationLoopTest.java`, `SimApiTest.java`

**Interfaces (Produces):**
- Beans: `Simulation` (Maschinen aus `SmartFactoryMasterData`, Rückmeldungen an die `SmartFactory`-Bean, Zufall `SeededRandom(seed).fork("sim")`), `MachineControl` (= die Simulation), `Plant`, `SimulationLoop`, `PlantReset`.
- `SimulationLoop`: ein Daemon-Thread `nexus-sim`. Alle 50 ms Echtzeit: `sollSim += 50 × speed`, `plant.advance(ganze Schritte)`; höchstens 50 Schritte je Takt (bei Rückstand wird Zeit verworfen statt aufzuholen, damit die Anlage bedienbar bleibt). `play()`, `pause()`, `setSpeed(double)` (0,25..25), `running()`, `speed()`. Startet pausiert. Ausnahme im Schritt → Pause, `decision` mit `ERROR` und Fehlerklasse (Review Focus 4).
- REST unter `/api/v1/sim`:

| Methode | Pfad | Antwort |
|---|---|---|
| GET | `/sim` | `{simTimeMs, running, speed, seed, runId, rules}` |
| POST | `/sim/play`, `/sim/pause` | 204 |
| POST | `/sim/reset` | `{seed?}` → 204 (ohne Seed: bisheriger) |
| PUT | `/sim/speed` | `{speed}` (0,25..25) → 204, sonst 400 |
| PUT | `/sim/rules` | `PlantRules` als JSON → 204; ungültige Werte 400 |
| GET | `/sim/frame` | Zustandsbild (JSON aus `PlantJson`) |

**Tests:**
- `SimulationLoopTest`: `play` lässt die Simulationszeit steigen, `pause` hält sie an; Tempo 10 ergibt in 2 s Echtzeit 16..24 s Simulationszeit; Rückstand wird begrenzt; Ausnahme im Schritt pausiert und meldet (Review Focus 4).
- `commandDuringAStepIsAppliedAfterIt` (Review Focus 1): 20 REST-Aufträge parallel zur laufenden Schleife bei Tempo 25, alle angelegt, keiner doppelt, kein Timeout.
- `SimApiTest`: je Endpunkt Erfolg und Validierung; `/sim/frame` enthält sieben Maschinen und sechs AGVs; ein per REST angelegter Auftrag erreicht bei laufender Schleife mit Tempo 25 innerhalb von 120 s Echtzeit `COMPLETED`.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `feat(app): Simulationsschleife und Steuerung der Anlage`.

---

### Task 3: WebSocket

**Files:**
- Create: `platform/app/src/main/java/io/nexus/app/plant/PlantSocket.java`, `SocketConfig.java`
- Test: `platform/app/src/test/java/io/nexus/app/plant/PlantSocketTest.java`

**Interfaces (Produces):**
- `/ws` (Text-WebSocket, gleiche Herkunft und `http://localhost:5173`/`5179` für den Vite-Dev-Server erlaubt).
- Ein Sende-Thread `nexus-ws` liest alle 100 ms das Zustandsbild (unter dem Lock, nur wenn sich die Simulationszeit oder eine Revision geändert hat) und die neuen Ereignisse (über `EventLog`/Abfrage nach `seq`, nur aktueller Lauf) und schickt sie an alle Clients.
- Je Client eine Warteschlange mit höchstens einem ausstehenden Zustandsbild (ein neueres ersetzt das ältere) und gesammelten Ereignissen; gesendet wird über `ConcurrentWebSocketSessionDecorator` mit Zeit- und Puffergrenze, ein hängender Client wird getrennt (Review Focus 3).
- Nach `PlantReset`: `{"type":"reset","seed":…}`, danach das neue Zustandsbild.

**Tests:**
- `clientReceivesAFrameAndNewEvents` (Spring-WebSocket-Client im Test)
- `eventsArriveOnceAndInOrder`
- `slowClientDoesNotBlockOthersAndGetsTheLatestFrame` (Review Focus 3)
- `resetIsAnnounced`
- `foreignOriginIsRejected`

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `feat(app): WebSocket mit Zustandsbild und Ereignissen`.

---

### Task 4: Ende-zu-Ende auf dem Server und Gleichheit der Laufzeiten

**Files:**
- Nutzt: `Scenario1h` aus M1-D1
- Test: `platform/app/src/test/java/io/nexus/app/plant/RuntimeEquivalenceTest.java`

**Test (`RuntimeEquivalenceTest`):** Schleife pausiert, `PlantReset.reset(4711)`, `Scenario1h.run(serverPlant)`; dasselbe mit `Plant.inMemory(4711, …)`, beide mit `autoOrders = false`. Beide Ergebnisse sind Zeichen für Zeichen gleich, und gleich `target/plant-jvm.txt` aus dem Browser-Vergleich (Review Focus 5). Weichen sie ab, nennt der Test die erste abweichende Zeile.

- [ ] Test schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `test: Server, Arbeitsspeicher und Browser liefern denselben Ablauf`.

## Nach M1-D2

- M1-D3: OPC UA (Eclipse Milo) zwischen Simulation und SmartFactory auf dem Server, mit synchronem Lesen je Schritt, damit der Determinismus erhalten bleibt; Verbindungsabbruch und ungültige Werte nach Spec 9.
- Danach M2 (Oberfläche) und, parallel dazu möglich, der Kern von RouteMind (M4).
