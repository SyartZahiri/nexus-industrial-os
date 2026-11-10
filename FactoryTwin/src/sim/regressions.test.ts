import { describe, expect, it } from 'vitest';
import { SimulationEngine } from './engine';

const run = (e: SimulationEngine, s: number) => {
  for (let t = 0; t < s; t += 0.1) e.step(0.1);
};

describe('Regressionen', () => {
  it('Stopp während des Rüstens setzt danach das Rüsten fort', () => {
    const e = new SimulationEngine(3, 600);
    e.setRules({ randomFaults: false });
    let m = e.state.machines.find((x) => x.status === 'SETUP');
    for (let i = 0; i < 20000 && !m; i++) {
      e.step(0.1);
      m = e.state.machines.find((x) => x.status === 'SETUP');
    }
    expect(m).toBeTruthy();
    e.stopMachine(m!.id);
    e.startMachine(m!.id);
    expect(m!.status).toBe('SETUP');
  });

  it('Absenken der Abschaltgrenze zieht die Warngrenze mit', () => {
    const e = new SimulationEngine(3, 0);
    e.configureMachine('CNC-01', { tempTrip: 60 });
    const cfg = () => e.machine('CNC-01')!.config;
    expect(cfg().tempTrip).toBe(60);
    expect(cfg().tempWarn).toBeLessThanOrEqual(56);
    e.configureMachine('CNC-01', { tempWarn: 70 });
    expect(cfg().tempTrip).toBeGreaterThanOrEqual(74);
  });

  it('Nicht gestartete, überfällige Aufträge stehen auf Verzug', () => {
    const e = new SimulationEngine(3, 0);
    e.setRules({ wipLimit: 0, autoOrders: false });
    const id = e.createOrder({ product: 'FP12', quantity: 3, priority: 'normal', dueIn: 30 });
    run(e, 60);
    expect(e.order(id)!.status).toBe('VERZUG');
    expect(e.order(id)!.startedAt).toBeNull();
  });

  it('Teile auf dem Zuführband einer ausgefallenen Zelle blockieren den Auftrag', () => {
    const e = new SimulationEngine(8, 900);
    e.setRules({ randomFaults: false, autoRepair: false });
    const cv = e.conveyor('CV-01')!;
    for (let i = 0; i < 30000 && !cv.items.length; i++) e.step(0.1);
    expect(cv.items.length).toBeGreaterThan(0);
    const orderId = e.wp(cv.items[0].wpId).orderId;
    e.injectFault('ROB-01', 'SERVO');
    run(e, 3);
    expect(e.order(orderId)!.status).toBe('BLOCKIERT');
  });

  it('Slider-Bewegungen erzeugen einen einzigen Protokolleintrag', () => {
    const e = new SimulationEngine(3, 0);
    const n = e.state.events.length;
    for (let v = 105; v <= 130; v += 5) e.configureMachine('CNC-02', { override: v });
    for (let v = 19; v >= 12; v--) e.setRules({ wipLimit: v });
    for (const v of [0.5, 0.7, 0.9]) e.setConveyorSpeed('CV-01', v);
    const added = e.state.events.slice(n).map((x) => x.message);
    expect(added).toEqual(['Override 100 → 130 %', 'WIP-Limit: 12', 'Bandgeschwindigkeit 0,40 → 0,90 m/s']);
  });
});
