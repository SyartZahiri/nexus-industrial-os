import type { MachineStatus } from '../../../nexus/types';

const ORDER: MachineStatus[] = ['RUNNING', 'SETUP', 'IDLE', 'BLOCKED', 'STOPPED', 'FAULT', 'REPAIR', 'MAINTENANCE', 'UNKNOWN'];

/** Shares of the time in each status, in a fixed order so stacked bars of different machines line up. */
export function statusShares(timeInStatusMs: Record<string, number>): { status: MachineStatus; share: number; ms: number }[] {
  const total = ORDER.reduce((sum, s) => sum + (timeInStatusMs[s] ?? 0), 0);
  if (total <= 0) return [];
  return ORDER.filter((s) => (timeInStatusMs[s] ?? 0) > 0).map((s) => ({
    status: s,
    ms: timeInStatusMs[s],
    share: timeInStatusMs[s] / total,
  }));
}
