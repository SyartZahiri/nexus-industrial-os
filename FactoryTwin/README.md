# FactoryTwin

FactoryTwin ist die Oberfläche von NEXUS Industrial OS: ein 3D-Digital-Twin einer kleinen Fertigung mit Zerspanung, Montage, Qualitätsprüfung und Intralogistik. Die Anlage selbst rechnet der Java-Kern (FlowPilot, SmartFactory, Simulation); diese Oberfläche zeigt und steuert ihn, im Browser oder gegen den Server.

Gebaut mit React 19, TypeScript, Vite, Three.js, React Three Fiber (mit drei), Tailwind CSS 4 und Zustand. Getestet wird mit Vitest.

```bash
npm install
npm run core       # Java-Kern für den Browser bauen (TeaVM, einmal und nach Änderungen am Kern)
npm run dev        # http://localhost:5173, Betriebsart Browser
npm test           # Tests von Datenweg, Twin und Store
npm run e2e        # Playwright: Szenarien A bis E und Bildrate im Browser-Modus
npm run build      # statischer Build in dist/
```

## Betriebsarten

| | Start | Was läuft |
|---|---|---|
| Browser (Standard) | `npm run dev` | Der Java-Kern, mit TeaVM nach JavaScript übersetzt, in einem Web Worker. Kein Server nötig; nach dem Neuladen beginnt die Anlage von vorn. |
| Server | `node scripts/run-server.mjs`, dann `npm run dev -- --mode server --port 5179` | Spring Boot mit PostgreSQL (eingebettet) und OPC UA. Vite leitet `/api` und `/ws` an `localhost:8080` weiter. |

Mit `?seed=123` in der URL startet die Browser-Anlage mit einem anderen Seed. Der Fuss der Navigation zeigt die aktive Betriebsart und den Verbindungsstand.

## Was simuliert wird

| Bereich | Modell |
|---|---|
| Aufträge | 4 Produkte mit mehrstufigen Arbeitsplänen (Drehen, Fräsen, Montage, Prüfen). Neue Rohteile kommen erst in die Fertigung, wenn der Umlaufbestand unter dem WIP-Limit liegt. Aufträge haben Termine und Prioritäten, die App erkennt Verzug und Blockaden. |
| CNC-Zentren (4) | Ein- und Ausgangspuffer, Rüstzeit bei Produktwechsel, Override und Kühlmittel. Die Temperatur folgt einem Modell erster Ordnung. Die Leistung wächst mit Override¹·⁶, der Verschleiß mit dem Quadrat der Last. Wird es zu heiß, schaltet der Thermoschutz die Maschine ab. |
| Roboterzellen (2) | 6-Achs-Roboter mit analytischer inverser Kinematik. Sie greifen vom Zuführband, montieren und legen auf das Sammelband ab. Ist dort kein Platz, wartet die Zelle. |
| Qualitätsprüfung | Wie wahrscheinlich Ausschuss ist, hängt davon ab, was das Teil vorher erlebt hat (Verschleiß, Übertemperatur, Override). Die Ursache steht im Protokoll, und das Teil wird nachproduziert. |
| Förderbänder (4) | Stauförderer mit Mindestabstand und einstellbarer Geschwindigkeit. Ein voller Bandabschnitt staut zurück bis in die vorgelagerten Prozesse. |
| AGV-Flotte (6) | Einbahn-Wegenetz aus Ring, Querspuren und Docks, daher kein Gegenverkehr. Routing per Dijkstra, Folgeabstand, Vorfahrt an Einmündungen, Akku und 6 Ladestationen. |
| Lager | Rohlager mit Meldebestand und Nachbestellung, Fertigwarenlager mit Versand. Die Regale in 3D zeigen den echten Bestand. |
| Störungen | Zufällig, abhängig von MTBF, Verschleiß, Temperatur und Override, oder vom Bediener ausgelöst. Die Instandhaltung hat eine Reaktions- und eine Reparaturzeit. Ab einer Verschleißschwelle wird vorbeugend gewartet. |

Bei aktiver dynamischer Umplanung gehen Teile an andere Maschinen mit derselben Fähigkeit. Teile, die an einer gestörten Maschine warten, werden umgelagert, und beladene AGVs fahren ein anderes Ziel an. Mit statischem Arbeitsplan warten die Teile, und der Auftrag wird als blockiert markiert. Die Prüfzelle QC-01 hat absichtlich keine Ausweichmöglichkeit.

## Bereiche

Die Navigation links (unter 1024 px ein Menü) führt zu elf Bereichen. Die Simulationsleiste oben mit Uhr, Start/Pause, Tempo, Reset und den Zählern für Fehler und Warnungen steht über jedem Bereich. Jeder Bereich hat eine eigene Adresse (`#/overview`, `#/events/PO-0004` …), lässt sich also verlinken und mit Zurück verlassen.

| Gruppe | Bereich | Stand |
|---|---|---|
| Betrieb | Overview | fertig: Durchsatz, OEE, Termintreue, Durchlaufzeit, Maschinenlage, offene und verspätete Aufträge, Meldungen |
| Betrieb | FactoryTwin | fertig: 3D-Halle mit Anlagendetails und Ereignissen |
| Betrieb | Machines | fertig: alle Maschinen mit Zustand, Sensoren und Verschleiss; je Maschine Auftrag und Befehle |
| Betrieb | Warehouse | fertig: Bestand, Reservierung und Meldebestand je Lagerplatz, Wareneingang mit freiem Platz |
| Systeme | SmartFactory | fertig: Aufträge mit Schritten, Priorität und Storno, Plan je Maschine (Gantt), neue Aufträge |
| Systeme | FlowPilot | fertig: AGV-Flotte, offene Transporte, Wegenetz als Karte mit Sperren und Freigeben |
| Systeme | RouteMind | kommt mit M4 |
| Auswertung | Analytics | fertig: Durchsatz, Termintreue, Durchlaufzeit, OEE je Maschine, Zeit je Zustand, Verlauf über 1 h, 8 h oder 24 h |
| Auswertung | Event Monitor | fertig: Filter nach Auftrag, Typ und Schweregrad, Live-Ansicht, ganze Geschichte seitenweise |
| Auswertung | Scenario Lab | fertig: fünf Szenarien starten und verfolgen, mit Zustand, Schrittfolge und Ereigniskette |
| System | Settings | fertig: Regeln der Anlage, Tempo, Seed, Darstellung |

Ein Bereich, der noch kommt, sagt, wofür er da ist und wo man das Gleiche heute schon sieht. Machines, SmartFactory und FlowPilot öffnen die Auswahl aus der Adresse (`#/machines/CNC-02`, `#/smartfactory/PO-0004`, `#/flowpilot/AGV-03`); eine unbekannte ID nennt das und zeigt die Liste.

## Scenario Lab

Ein Szenario greift einmal in die laufende Anlage ein und prüft dann jede simulierte Sekunde, ob das erwartete Verhalten eintritt. Es besteht, sobald das eintritt, und gilt als nicht bestanden, wenn seine Zeitgrenze abläuft; den Grund nennt es dann. Der Ablauf steckt im Java-Kern, Browser und Server verhalten sich also gleich. Es läuft immer nur ein Szenario; ein zweiter Start wird mit Grund abgelehnt.

| | Szenario | Eingriff | Bestanden, wenn | Zeitgrenze |
|---|---|---|---|---|
| A | Maschinenausfall | Störung an einer laufenden CNC, danach ein Auftrag, der diese CNC bevorzugt | Arbeit wird auf einer Ausweichmaschine eingeplant (das laufende Los bleibt in der Maschine) | 30 min |
| B | AGV-Ausfall | Störung an einem beladenen AGV | ein Ersatz-AGV übernimmt den ausgefallenen Transport | 30 min |
| C | Materialengpass | Bestand des ersten Materials auf die Hälfte des Bedarfs abgebucht, dann ein Auftrag über 10 Stück | der Auftrag wartet ohne Reservierung in SHORTAGE auf den Wareneingang | 30 min |
| D | Expressauftrag | Auftrag mit Priorität eilig | er ist eingeplant; verschobene Aufträge stehen mit ihrer Verspätung in der Schrittfolge | 5 min |
| E | Kompletter Durchlauf | neuer Auftrag über 2 Stück | Fertigware eingelagert; Freigabe, jeder Schritt und die Einlagerung als Schrittfolge | 6 h |

Die Zeitgrenzen sind Simulationszeit; eine pausierte Simulation verbraucht keine. C bucht echten Bestand ab, A und C legen eigene Aufträge an. `scenario.started` und `scenario.finished` tragen die Auftrags-ID als `correlationId`; der Link „Ereignisse“ zeigt die ganze Kette im Event Monitor. Ein Reset vergisst alle Läufe. Im Server-Betrieb gibt es dazu `GET /api/v1/scenarios` und `POST /api/v1/scenarios/{id}/run`.

## End-to-End-Tests

```bash
npm run e2e          # Browser-Modus: Szenarien A bis E und Bildrate, Vite auf Port 5173
npm run e2e:server   # Server-Modus: Szenarien A bis E gegen Spring Boot, PostgreSQL und OPC UA (Port 8080, Oberfläche 5179)
```

Die Tests liegen in `e2e/`. Lokal nutzt Playwright den installierten Chrome, ein Browser-Download ist nicht nötig. Laufende Server werden wiederverwendet. Die Server-Variante braucht Java 25 und baut den Server beim ersten Start (etwa eine Minute). Beide Läufe dauern danach unter einer Minute.

**Bildrate (Spec 14, Ziel 60 Bilder/s bei 25×):** gemessen am 16.11.2026 auf einem Windows-11-Laptop im Chrome-Fenster mit `npx playwright test fps --headed` in FactoryTwin bei 25-fachem Tempo: **60,0 Bilder/s**, also so viele, wie der Bildschirm anzeigt. Der Test misst über 5 s mit `requestAnimationFrame` und prüft nur gegen `FPS_MIN`, wenn die Variable gesetzt ist, weil Rechner ohne Grafikkarte (CI) weniger schaffen.

## Bedienung

In der 3D-Ansicht wählt ein Klick eine Anlage aus, ein Doppelklick fährt die Kamera hin. Mit der linken Maustaste dreht man die Ansicht, mit der rechten verschiebt man sie, das Mausrad zoomt. Ein Klick auf eine Maschine in der Overview öffnet FactoryTwin mit dieser Maschine ausgewählt.

Tastenkürzel: `Leertaste` startet und pausiert, `1` bis `5` stellen das Tempo von 1× bis 25× (beides in jedem Bereich). Nur in FactoryTwin: `Q W E R T` wechseln die Kameraperspektive, `F` fokussiert die Auswahl, `L` schaltet die Beschriftungen, `P` die Fahrwege und `Esc` hebt die Auswahl auf.

Mit `?seed=123` in der URL startet die Simulation mit einem festen Seed. Gleicher Seed und gleiche Eingriffe ergeben denselben Verlauf.

Die Grafikqualität lässt sich in der Werkzeugleiste von FactoryTwin oder in Settings umstellen: Hoch mit Schatten und Bloom, Mittel ohne Post-Processing, Schnell ohne Schatten. Auf Mobilgeräten startet die App mit „Schnell“.

## Aufbau

```
src/
  nexus/          Verbindung zur Anlage: NexusClient mit zwei Umsetzungen
    types.ts        Frame, Ereignisse, Befehle und Antworten (dasselbe JSON in beiden Betriebsarten)
    loop.ts         Takt der Browser-Anlage, wie SimulationLoop auf dem Server
    browser/        Web Worker mit dem Java-Kern (BrowserEngine, BrowserClient)
    server/         REST und WebSocket, mit Wiederverbinden und Nachholen verpasster Ereignisse
  twin/           Twin-Laufzeit (letzter und vorletzter Frame, Interpolation) und Szenenmodell
    sceneModel.ts   bildet den Frame auf die Formen ab, die die 3D-Szene liest
    plantLayout.ts  Zeichendaten: wo Maschinen, Bänder und Ladeplätze stehen
    layout.ts       Hallenlayout und AGV-Wegenetz (Zeichnung)
  store/          Zustand-Store: Simulationszustand, Ereignisse, Auswahl, Kamera
  scene/          3D-Szene; liest pro Bild aus dem Twin, ohne React-Re-Render
  shell/          Bereiche, Hash-Routing, Navigation, Simulationsleiste, Platzhalter
  areas/          die fertigen Bereiche: TwinArea, overview/, machines/, warehouse/, smartfactory/, flowpilot/, analytics/, scenarios/, events/, settings/
  ui/             Designsystem (kit/: Tabelle, Kennzahl, Zustandsmarke, Abschnitt, Feld; kit/charts/: Balken, Zustandsbalken, Säulen), Zustandsfarben, Detailansicht
```

Was sich laufend ändert, kommt mit jedem Frame. Stammdaten, Plan, Auftragsdetails, Wegenetz und Kennzahlen fragt die Oberfläche beim Kern ab (`products`, `schedule`, `order`, `warehouse`, `network`, `kpis`, `kpiSeries`), in beiden Betriebsarten mit derselben Antwort.

Die Oberfläche enthält keine Geschäftsregeln. Jeder Befehl geht als JSON an den Java-Kern und wird dort geprüft; lehnt die Anlage ab, zeigt die Meldungszeile unter der Simulationsleiste den Grund.

## Deployment

Der Build nutzt relative Pfade (`base: './'`) und läuft deshalb als statische Seite unter jedem Pfad, zum Beispiel auf GitHub Pages. Ein Deployment-Workflow ist noch nicht eingerichtet.
