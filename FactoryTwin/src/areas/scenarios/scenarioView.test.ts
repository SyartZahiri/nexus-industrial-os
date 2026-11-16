import { describe, expect, it } from 'vitest';
import type { ScenarioList, ScenarioRun } from '../../nexus/types';
import { activeRun, lastRunOf, runTiming } from './scenarioView';

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

  it('aRunningScenarioSaysHowLongItRunsAndItsLimit', () => {
    const r = { ...run('SZ-1', 'E', 'RUNNING' as const), startedAtMs: 60_000 };
    expect(runTiming(r, 12 * 60_000 + 30_000, 360)).toBe('seit 11 min · Grenze 360 min');
    expect(runTiming({ ...r, state: 'PASSED', finishedAtMs: 31 * 60_000 }, 99 * 60_000, 360)).toBe('nach 30 min');
    expect(runTiming(r, 30_000, 360)).toBe('seit 0 min · Grenze 360 min'); // a clock behind the start never shows a negative time
  });
});
