import type { Schedule } from '../../nexus/types';

const HOUR = 3_600_000;
const MIN_WIDTH = 0.5;

export interface GanttBar {
  key: string;
  orderId: string;
  seq: number;
  left: number;
  width: number;
  setupShare: number;
  late: boolean;
}

/**
 * Positions of the planned operations in percent of the axis. The axis starts at now or the first start, whichever
 * is earlier, and spans at least an hour; every machine gets a lane, also without operations.
 */
export function ganttLayout(s: Schedule, nowMs: number, machineIds: string[]) {
  const fromMs = Math.min(nowMs, ...s.ops.map((o) => o.startMs));
  const toMs = Math.max(fromMs + HOUR, ...s.ops.map((o) => o.endMs));
  const span = toMs - fromMs;
  const at = (ms: number) => ((ms - fromMs) / span) * 100;
  const late = new Set(s.lateOrders);
  const lanes = machineIds.map((machineId) => ({
    machineId,
    bars: s.ops
      .filter((o) => o.machineId === machineId)
      .map((o): GanttBar => {
        const length = o.endMs - o.startMs;
        return {
          key: `${o.orderId}/${o.seq}`,
          orderId: o.orderId,
          seq: o.seq,
          left: at(o.startMs),
          width: Math.max(MIN_WIDTH, at(o.endMs) - at(o.startMs)),
          setupShare: length > 0 ? Math.min(1, o.setupMs / length) : 0,
          late: late.has(o.orderId),
        };
      }),
  }));
  return { fromMs, toMs, nowLeft: at(nowMs), lanes };
}
