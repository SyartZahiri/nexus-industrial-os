# NEXUS Industrial OS – Design

Stand: 2026-11-10, Revision 2. Status: zur Freigabe.

Revision 2 ändert gegenüber der freigegebenen ersten Fassung: Die Online-Version ist voll bedienbar und läuft ohne Server im Browser (Abschnitt 2a), RouteMind kommt als viertes System hinzu (Abschnitt 15a), Java 25 statt 21. Die Befunde aus dem Review von M1-A sind als Anforderungen eingearbeitet (Abschnitte 6, 7, 16).

## 1. Ziel

Aus dem bestehenden Browser-Projekt FactoryTwin und drei neuen Systemen, FlowPilot (Materialfluss), SmartFactory (MES) und RouteMind (Lieferlogistik), entsteht eine integrierte Anwendung für ein Softwareentwicklungs-Portfolio und als Live-Demo für eine Bewerbung. Sie simuliert eine kleine Fertigung nachvollziehbar von der Auftragsanlage über Produktion und Einlagerung bis zur Auslieferung beim Kunden.

Erfolgskriterien:

- Ein Auftrag durchläuft Materialreservierung, AGV-Transport, Produktionsfreigabe, Fertigung, Einlagerung, Verladung, Tourenplanung und Zustellung, und alle vier Systeme zeigen denselben Zustand.
- Jede Kennzahl ist aus protokollierten Zustandsänderungen berechnet.
- Gleicher Seed und gleiche Eingriffe ergeben dasselbe Ereignisprotokoll, auf dem Server und im Browser.
- Die Online-Version ist ohne Server voll bedienbar und verhält sich so flüssig wie das ursprüngliche FactoryTwin. Der Full-Stack-Betrieb läuft mit derselben Fachlogik gegen PostgreSQL.

## 2. Getroffene Entscheidungen

| Entscheidung | Wahl | Folge |
|---|---|---|
| Ort der Fachlogik | Nur in Java, als portabler Kern | `FactoryTwin/src/sim` und die Adapter in `src/integration` entfallen. Die 14 Vitest-Tests der Engine werden durch Java-Tests ersetzt. Die Logik existiert genau einmal. |
| Online-Version | Derselbe Java-Kern, mit TeaVM nach JavaScript übersetzt, läuft im Browser | Voll bedienbar ohne Server. Die frühere Entscheidung „Replay" ist aufgehoben. |
| Java-Version | 25 (LTS) | Gebaut und getestet mit dem installierten JDK 25. |
| Datenbank lokal | Embedded PostgreSQL (`io.zonky.test:embedded-postgres`) | Echtes PostgreSQL ohne Installation. Docker Compose wird geschrieben, auf dem Entwicklungsrechner aber nicht ausgeführt. |
| Deployment-Form | Ein Spring-Boot-Prozess, eine Datenbank | Keine Microservices, kein Message-Broker. |

## 2a. Ein Kern, zwei Laufzeiten

Ein Versuch am 2026-11-10 hat gezeigt: `nexus-contracts` (Java 25, Records, Sealed-Interfaces mit Pattern-Switch, Streams, Zustandsautomaten) lässt sich mit TeaVM 0.16.0 zu einem ES-Modul von 50 KB übersetzen und läuft in Node korrekt. Einzige Lücke im Versuch: der Konstruktor `java.util.UUID(long, long)` fehlt in der TeaVM-Klassenbibliothek.

Daraus folgt die Schichtung:

| Schicht | Inhalt | Darf verwenden |
|---|---|---|
| **Kern** (`contracts`, `sim`, `FlowPilot`, `SmartFactory`, `RouteMind`) | Gesamte Fachlogik, Zustandsautomaten, Planung, Routing, Simulation | Nur Java-Standardbibliothek, soweit TeaVM sie unterstützt. Kein Spring, kein JDBC, keine Threads, keine Reflection, keine Systemzeit. |
| **Server-Laufzeit** (`platform/app`) | Spring Boot, PostgreSQL-Speicher, REST, WebSocket, OPC UA (Milo), OR-Tools | Alles. |
| **Browser-Laufzeit** (`platform/web`) | Speicher im Arbeitsspeicher, direkte Maschinenanbindung, JavaScript-Brücke; von TeaVM übersetzt | Nur den Kern und die TeaVM-Bibliothek. |

Der Kern spricht mit der Aussenwelt nur über Ports, die jede Laufzeit implementiert:

| Port | Server | Browser |
|---|---|---|
| Speicher je Fachobjekt (`OrderStore`, `StockStore`, `TransportStore` …) | JDBC auf PostgreSQL | `HashMap` |
| `UnitOfWork` (Transaktion) | Spring-Transaktion | sofort ausführen (ein Thread) |
| `EventLog` (Ereignisse speichern, verarbeitete merken) | Tabellen `event`, `processed_event` | Listen im Speicher |
| `MachineGateway` (Maschinenzustände lesen, Befehle senden) | Milo-OPC-UA-Client gegen den OPC-UA-Server der Simulation | direkter Aufruf der Simulation |
| `RouteOptimizer` (RouteMind) | Google OR-Tools | Java-Heuristik aus dem Kern |
| `RoadNetwork` (Fahrzeiten, Strassenverläufe) | vorberechnete Matrix, optional Routingdienst | vorberechnete Matrix |

Regeln:

- Was im Browser nicht läuft (PostgreSQL, OPC UA, OR-Tools), ist ausschliesslich ein Adapter. Die Geschäftsregeln dahinter (Reservierung alles-oder-nichts, ein Transport je AGV, Zustandsübergänge) stehen im Kern und gelten in beiden Laufzeiten. Die Datenbank-Constraints aus Abschnitt 13 bleiben als zweite Sicherung des Servers.
- Ein gemeinsamer Vertragstest je Port läuft gegen beide Implementierungen, damit sich Speicher im Arbeitsspeicher und PostgreSQL gleich verhalten.
- Der Kern wird in CI zusätzlich mit TeaVM übersetzt. Verwendet jemand eine nicht unterstützte Bibliotheksfunktion, schlägt der Build fehl.
- Zufall kommt aus einem eigenen Generator im Kern (SplitMix64), nicht aus `java.util.Random`, damit Server und Browser dieselbe Zahlenfolge erzeugen.
- IDs sind Zeichenketten (`<lauf>-<zähler>`), keine `UUID`.
- Jedes Kernmodul hängt in `platform/web` und liefert als erste Aufgabe einen `@JSExport`-Selbsttest, den der Build auf der JVM und in Node vergleicht. TeaVM übersetzt nur erreichbaren Code; ohne Selbsttest bliebe ein Modul ungeprüft.
- Determinismus im Kern: keine Iteration über `HashMap`/`HashSet` mit Enum- oder Record-Schlüsseln (der Hash ist auf der JVM identitätsbasiert), keine `Set.of`/`Set.copyOf`/`Map.of` dort, wo iteriert wird; in Ereignissen nur Grundrechenarten und `sqrt`, keine Ergebnisse von `sin`, `cos`, `atan2`, `pow`, die zwischen den Laufzeiten im letzten Bit abweichen dürfen.
- Die Oberfläche ist in beiden Betriebsarten dieselbe. Sie spricht mit einer Schnittstelle `NexusClient`, die entweder REST und WebSocket nutzt oder das TeaVM-Modul in einem Web Worker.

## 3. Ausgangslage FactoryTwin

React 19, TypeScript 7, Vite 8, React Three Fiber, Tailwind 4, Zustand. Kein Backend, kein Git-Repository, kein CI.

Erhalten bleiben: `src/scene` (3D-Modelle, Materialien, Kamera), `src/ui` (Primitives, Panels, SVG-Charts) und die Hallengeometrie aus `src/sim/layout.ts` (wandert als Stammdaten ins Backend).

Ersetzt werden: `src/sim/engine.ts` und zugehörige Dateien (Logik wandert nach Java), `src/integration` (ersetzt durch REST/WebSocket-Client), `src/store/factoryStore.ts` (liest künftig aus dem Twin-Laufzeitobjekt statt aus der Engine).

Bestehende IDs werden übernommen: Maschinen `CNC-01` bis `CNC-04`, `ROB-01`, `ROB-02`, `QC-01`; Förderbänder `CV-01` bis `CV-04`; Ladestationen `CH-1` bis `CH-6`; Produkte `GH200`, `AW50`, `FP12`, `MT7`; Prozesse `DREHEN`, `FRAESEN`, `MONTAGE`, `PRUEFEN`.

## 4. Repository

```
Industire/                    Git-Monorepo
  pom.xml                     Maven-Parent, Java-Release 25, Maven Wrapper
  platform/
    contracts/                Kern: IDs, Enums, Events, Ports, Ereignisverteilung
    sim/                      Kern: Simulationsuhr, Seed-RNG, Maschinen-/Band-Simulatoren, Szenarien
    app/                      Server-Laufzeit: Spring Boot, JDBC-Speicher, REST, WebSocket, Flyway, OPC UA, OR-Tools
    web/                      Browser-Laufzeit: Speicher im Arbeitsspeicher, JS-Brücke, TeaVM-Build
  FlowPilot/                  Kern: Material, Lager, Reservierung, Transport, Flotte, Routing
  SmartFactory/               Kern: Aufträge, Arbeitspläne, Planung, Maschinenzustand, Fehlerbehandlung, Analysen
  RouteMind/                  Kern: Lieferaufträge, Flotte, Touren, Heuristik, Liefersimulation
  FactoryTwin/                Frontend (NEXUS-Oberfläche)
  docker-compose.yml
  .github/workflows/ci.yml
  docs/
```

Abhängigkeitsregel: `FlowPilot`, `SmartFactory`, `RouteMind` und `sim` hängen nur von `contracts` ab, nie voneinander. `app` und `web` verdrahten alles. Die Kernmodule haben keine Abhängigkeit ausser der Java-Standardbibliothek; ein Maven-Enforcer-Check erzwingt das.

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

Präzisierungen nach dem Review von M1-A (die Ports werden entsprechend angepasst):

- **Reservierung über mehrere Materialien ist atomar.** `reserve` nimmt die gesamte Bedarfsliste eines Auftrags entgegen. Fehlt ein Material, wird nichts reserviert.
- **Idempotenz gilt nur für erfolgreiche Reservierungen.** Dieselbe `requestId` liefert dieselbe Reservierung. Ein `Shortage` wird nicht gespeichert; ein erneuter Aufruf prüft den Bestand neu. Das trägt die Schleife `SHORTAGE → MATERIAL_PENDING` nach einem Wareneingang.
- **Transporte nennen fachliche Orte, keine Graphknoten.** `TransportRequest` enthält Quelle und Ziel als Lagerplatz-, Maschinen- oder Verladezonen-ID. Nur FlowPilot kennt den Wegegraphen und löst die Knoten auf.
- **Produkt und Fertigware sind verknüpft.** `ProductCatalog` liefert zu jedem Produkt die Material-ID der Fertigware, statt dass sie aus dem Namen abgeleitet wird.
- **Alle Orte haben eine ID.** Förderbänder (`CV-01`..`CV-04`), Ladestationen (`CH-1`..`CH-6`), der Abholpunkt hinter der Prüfzelle und die Verladezone stehen als Stammdaten in der Datenbank.
- **Ereignisnutzlasten sind typisiert.** Für jedes modulübergreifende Ereignis gibt es in `contracts` einen Record; lose Maps mit frei gewählten Schlüsseln sind nicht zulässig.
- **Verfügbarkeit gilt je Lagerplatz**, weil eine Reservierung an einen Lagerplatz gebunden ist.

## 7. Ereignisse

Jedes Ereignis hat `eventId` (UUID, deterministisch aus Seed und Zähler), `simTime`, `type`, `source`, `severity`, `payload` (JSON) und optional `correlationId` (die Auftrags-ID, damit ein Auftrag durchgängig verfolgbar ist).

Typen: `order.created`, `order.material_pending`, `order.shortage`, `order.released`, `order.started`, `order.rescheduled`, `order.completed`, `order.cancelled`, `operation.started`, `operation.completed`, `material.reserved`, `material.released`, `material.shortage`, `stock.changed`, `transport.created`, `transport.assigned`, `transport.state`, `transport.failed`, `transport.reassigned`, `transport.delivered`, `agv.state`, `agv.fault`, `route.blocked`, `route.replanned`, `machine.status`, `machine.fault`, `machine.material_request`, `opcua.connection`, `scenario.started`, `scenario.finished`, `decision` (begründete automatische Entscheidung, etwa AGV-Auswahl oder Ausweichmaschine).

Verarbeitung: Ereignisse werden in derselben Transaktion wie die Zustandsänderung gespeichert und nach dem Commit im Prozess verteilt. Verbraucher merken sich verarbeitete `eventId`s (Unique auf `consumer, event_id`), wiederholte Zustellung bleibt wirkungslos. Die Verteilung selbst liegt im Kern und nutzt den Port `EventLog`.

Regeln der Verteilung (aus dem Review von M1-A):

- **Reihenfolge.** Zugestellt wird in der Reihenfolge der Veröffentlichung über eine Warteschlange. Veröffentlicht ein Verbraucher selbst ein Ereignis, wird es hinten angestellt und erst zugestellt, wenn das auslösende Ereignis alle Verbraucher erreicht hat. Kein Verbraucher sieht eine Wirkung vor ihrer Ursache.
- **Fehlgeschlagene Verbraucher.** Schlägt ein Verbraucher fehl, wird sein Schreibvorgang zurückgerollt und das Ereignis bleibt für ihn offen. Solange er einen offenen Fehlschlag hat, werden spätere Ereignisse für ihn zurückgehalten und danach in Veröffentlichungsreihenfolge zugestellt; auch er sieht also keine Wirkung vor ihrer Ursache. Am Ende jedes Simulationsschritts werden offene Zustellungen erneut versucht, höchstens fünfmal. Danach meldet das System `system.consumer_failed` mit Schweregrad `ERROR`, sichtbar im Event Monitor. Die Meldung enthält nur Texte von Fachausnahmen, sonst den Namen der Ausnahmeklasse; der volle Text bleibt in der Fehlertabelle, die keine Schnittstelle ausliefert.
- **Zustellung wirft nie in den Veröffentlicher.** Probleme der Verteilung selbst (Datenbank nicht erreichbar) werden protokolliert; eine bereits festgeschriebene Änderung führt nicht zu einem Fehler beim Aufrufer.
- **Threading auf dem Server.** Erneute Zustellung und Veröffentlichungen in einer eigenen Transaktion dürfen nicht aufgerufen werden, während der Aufrufer noch nicht festgeschriebene Änderungen hält. Sperrwartezeiten sind auf 5 s begrenzt, der Verbindungspool hat 20 Verbindungen. Der Simulationsschritt in M1-D ruft die Wiederholung ausserhalb seiner Transaktion auf.
- **Gleiche Nutzlast.** Verbraucher erhalten bei der ersten und bei jeder erneuten Zustellung dieselbe typisierte Nutzlast.
- **Eindeutige Namen.** Zwei Verbraucher mit demselben Namen sind ein Startfehler.
- **Ereignis-IDs** sind Zeichenketten `<lauf>-<zähler>`. Die Determinismusprüfung vergleicht Zähler, Zeit, Typ und Nutzlast, nicht die Laufnummer.

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
- **OPC UA** (nur Server-Laufzeit, als Implementierung des Ports `MachineGateway`; im Browser ruft derselbe Port die Simulation direkt auf). Milo-Client mit Subscription auf Status, Fortschritt, Sensoren und Materialanforderung je Maschine. Bei Verbindungsverlust: Reconnect mit Backoff, betroffene Maschinen gelten als `UNKNOWN` und erhalten keine neuen Transporte. Werte ausserhalb des gültigen Bereichs oder mit schlechtem Statuscode werden verworfen und protokolliert.

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

Wichtige Constraints: `stock.reserved <= stock.on_hand`, `stock.on_hand <= storage_location.capacity`, Unique `reservation(request_id, material_id)` (eine Zeile je Material, der Aufrufer fasst zusammen), Unique `transport_order.request_id`, Quelle und Ziel eines Transports als fachlicher Ort (`from_kind`/`from_id`, `to_kind`/`to_id`) neben dem aufgelösten Graphknoten, partieller Unique-Index auf `transport_order(agv_id)` für aktive Zustände, Optimistic Locking (`version`) auf `production_order` und `transport_order`.

## 14. Frontend (FactoryTwin wird NEXUS)

- **Datenfluss.** Ein Twin-Laufzeitobjekt hält den letzten und vorletzten Frame. Die Szene liest daraus in `useFrame`, die Panels über den Zustand-Store. Alle Daten und Befehle laufen über die Schnittstelle `NexusClient` mit zwei Implementierungen, gewählt über `VITE_NEXUS_MODE`:
  - `server`: REST für Befehle und Abfragen, WebSocket für Frames und Ereignisse.
  - `browser`: Das TeaVM-Modul läuft in einem Web Worker, simuliert dort mit festem Schritt und sendet Frames und Ereignisse per `postMessage`. Befehle gehen als Nachrichten an den Worker und werden dort vom selben Kern validiert wie auf dem Server.
- **GitHub Pages** liefert den Build im Modus `browser`. Alle Bedienelemente funktionieren. Der Zustand lebt im Arbeitsspeicher des Tabs und beginnt nach dem Neuladen mit dem Seed von vorn.
- **Flüssigkeit.** Die Simulation blockiert die Darstellung nicht (Worker). Zielwert: 60 Bilder pro Sekunde in der 3D-Ansicht bei 25-fachem Tempo auf einem üblichen Laptop, gemessen wie beim ursprünglichen FactoryTwin.
- **Navigation.** Elf Bereiche über Hash-Routing: Overview, FactoryTwin, SmartFactory, FlowPilot, RouteMind, Machines, Warehouse, Analytics, Event Monitor, Scenario Lab, Settings.
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

## 15a. RouteMind – Lieferlogistik ausserhalb der Fabrik

RouteMind ist ein weiteres Kernmodul und folgt denselben Regeln wie FlowPilot und SmartFactory. Es wird erst gebaut, wenn der Ablauf bis zur Einlagerung in beiden Laufzeiten funktioniert.

**Zuständigkeit.** RouteMind ist die Autorität für Lieferaufträge, Lieferfahrzeuge, Depots, Kunden, Touren und Stopps. Fertigwarenbestand bleibt bei FlowPilot, Fertigstellungstermine bleiben bei SmartFactory.

**Zustandsautomaten.**

```
Lieferauftrag:  CREATED → AWAITING_GOODS → READY_TO_LOAD → LOADED → IN_TRANSIT → DELIVERED
                IN_TRANSIT → FAILED → READY_TO_LOAD (erneuter Zustellversuch, höchstens zwei)
                vor LOADED → CANCELLED
Tour:           PLANNED → LOADING → EN_ROUTE → COMPLETED;  PLANNED → REPLANNED;  EN_ROUTE → INTERRUPTED
Lieferfahrzeug: AVAILABLE, LOADING, EN_ROUTE, RETURNING, MAINTENANCE, BROKEN_DOWN
```

**Harte Regeln, im Kern erzwungen und getestet.**

- Eine Tour startet erst, wenn für jeden ihrer Lieferaufträge die Verladung bestätigt ist (`LOADED`).
- Die Ladung eines Fahrzeugs überschreitet nie seine Kapazität in Gewicht und Stellplätzen.
- Eine Neuplanung verändert weder zugestellte Lieferungen noch bereits angefahrene Stopps.

**Optimierung.** Vehicle Routing mit Kapazitäten und Zeitfenstern hinter dem Port `RouteOptimizer`:

- Server: Google OR-Tools (Routing-Bibliothek), mit Zeitlimit.
- Browser und Vergleichsbasis: eine Heuristik in reinem Java im Kern (Savings-Verfahren, danach 2-opt und Verschieben einzelner Stopps).
- Die Oberfläche zeigt je Planung Strecke, Fahrzeit, Kosten und Verspätung der einfachen Ausgangsplanung (nächster Nachbar) gegenüber der optimierten Lösung, und welcher Löser gerechnet hat.
- Nicht erfüllbare Bedingungen werden je Lieferauftrag mit Grund ausgewiesen (Zeitfenster nicht erreichbar, Kapazität reicht nicht, kein Fahrzeug in der Schicht).

**Strassennetz.** Ein fester Satz von Orten (ein Depot am Werk, rund 30 Kunden in der Region) mit vorberechneter Fahrzeit- und Distanzmatrix und Strassenverläufen, einmalig über einen OSRM-Routingdienst aus OpenStreetMap-Daten erzeugt und als Datei mit Quellenangabe abgelegt. Luftlinien werden nicht als Fahrzeiten ausgegeben. Neue Kundenorte sind im Server-Betrieb über den Routingdienst möglich, im Browser nur aus dem festen Satz.

**Karte.** MapLibre GL JS mit OpenStreetMap-Kacheln: Depot, Kunden, farbig unterscheidbare Touren entlang der Strassenverläufe, bewegte Fahrzeuge, Ankunftszeiten, Lieferstatus, Filter und Detailansicht je Tour.

**Simulation.** Lieferfahrzeuge bewegen sich in derselben Simulationsuhr wie die Fabrik. Verkehrsverzögerungen und Fahrzeugausfälle kommen aus dem Seed oder werden ausgelöst. Neue Lieferaufträge und Ausfälle lösen eine Neuplanung der noch nicht gestarteten Touren und der offenen Stopps aus.

**Integration.**

1. SmartFactory meldet voraussichtliche und tatsächliche Fertigstellung. RouteMind legt zu jedem Kundenauftrag einen Lieferauftrag mit derselben Auftrags-ID als `correlationId` an.
2. Sind die Fertigwaren laut FlowPilot im Lager, fordert RouteMind über `TransportService` den Transport zur Verladezone an.
3. FlowPilot bestätigt die Bereitstellung, RouteMind bestätigt die Verladung, erst dann startet die Tour.
4. FactoryTwin zeigt Verladezone, wartende Lieferfahrzeuge und die Übergabe. Ab dem Werkstor übernimmt die Karte.
5. Verschiebt sich die Produktion, plant RouteMind die betroffenen, noch nicht gestarteten Touren neu.

**Szenarien** (ersetzen und erweitern die Liste in Abschnitt 15): Produktionsverzögerung mit Auswirkung auf Touren, Ausfall eines Lieferfahrzeugs, Expressauftrag bis zur Lieferplanung, Materialengpass mit Auswirkung auf den Liefertermin, vollständige Lieferkette. Zusätzlich ein Vergleich „ursprünglicher Plan gegen neuer Plan" für Produktion und Lieferung.

**Dashboard.** Aktive Touren, Auslastung, geplante gegen tatsächliche Lieferzeiten, Strecken, Kosten, Verspätungen, Optimierungsergebnisse und Verlauf, berechnet aus Ereignissen.

**Nicht enthalten.** Der RouteMind-Prompt nennt „gemeinsame Authentifizierung und Berechtigungen". NEXUS hat keine Benutzerkonten (Abschnitt 19); das gilt auch für RouteMind.

## 16. Fehlerbehandlung

- Eingaben: Bean Validation, 400 mit Feldliste. Ungültiger Zustandsübergang: 409. Unbekannte ID: 404. Verletzte Datenbankregel (Kapazität, doppelte Anforderung): 409. Jeder andere Fehler: 500 im selben Problem-JSON-Format, ohne interne Meldungstexte; nur eigene Fachausnahmen geben ihren Text an den Client.
- Die API hat keine Anmeldung. Sie ist für lokalen Betrieb und Demo gedacht und darf so nicht offen ins Internet gestellt werden; die README sagt das ausdrücklich.
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
| Port-Vertragstests | Dieselbe Testklasse je Speicher-Port läuft gegen PostgreSQL und gegen den Speicher im Arbeitsspeicher |
| Browser-Laufzeit | Das TeaVM-Modul wird in CI gebaut und in Node ausgeführt: Ende-zu-Ende-Ablauf und Vergleich der Ereignisfolge mit dem Server bei gleichem Seed |
| RouteMind | Keine Tour startet ohne bestätigte Verladung, Kapazität nie überschritten, Neuplanung lässt Zugestelltes unverändert, Heuristik gegen OR-Tools auf festen Instanzen |
| Frontend (Vitest) | Frame-Interpolation, `NexusClient` in beiden Betriebsarten, Formatierung |
| End-to-End (Playwright) | Szenarien A bis E gegen das laufende Backend |

CI (GitHub Actions): Backend-Build und Tests, Frontend-Typecheck, Tests und Build, Playwright gegen das gestartete Backend.

## 18. Lieferumfang in Meilensteinen

1. **M1 Kern und beide Laufzeiten.**
   - M1-A Fundament und Verträge: erledigt (Branch `m1a-fundament`).
   - M1-A2 Umbau auf den portablen Kern: Speicher-Ports, `UnitOfWork`, Ereignisverteilung in den Kern, eigener Zufallsgenerator, Zeichenketten-IDs, präzisierte Ports aus Abschnitt 6, Modul `platform/web` mit TeaVM-Build und Vertragstests gegen beide Speicher.
   - M1-B FlowPilot und M1-C SmartFactory, parallel in getrennten Worktrees.
   - M1-D Simulation, `MachineGateway` (OPC UA und direkt), WebSocket, Ende-zu-Ende-Ablauf in beiden Laufzeiten.
2. **M2 Oberfläche.** `NexusClient` mit beiden Implementierungen, Web Worker, Twin-Laufzeitobjekt, Redesign, alle Bereiche ausser RouteMind.
3. **M3 Scenario Lab und Betrieb.** Szenarien, Analysen, Playwright in beiden Betriebsarten, CI, Docker Compose, GitHub Pages, README mit Architekturdiagramm.
4. **M4 RouteMind.** Kernmodul, Heuristik, OR-Tools-Adapter, Strassennetz-Daten, Karte, Verladung in FactoryTwin, erweiterte Szenarien, Logistik-Dashboard, Tests vom Produktionsauftrag bis zur Zustellung.

Jeder Meilenstein bekommt eigene Umsetzungspläne. Nach M3 ist NEXUS ohne RouteMind vollständig und vorzeigbar.

## 19. Nicht enthalten

Benutzerkonten und Rechte, Message-Broker, MQTT, mehrere Werke, Mehrsprachigkeit (Oberfläche bleibt deutsch), reale OPC-UA-Hardware, Speicherung des Browser-Zustands über das Neuladen hinaus.

## 20. Bekannte Einschränkungen

- Docker Compose kann auf dem Entwicklungsrechner nicht ausgeführt werden (kein Docker). Geprüft wird es erst in CI oder auf einem Rechner mit Docker.
- Im Browser laufen weder PostgreSQL noch OPC UA noch OR-Tools. Die Online-Version zeigt dieselbe Fachlogik, aber diese drei Technologien nur im Server-Betrieb. Die Oberfläche weist die aktive Betriebsart und den verwendeten Löser aus.
- Der TeaVM-Versuch deckt bisher nur `contracts` ab. Ob jede im Kern gewünschte Bibliotheksfunktion unterstützt wird, zeigt sich modulweise; der CI-Build aus Abschnitt 2a fängt Verstösse ab. Trägt TeaVM an einer Stelle nicht, wird der betroffene Code auf unterstützte Mittel umgeschrieben, nicht in TypeScript dupliziert.
- Server und Browser sind deterministisch je für sich. Dass beide bei gleichem Seed dieselbe Ereignisfolge liefern, ist ein Ziel mit eigenem Test, hängt aber an identischer Gleitkomma-Arithmetik und wird erst in M1-D nachgewiesen.
- Die Strassendaten von RouteMind sind ein fester, einmal erzeugter Satz. Die Kartenkacheln kommen zur Laufzeit von OpenStreetMap und brauchen eine Internetverbindung.
