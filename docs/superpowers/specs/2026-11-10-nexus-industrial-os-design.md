# NEXUS Industrial OS – Design

Stand: 2026-11-10. Status: zur Freigabe.

## 1. Ziel

Aus dem bestehenden Browser-Projekt FactoryTwin und zwei neuen Systemen, FlowPilot (Materialfluss) und SmartFactory (MES), entsteht eine integrierte Anwendung für ein Softwareentwicklungs-Portfolio. Sie simuliert eine kleine Fertigung nachvollziehbar von der Auftragsanlage bis zur Einlagerung.

Erfolgskriterien:

- Ein Auftrag durchläuft Materialreservierung, AGV-Transport, Produktionsfreigabe, Fertigung, Abschluss und Einlagerung, und alle drei Systeme zeigen denselben Zustand.
- Jede Kennzahl ist aus protokollierten Zustandsänderungen berechnet.
- Gleicher Seed und gleiche Eingriffe ergeben dasselbe Ereignisprotokoll.
- Der Full-Stack-Betrieb läuft gegen das echte Backend. GitHub Pages zeigt eine Aufzeichnung davon.

## 2. Getroffene Entscheidungen

| Entscheidung | Wahl | Folge |
|---|---|---|
| Ort der Fachlogik | Nur im Java-Backend | `FactoryTwin/src/sim` und die Adapter in `src/integration` entfallen. Die 14 Vitest-Tests der Engine werden durch Java-Tests ersetzt. |
| Online-Demo | Replay aufgezeichneter Backend-Frames | Nicht interaktiv steuerbar. Kamera, Auswahl und Zeitleiste bleiben bedienbar. |
| Datenbank lokal | Embedded PostgreSQL (`io.zonky.test:embedded-postgres`) | Echtes PostgreSQL ohne Installation. Docker Compose wird geschrieben, auf dem Entwicklungsrechner aber nicht ausgeführt. |
| Deployment-Form | Ein Spring-Boot-Prozess, eine Datenbank | Keine Microservices, kein Message-Broker. |

## 3. Ausgangslage FactoryTwin

React 19, TypeScript 7, Vite 8, React Three Fiber, Tailwind 4, Zustand. Kein Backend, kein Git-Repository, kein CI.

Erhalten bleiben: `src/scene` (3D-Modelle, Materialien, Kamera), `src/ui` (Primitives, Panels, SVG-Charts) und die Hallengeometrie aus `src/sim/layout.ts` (wandert als Stammdaten ins Backend).

Ersetzt werden: `src/sim/engine.ts` und zugehörige Dateien (Logik wandert nach Java), `src/integration` (ersetzt durch REST/WebSocket-Client), `src/store/factoryStore.ts` (liest künftig aus dem Twin-Laufzeitobjekt statt aus der Engine).

Bestehende IDs werden übernommen: Maschinen `CNC-01` bis `CNC-04`, `ROB-01`, `ROB-02`, `QC-01`; Förderbänder `CV-01` bis `CV-04`; Ladestationen `CH-1` bis `CH-6`; Produkte `GH200`, `AW50`, `FP12`, `MT7`; Prozesse `DREHEN`, `FRAESEN`, `MONTAGE`, `PRUEFEN`.

## 4. Repository

```
Industire/                    Git-Monorepo
  pom.xml                     Maven-Parent, Java-Release 21, Maven Wrapper
  platform/
    contracts/                IDs, Enums, Events, Ports (keine Abhängigkeit auf Spring)
    sim/                      Simulationsuhr, Seed-RNG, Maschinen-/Band-Simulatoren, OPC-UA-Server
    app/                      Spring Boot: REST, WebSocket, Flyway, Audit, Szenarien, Aufzeichnung
  FlowPilot/                  Material, Lager, Reservierung, Transport, Flotte, Routing, OPC-UA-Client
  SmartFactory/               Aufträge, Arbeitspläne, Planung, Maschinenzustand, Fehlerbehandlung, Analysen
  FactoryTwin/                Frontend (NEXUS-Oberfläche)
  docker-compose.yml
  .github/workflows/ci.yml
  docs/
```

Abhängigkeitsregel: `FlowPilot` und `SmartFactory` hängen nur von `contracts` ab, nie voneinander. `sim` hängt nur von `contracts` ab. `app` verdrahtet alles.

## 5. Zuständigkeiten (eine Datenquelle pro Objekt)

| Objekt | Autorität | Andere lesen über |
|---|---|---|
| Produkt, Arbeitsplan, Stückliste | SmartFactory | Port `ProductCatalog` |
| Produktionsauftrag, Arbeitsgang, Plan | SmartFactory | Events `order.*`, REST |
| Maschinen-Stammdaten und Betriebszustand | SmartFactory | Events `machine.*` |
| Material, Lagerplatz, Bestand, Reservierung | FlowPilot | Port `MaterialService` |
| Transportauftrag, AGV, Wegenetz, Sperrungen | FlowPilot | Events `transport.*`, `agv.*` |
| Physik der Anlagen (Fortschritt, Temperatur, Verschleiss, Störung) | `sim` | ausschliesslich OPC UA |
| Ereignis- und Auditprotokoll | `app` | REST, WebSocket |

Maschinen-Betriebszustand: `sim` erzeugt ihn, der OPC-UA-Client in FlowPilot liest ihn und veröffentlicht `machine.status`. SmartFactory führt daraus den fachlichen Maschinenzustand. Es gibt keinen zweiten Weg am OPC UA vorbei.

## 6. Ports zwischen den Modulen (`contracts`)

```java
interface MaterialService {            // implementiert von FlowPilot
  ReservationResult reserve(ReservationRequest r);   // idempotent über r.requestId
  void release(String reservationId);
  Availability availability(String materialId);
}
interface TransportService {           // implementiert von FlowPilot
  String requestTransport(TransportRequest r);       // idempotent über r.requestId
  void cancel(String transportId);
}
interface ProductCatalog {             // implementiert von SmartFactory
  List<MaterialDemand> demandFor(String productId, int quantity);
}
interface MachineControl {             // implementiert von sim, aufgerufen über OPC-UA-Methoden
  void start(String machineId); void stop(String machineId);
  void injectFault(String machineId, String faultTypeId);
  void repair(String machineId); void maintain(String machineId);
}
```

`ReservationResult` ist `Reserved(reservationId)` oder `Shortage(materialId, requested, available)`. Teilreservierungen gibt es nicht.

## 7. Ereignisse

Jedes Ereignis hat `eventId` (UUID, deterministisch aus Seed und Zähler), `simTime`, `type`, `source`, `severity`, `payload` (JSON) und optional `correlationId` (die Auftrags-ID, damit ein Auftrag durchgängig verfolgbar ist).

Typen: `order.created`, `order.material_pending`, `order.shortage`, `order.released`, `order.started`, `order.rescheduled`, `order.completed`, `order.cancelled`, `operation.started`, `operation.completed`, `material.reserved`, `material.released`, `material.shortage`, `stock.changed`, `transport.created`, `transport.assigned`, `transport.state`, `transport.failed`, `transport.reassigned`, `transport.delivered`, `agv.state`, `agv.fault`, `route.blocked`, `route.replanned`, `machine.status`, `machine.fault`, `machine.material_request`, `opcua.connection`, `scenario.started`, `scenario.finished`, `decision` (begründete automatische Entscheidung, etwa AGV-Auswahl oder Ausweichmaschine).

Verarbeitung: Ereignisse werden in derselben Transaktion wie die Zustandsänderung in die Tabelle `event` geschrieben und nach dem Commit im Prozess verteilt. Verbraucher merken sich verarbeitete `eventId`s in `processed_event` (Unique auf `consumer, event_id`), wiederholte Zustellung bleibt wirkungslos.

## 8. Zustandsautomaten

Ungültige Übergänge werden abgelehnt und als `IllegalTransition` gemeldet (REST: 409).

**Produktionsauftrag**

```
CREATED → MATERIAL_PENDING → RELEASED → IN_PROGRESS → COMPLETED
              ↓      ↑
           SHORTAGE ─┘ (nach Wareneingang erneut geprüft)
jeder nicht-terminale Zustand → CANCELLED
```

**Transportauftrag** (Arten: `SUPPLY` Lager→Maschine, `WIP` Maschine→Maschine, `PUTAWAY` Maschine→Fertiglager)

```
CREATED → ASSIGNED → TO_PICKUP → LOADING → IN_TRANSIT → UNLOADING → DELIVERED
ASSIGNED | TO_PICKUP                → CREATED   (AGV fällt unbeladen aus, Neuzuweisung)
LOADING | IN_TRANSIT | UNLOADING    → FAILED → ASSIGNED (Ersatz-AGV holt die Ladung am Ausfallort)
CREATED | ASSIGNED | TO_PICKUP      → CANCELLED
```

Ein AGV hält höchstens einen nicht-terminalen Transportauftrag (Unique-Index auf `agv_id` für aktive Zustände).

**Maschine**: `IDLE`, `SETUP`, `RUNNING`, `BLOCKED`, `STOPPED`, `FAULT`, `REPAIR`, `MAINTENANCE`, mit den Übergängen der heutigen Engine.

**AGV**: `IDLE`, `TO_PICKUP`, `LOADING`, `TO_DROP`, `UNLOADING`, `TO_CHARGE`, `CHARGING`, `FAULT`, `OFFLINE`.

## 9. FlowPilot

- **Material und Lager.** Materialtypen, Lagerplätze mit Kapazität, Bestand je (Material, Lagerplatz). Verfügbar = Bestand − reserviert. Meldebestand löst Nachbestellung mit Lieferzeit aus.
- **Reservierung.** Eine Transaktion, `SELECT … FOR UPDATE` auf die Bestandszeilen, Check-Constraint `reserved <= on_hand`, Unique auf `request_id`. Reicht der Bestand nicht, wird nichts reserviert und `material.shortage` gemeldet.
- **Wegenetz.** Gerichteter Graph aus der bestehenden Hallengeometrie. Routing mit A* (Heuristik: euklidische Distanz). Kanten lassen sich sperren; betroffene Fahrzeuge planen neu, ohne gültige Route wartet der Auftrag und meldet `route.blocked`.
- **Disposition.** Offene Aufträge nach Priorität, dann Alter. Je Auftrag das verfügbare AGV mit ausreichender Kapazität und Akkureichweite und der kürzesten Anfahrt. Die Wahl wird als `decision` protokolliert.
- **Flotte.** Position, Geschwindigkeit, Ladung, Akku, Status. Folgeabstand und Vorfahrt wie bisher. Laden an `CH-1` bis `CH-6`. Wartung und Ausfälle zufällig (Seed) oder ausgelöst.
- **OPC UA.** Milo-Client mit Subscription auf Status, Fortschritt, Sensoren und Materialanforderung je Maschine. Bei Verbindungsverlust: Reconnect mit Backoff, betroffene Maschinen gelten als `UNKNOWN` und erhalten keine neuen Transporte. Werte ausserhalb des gültigen Bereichs oder mit schlechtem Statuscode werden verworfen und protokolliert.

## 10. SmartFactory

- **Stammdaten.** Produkte mit Arbeitsplan (Schritte mit Prozess, Grundzeit, Vorgänger) und Stückliste je Schritt. Maschinen mit Fähigkeiten, Kapazität und Wartungsfenstern.
- **Aufträge.** Anlegen, ändern, priorisieren, stornieren. Menge, Termin, Status.
- **Planung.** Vorwärtsterminierung mit endlicher Kapazität: Arbeitsgänge werden in Reihenfolge der Dispositionsregel (`PRIORITY`, `EDD`, `FIFO`, `SPT`) auf die früheste freie fähige Maschine gelegt, unter Beachtung von Vorgängern, Rüstzeit und Wartungsfenstern. Ergebnis ist ein Plan mit Start/Ende je Arbeitsgang. Erkannt werden Terminüberschreitungen und die Engpassmaschine (höchste geplante Auslastung).
- **Freigabe.** Ein Arbeitsgang startet erst, wenn das Material laut `transport.delivered` an der Maschine liegt.
- **Fehlerbehandlung.** Bei `machine.fault`: betroffene Arbeitsgänge ermitteln, Ausweichmaschine mit gleicher Fähigkeit suchen, neu planen, offene Transporte über `TransportService` umleiten, alles als `decision` und `order.rescheduled` protokollieren. `QC-01` hat bewusst keine Ausweichmaschine; betroffene Aufträge warten.
- **Analysen.** Durchsatz, Auslastung, Stillstandszeiten je Zustand, Durchlaufzeiten, OEE, Engpass. Berechnet aus `event` und `machine_state_interval`, nicht aus Zählern im Frontend.

## 11. Simulation (`sim`)

- Eine Uhr, ein Thread, fester Schritt von 100 ms Simulationszeit. Tempo 0 bis 25-fach bestimmt, wie viele Schritte pro Echtzeit-Tick laufen.
- Ein Seed-RNG (konfigurierbar über `nexus.sim.seed` und beim Reset). Kein Aufruf von `Math.random` oder der Systemzeit in Fachlogik.
- Übernommenes Modell: Rüstzeit, Override, Temperatur erster Ordnung mit Thermoschutz, Verschleiss, MTBF-Störungen, Reparatur und vorbeugende Wartung, Stauförderer, QC-Ausschuss abhängig von der Teilehistorie.
- Bewegungsdaten liegen im Speicher. Persistiert werden Geschäftsübergänge und Ereignisse. Ein Reset leert die Bewegungstabellen und lädt die Stammdaten neu.
- Reihenfolge je Schritt: `sim` aktualisiert Anlagen → OPC-UA-Werte → FlowPilot (Flotte, Disposition) → SmartFactory (Freigabe, Planung) → Ereignisse verteilen. Diese feste Reihenfolge ist Teil der Determinismus-Garantie.

## 12. Schnittstellen nach aussen

REST unter `/api/v1`, beschrieben über OpenAPI (springdoc), Swagger UI unter `/swagger-ui`. TypeScript-Typen werden mit `openapi-typescript` generiert. Fehler als RFC-7807-Problem-JSON; Validierung über Bean Validation.

| Bereich | Endpunkte |
|---|---|
| Aufträge | `GET/POST /orders`, `GET/PATCH /orders/{id}`, `POST /orders/{id}/cancel`, `GET /schedule` |
| Stammdaten | `GET /products`, `GET /machines`, `GET /layout` |
| Maschinen | `POST /machines/{id}/start\|stop\|fault\|repair\|maintain`, `PATCH /machines/{id}/config` |
| Lager | `GET /materials`, `GET /stock`, `GET /reservations`, `POST /stock/receipts` |
| Transport | `GET /transports`, `GET /agvs`, `POST /agvs/{id}/enable\|disable\|charge\|fault`, `POST /routes/edges/{id}/block\|unblock` |
| Simulation | `GET /sim`, `POST /sim/play\|pause\|reset`, `PUT /sim/speed`, `PUT /sim/rules` |
| Szenarien | `GET /scenarios`, `POST /scenarios/{id}/run` |
| Ereignisse | `GET /events?type&source&correlationId&after`, `GET /analytics/*` |

WebSocket `/ws`:

- `frame` (etwa 10 pro Sekunde): Simulationszeit, Maschinen (Status, Fortschritt, Sensoren), Förderbänder mit Teilepositionen, AGVs (Position, Richtung, Route, Ladung, Akku), Bestände, laufende Aufträge und Transporte.
- `event`: jedes neue Ereignis einzeln.

Das Frontend interpoliert zwischen zwei Frames.

## 13. Datenbank

PostgreSQL, Schema über Flyway. Tabellen:

`product`, `routing_step`, `bom_item`, `machine`, `maintenance_window`, `production_order`, `operation`, `machine_state_interval`, `material`, `storage_location`, `stock`, `reservation`, `transport_order`, `agv`, `graph_node`, `graph_edge`, `event`, `processed_event`, `sim_run`.

Wichtige Constraints: `stock.reserved <= stock.on_hand`, `stock.on_hand <= storage_location.capacity`, Unique `reservation.request_id`, Unique `transport_order.request_id`, partieller Unique-Index auf `transport_order(agv_id)` für aktive Zustände, Optimistic Locking (`version`) auf `production_order` und `transport_order`.

## 14. Frontend (FactoryTwin wird NEXUS)

- **Datenfluss.** Ein Twin-Laufzeitobjekt hält den letzten und vorletzten Frame. Quelle ist entweder der WebSocket (Live) oder eine Aufzeichnungsdatei (Replay), gewählt über `VITE_NEXUS_MODE`. Die Szene liest daraus in `useFrame`, die Panels über den Zustand-Store. Befehle gehen über einen typisierten REST-Client.
- **Replay.** `app` kann einen Lauf als komprimierte Frame- und Ereignisfolge exportieren. Die Datei liegt in `public/` und wird auf GitHub Pages abgespielt. Steuerbefehle sind dort deaktiviert und als „Aufzeichnung" gekennzeichnet.
- **Navigation.** Zehn Bereiche über Hash-Routing: Overview, FactoryTwin, SmartFactory, FlowPilot, Machines, Warehouse, Analytics, Event Monitor, Scenario Lab, Settings.
- **Designsystem.** Aufbau auf `src/ui/primitives.tsx`. Inter für Text, JetBrains Mono für Zahlen und IDs. Dunkles Thema mit neutralen Flächen, Farbe nur für Zustände (läuft, wartet, gestört, Wartung), keine Verläufe, kein Glas-Effekt. Tabellen, Statusanzeigen, Maschinenkarten und SVG-Charts als gemeinsame Komponenten. Layout für Desktop und grosse Leitstandbildschirme, einspaltig unter 1024 px.
- **3D.** Maschinenanimationen folgen dem Status aus dem Frame. AGVs fahren die gelieferte Route. Lagerregale zeigen den Bestand. Klick öffnet Details mit Auftrag, Fortschritt und Befehlen.

## 15. Scenario Lab

Jedes Szenario ist ein Backend-Endpunkt, der Befehle auf die laufende Simulation anwendet und `scenario.started` / `scenario.finished` meldet.

| | Szenario | Auslöser | Erwartetes Verhalten |
|---|---|---|---|
| A | Maschinenausfall | Störung an einer laufenden CNC | Umplanung auf Ausweichmaschine, Transporte umgeleitet |
| B | AGV-Ausfall | Störung an einem beladenen AGV | Transport `FAILED`, Ersatz-AGV übernimmt die Ladung |
| C | Materialengpass | Auftrag über Bestand | Keine Reservierung, `SHORTAGE`, Auftrag wartet auf Wareneingang |
| D | Expressauftrag | Auftrag mit Priorität `eilig` | Neuplanung, Anzeige der verschobenen Aufträge und Termine |
| E | Kompletter Durchlauf | Neuer Auftrag | Reservierung bis Einlagerung, als Schrittfolge sichtbar |

Dazu Start, Pause, Tempo, Reset und der Ereignisverlauf gefiltert auf die `correlationId` des Szenarios.

## 16. Fehlerbehandlung

- Eingaben: Bean Validation, 400 mit Feldliste. Ungültiger Zustandsübergang: 409. Unbekannte ID: 404.
- Konkurrenz: Optimistic Locking, bei Konflikt 409 und kein stilles Überschreiben.
- OPC UA: siehe Abschnitt 9.
- WebSocket: Frontend verbindet neu mit Backoff und zeigt den Verbindungsstatus dauerhaft an.

## 17. Tests

| Ebene | Inhalt |
|---|---|
| Unit (JUnit) | A* inklusive gesperrter Kanten, Reservierung, Disposition, Terminierung, alle Zustandsautomaten |
| Integration (Spring, Embedded PostgreSQL) | Ende-zu-Ende-Ablauf, 50 parallele Reservierungen ohne Überbuchung, doppelte Events ohne Doppelwirkung, AGV-Ausfall mit Neuzuweisung, Maschinenausfall mit Umplanung |
| Determinismus | Zwei Läufe mit gleichem Seed liefern identische Ereignisfolgen |
| Invarianten | Über lange Läufe mit Zufallseingriffen: kein Teil verloren oder doppelt, Reservierungen und Bestände konsistent (übernommen aus `invariants.ts`) |
| Frontend (Vitest) | Frame-Interpolation, Replay-Parser, Formatierung |
| End-to-End (Playwright) | Szenarien A bis E gegen das laufende Backend |

CI (GitHub Actions): Backend-Build und Tests, Frontend-Typecheck, Tests und Build, Playwright gegen das gestartete Backend.

## 18. Lieferumfang in drei Meilensteinen

1. **M1 Backend.** `contracts`, FlowPilot, SmartFactory, `sim`, `app`, Ende-zu-Ende-Ablauf mit allen Backend-Tests. FlowPilot und SmartFactory werden nach den Verträgen aus Abschnitt 6 bis 8 parallel in getrennten Worktrees entwickelt.
2. **M2 Oberfläche.** Twin-Laufzeitobjekt, REST/WebSocket-Client, Redesign, zehn Bereiche.
3. **M3 Abschluss.** Scenario Lab, Analysen, Aufzeichnung und Replay, Playwright, CI, Docker Compose, README mit Architekturdiagramm.

Jeder Meilenstein bekommt einen eigenen Umsetzungsplan.

## 19. Nicht enthalten

Benutzerkonten und Rechte, Message-Broker, MQTT, mehrere Werke, Mehrsprachigkeit (Oberfläche bleibt deutsch), reale OPC-UA-Hardware.

## 20. Bekannte Einschränkungen

- Docker Compose kann auf dem Entwicklungsrechner nicht ausgeführt werden (kein Docker). Geprüft wird es erst in CI oder auf einem Rechner mit Docker.
- Gebaut wird mit JDK 25 und `--release 21`. Ein Lauf auf einem echten JDK 21 erfolgt erst in CI.
- Die Online-Demo zeigt einen festen aufgezeichneten Lauf.
