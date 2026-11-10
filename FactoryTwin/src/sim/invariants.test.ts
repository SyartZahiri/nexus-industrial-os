import { describe, expect, it } from 'vitest';
import { SimulationEngine } from './engine';
import { checkInvariants } from './invariants';
import { Rng } from './rng';

function run(e: SimulationEngine, seconds: number, onTick?: (t: number) => void) {
  for (let t = 0; t < seconds; t += 0.1) {
    e.step(0.1);
    onTick?.(t);
  }
}

describe('Invarianten', () => {
  it('bleiben im Normalbetrieb über 3 h erhalten', () => {
    const e = new SimulationEngine(11, 0);
    let errs: string[] = [];
    run(e, 3 * 3600, (t) => {
      if (Math.round(t * 10) % 50 === 0 && !errs.length) errs = checkInvariants(e);
    });
    expect(errs).toEqual([]);
  }, 60000);

  for (const seed of [1, 2, 3, 42]) {
    it(`überstehen zufällige Bedienereingriffe (Seed ${seed})`, () => {
      const e = new SimulationEngine(seed, 300);
      const r = new Rng(seed * 97);
      const ids = {
        m: e.state.machines.map((m) => m.id),
        c: e.state.conveyors.map((c) => c.id),
        a: e.state.agvs.map((a) => a.id),
      };
      let errs: string[] = [];
      let maxBlocked = 0;
      const actions = [
        () => e.injectFault(r.pick(ids.m)),
        () => e.stopMachine(r.pick(ids.m)),
        () => e.startMachine(r.pick(ids.m)),
        () => e.repair(r.pick([...ids.m, ...ids.c, ...ids.a])),
        () => e.maintain(r.pick(ids.m)),
        () => e.configureMachine(r.pick(ids.m), { override: r.int(50, 130), coolant: r.next() > 0.3 }),
        () => e.injectFault(r.pick(ids.c)),
        () => e.stopConveyor(r.pick(ids.c)),
        () => e.startConveyor(r.pick(ids.c)),
        () => e.setConveyorSpeed(r.pick(ids.c), r.range(0.1, 1.2)),
        () => e.injectFault(r.pick(ids.a)),
        () => e.setAgvEnabled(r.pick(ids.a), r.next() > 0.3),
        () => e.sendAgvToCharge(r.pick(ids.a)),
        () => e.setRules({ dynamicRouting: r.next() > 0.4 }),
        () => e.setRules({ dispatchRule: r.pick(['FIFO', 'PRIORITY', 'EDD', 'SPT'] as const) }),
        () => e.setRules({ wipLimit: r.int(4, 30) }),
        () => e.setRules({ autoRepair: r.next() > 0.3 }),
        () => e.createOrder({ product: r.pick(['GH200', 'AW50', 'FP12', 'MT7'] as const), quantity: r.int(1, 10), priority: 'normal' }),
        () => {
          const o = e.state.orders.find((x) => x.status === 'AKTIV');
          if (o) e.cancelOrder(o.id);
        },
        () => e.orderRawMaterial(20),
      ];
      // 2 h chaos, then 1 h recovery with everything switched back on
      run(e, 7200, (t) => {
        if (r.next() < 0.004) r.pick(actions)();
        if (Math.round(t * 10) % 50 === 0 && !errs.length) errs = checkInvariants(e);
      });
      expect(errs).toEqual([]);
      e.setRules({ autoRepair: true, dynamicRouting: true, wipLimit: 20 });
      for (const id of ids.m) e.startMachine(id);
      for (const id of ids.c) e.startConveyor(id);
      for (const id of ids.a) e.setAgvEnabled(id, true);
      const before = e.state.metrics.totalGood;
      // Waiting that started during the chaos phase (e.g. behind a vehicle whose fault nobody
      // repaired) is not part of the recovery measurement.
      for (const a of e.state.agvs) a.blockedTime = 0;
      run(e, 3600, () => {
        for (const a of e.state.agvs) maxBlocked = Math.max(maxBlocked, a.blockedTime);
      });
      expect(checkInvariants(e)).toEqual([]);
      // Plant recovers and keeps producing; no vehicle stays stuck behind another
      expect(e.state.metrics.totalGood - before).toBeGreaterThan(25);
      expect(maxBlocked).toBeLessThan(90);
    }, 60000);
  }
});
