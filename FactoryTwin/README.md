# FactoryTwin

FactoryTwin ist ein 3D-Digital-Twin einer kleinen Fertigung mit Zerspanung, Montage, Qualitätsprüfung und Intralogistik. Alles, was in der 3D-Ansicht passiert und im Dashboard steht, kommt aus derselben Simulation.

Gebaut mit React 19, TypeScript, Vite, Three.js, React Three Fiber (mit drei), Tailwind CSS 4 und Zustand. Getestet wird mit Vitest.

```bash
npm install
npm run dev        # http://localhost:5173
npm test           # Tests der Simulations-Engine
npm run build      # statischer Build in dist/
```

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

## Bedienung

In der 3D-Ansicht wählt ein Klick eine Anlage aus, ein Doppelklick fährt die Kamera hin. Mit der linken Maustaste dreht man die Ansicht, mit der rechten verschiebt man sie, das Mausrad zoomt. Jede Anlage und beide Lager lassen sich auch über die Liste links auswählen.

Tastenkürzel: `Leertaste` startet und pausiert, `1` bis `5` stellen das Tempo von 1× bis 25×, `Q W E R T` wechseln die Kameraperspektive, `F` fokussiert die Auswahl, `L` schaltet die Beschriftungen, `P` die Fahrwege und `Esc` hebt die Auswahl auf.

Mit `?seed=123` in der URL startet die Simulation mit einem festen Seed. Gleicher Seed und gleiche Eingriffe ergeben denselben Verlauf.

Die Grafikqualität lässt sich oben rechts umstellen: Hoch mit Schatten und Bloom, Mittel ohne Post-Processing, Schnell ohne Schatten. Auf Mobilgeräten startet die App mit „Schnell“.

## Aufbau

```
src/
  sim/            Domänenmodell und Engine, ohne Framework, headless testbar
    types.ts        Datentypen
    config.ts       Produkte, Arbeitspläne, Störungsarten, Physik-Parameter
    layout.ts       Hallenlayout und gerichteter AGV-Graph
    engine.ts       SimulationEngine: step(dt), Disposition, Umplanung, Befehle
    invariants.ts   Konsistenzprüfung des Zustands (für Tests und Fehlersuche)
    *.test.ts       Durchsatz, Umplanung, Blockaden, Determinismus, Chaos-Tests
  integration/    Öffentliche Schnittstellen
    contracts.ts    FactoryCommands, DomainEvent, TelemetryFrame, RoutePlanner, FactoryConnector
    api.ts          Fassade, im Browser auch als window.FactoryTwin erreichbar
    adapters.ts     Demo-Adapter für SmartFactory und RouteMind
  store/          Zustand-Store: Engine-Instanz, Snapshots für die Panels, UI-Zustand
  scene/          3D-Szene; liest den Engine-Zustand pro Frame ohne React-Re-Render
  ui/             Dashboard, Panels und Charts (SVG, ohne Chart-Bibliothek)
```

Die Engine hält den gesamten Zustand, und Änderungen laufen nur über `FactoryCommands`. Die 3D-Komponenten lesen den Zustand direkt in `useFrame`. Die React-Panels bekommen etwa sieben Mal pro Sekunde einen unveränderlichen Snapshot aus dem Store. Dadurch läuft die Simulation flüssig, und das Dashboard rendert nicht in jedem Frame neu.

Die Chaos-Tests lösen über Stunden zufällige Bedienereingriffe aus (Störungen, Stopps, Regeländerungen, Stornierungen) und prüfen dabei laufend, dass kein Werkstück verloren geht oder doppelt existiert, dass Reservierungen und Aufträge stimmen und dass die Anlage danach wieder anläuft.

### SmartFactory und RouteMind anbinden

```ts
const ft = window.FactoryTwin;
ft.subscribe('machine.fault', (e) => console.log(e.machineId, e.fault));
ft.commands.createOrder({ product: 'GH200', quantity: 5, priority: 'eilig', source: 'smartfactory' });
ft.getTelemetry();               // TelemetryFrame mit Maschinen, AGVs und Kennzahlen
ft.setRoutePlanner(myPlanner);   // eigenen RoutePlanner einsetzen, z. B. RouteMind
```

SmartFactory wird als `FactoryConnector` angebunden. Der Adapter abonniert die Domain-Events, liest Telemetrie und steuert Aufträge ein. In der Demo bestellt er nach, sobald der Umlaufbestand niedrig ist.

RouteMind ersetzt als `RoutePlanner` den lokalen Dijkstra-Planer zur Laufzeit. Der Demo-Adapter weicht Staus aus, indem Kanten in der Nähe anderer Fahrzeuge teurer werden.

Für eine echte Anbindung über REST, MQTT oder OPC UA tauscht man nur den Adapter. Engine und UI bleiben gleich.

## Deployment

Der Build nutzt relative Pfade (`base: './'`) und läuft deshalb als statische Seite unter jedem Pfad, zum Beispiel auf GitHub Pages. Ein Deployment-Workflow ist noch nicht eingerichtet.
