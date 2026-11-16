import type { ScenarioList, ScenarioRun } from '../../nexus/types';

/** The run that still waits for its trigger or watches the plant; while it exists, no other scenario may start. */
export function activeRun(list: ScenarioList | null): ScenarioRun | null {
  return list?.runs.find((r) => r.state === 'WAITING' || r.state === 'RUNNING') ?? null;
}

/** Runs come newest first from the core. */
export function lastRunOf(list: ScenarioList | null, id: string): ScenarioRun | null {
  return list?.runs.find((r) => r.scenario === id) ?? null;
}

/** How long a run has been going (with its limit), or how long it took once it ended; whole simulated minutes. */
export function runTiming(run: ScenarioRun, nowMs: number, limitMinutes: number): string {
  const minutes = (ms: number) => Math.max(0, Math.floor(ms / 60_000));
  if (run.finishedAtMs !== null) return `nach ${minutes(run.finishedAtMs - run.startedAtMs)} min`;
  return `seit ${minutes(nowMs - run.startedAtMs)} min · Grenze ${limitMinutes} min`;
}
