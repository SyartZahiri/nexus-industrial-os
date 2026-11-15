import { describe, expect, it } from 'vitest';
import type { ScenarioList, ScenarioRun } from '../../nexus/types';
import { activeRun, lastRunOf } from './scenarioView';

const run = (runId: string, scenario: string, state: ScenarioRun['state']): ScenarioRun => ({
  runId,
  scenario,
  state,
  orderId: null,
  startedAtMs: 0,
  finishedAtMs: null,
  steps: [],
});
const list = (runs: ScenarioRun[]): ScenarioList => ({ definitions: [], runs });

describe('scenario view', () => {
  it('aRunningScenarioBlocksTheOthers', () => {
    expect(activeRun(null)).toBeNull();
    expect(activeRun(list([run('SZ-2', 'A', 'PASSED')]))).toBeNull();
    expect(activeRun(list([run('SZ-3', 'B', 'WAITING'), run('SZ-2', 'A', 'PASSED')]))?.runId).toBe('SZ-3');
    expect(activeRun(list([run('SZ-3', 'B', 'RUNNING')]))?.runId).toBe('SZ-3');
  });

  it('shows the newest run of each scenario', () => {
    const l = list([run('SZ-3', 'A', 'FAILED'), run('SZ-2', 'B', 'PASSED'), run('SZ-1', 'A', 'PASSED')]);
    expect(lastRunOf(l, 'A')?.runId).toBe('SZ-3');
    expect(lastRunOf(l, 'E')).toBeNull();
  });
});
