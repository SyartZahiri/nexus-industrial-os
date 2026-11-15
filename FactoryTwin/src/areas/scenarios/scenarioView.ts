import type { ScenarioList, ScenarioRun } from '../../nexus/types';

/** The run that still waits for its trigger or watches the plant; while it exists, no other scenario may start. */
export function activeRun(list: ScenarioList | null): ScenarioRun | null {
  return list?.runs.find((r) => r.state === 'WAITING' || r.state === 'RUNNING') ?? null;
}

/** Runs come newest first from the core. */
export function lastRunOf(list: ScenarioList | null, id: string): ScenarioRun | null {
  return list?.runs.find((r) => r.scenario === id) ?? null;
}
