import { describe, expect, it } from 'vitest';
import { applyRules, rulesPatch } from './rulesForm';
import type { Rules } from '../../nexus/types';

const base: Rules = {
  randomFaults: true,
  autoRepair: true,
  autoMaintenance: true,
  maintenanceThreshold: 0.85,
  autoOrders: true,
  autoOrderIntervalMinutes: 6,
  maxOpenOrders: 6,
  shipping: true,
  dispatchRule: 'PRIORITY',
};

describe('rules form', () => {
  it('sends only what changed', () => {
    expect(rulesPatch(base, { ...base })).toBeNull();
    expect(rulesPatch(base, { ...base, dispatchRule: 'EDD', maxOpenOrders: 8 })).toEqual({
      command: 'setRules',
      dispatchRule: 'EDD',
      maxOpenOrders: 8,
    });
  });

  it('aRefusedRuleKeepsTheFormOnTheValidValues', async () => {
    const sent: unknown[] = [];
    const result = await applyRules(
      base,
      { ...base, maintenanceThreshold: 1.5 },
      async (c) => {
        sent.push(c);
        return false; // the core refuses
      },
      async () => base, // and still reports the old rules
    );
    expect(sent).toEqual([{ command: 'setRules', maintenanceThreshold: 1.5 }]);
    expect(result).toEqual(base);
  });
});
