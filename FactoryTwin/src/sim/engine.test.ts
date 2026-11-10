import { describe, expect, it } from 'vitest';
import { SimulationEngine } from './engine';

function run(engine: SimulationEngine, seconds: number) {
  for (let t = 0; t < seconds; t += 0.1) engine.step(0.1);
}

describe('SimulationEngine', () => {
  it('produces parts continuously without deadlocks', { timeout: 30000 }, () => {
    const e = new SimulationEngine(4711, 0);
    const checkpoints: number[] = [];
    for (let h = 0; h < 4; h++) {
      run(e, 3600);
      checkpoints.push(e.state.metrics.totalGood);
      const s = e.state;
      console.log(
        `h${h + 1}: good=${s.metrics.totalGood} scrap=${s.metrics.totalScrap} tp=${e.throughput().toFixed(1)}/h oee=${(e.overallOee() * 100).toFixed(1)}% ` +
          `wip=${[...s.workpieces.values()].filter((w) => w.location.type !== 'finished' && w.location.type !== 'scrap').length} raw=${s.inventory.raw} ` +
          `orders=${s.orders.filter((o) => o.status !== 'ABGESCHLOSSEN').length} kWh=${s.metrics.energyKWh.toFixed(0)}`,
      );
      console.log('  machines', s.machines.map((m) => `${m.id}:${m.status}:${Math.round(m.stats.utilEma * 100)}%:w${Math.round(m.wear * 100)}`).join(' '));
      console.log('  agvs', s.agvs.map((a) => `${a.id}:${a.state}:${Math.round(a.battery)}%:${Math.round(a.stats.busyEma * 100)}%`).join(' '));
    }
    for (let i = 1; i < checkpoints.length; i++) expect(checkpoints[i]).toBeGreaterThan(checkpoints[i - 1] + 20);
  });

  it('reroutes work when a machine fails (dynamic routing)', () => {
    const e = new SimulationEngine(99, 600);
    e.setRules({ randomFaults: false, autoRepair: false });
    e.injectFault('CNC-01', 'SERVO');
    run(e, 900);
    const cnc1 = e.machine('CNC-01')!;
    expect(cnc1.status).toBe('FAULT');
    expect(e.state.events.some((ev) => ev.source === 'Umplanung')).toBe(true);
    const fr = e.state.machines.filter((m) => m.id === 'CNC-02' || m.id === 'CNC-04');
    expect(fr.some((m) => m.stats.partsGood > 0)).toBe(true);
    e.repair('CNC-01');
    run(e, 600);
    expect(e.machine('CNC-01')!.status).not.toBe('FAULT');
  });

  it('halts dependent flow with static routing', () => {
    const e = new SimulationEngine(7, 600);
    e.setRules({ randomFaults: false, autoRepair: false, dynamicRouting: false });
    e.injectFault('QC-01', 'VISION');
    const before = e.state.metrics.totalGood;
    run(e, 1200);
    // Nothing passes quality control → at most parts already past QC reach the warehouse.
    expect(e.state.metrics.totalGood - before).toBeLessThan(10);
    expect(e.state.orders.some((o) => o.status === 'BLOCKIERT')).toBe(true);
  });

  it('is deterministic for a given seed', () => {
    const a = new SimulationEngine(123, 300);
    const b = new SimulationEngine(123, 300);
    run(a, 600);
    run(b, 600);
    expect(a.state.metrics.totalGood).toBe(b.state.metrics.totalGood);
    expect(a.state.metrics.energyKWh).toBeCloseTo(b.state.metrics.energyKWh, 6);
  });
});
