import type { Frame, Kpis, MachineStatus, NexusEvent } from '../../nexus/types';
import { nf } from '../../ui/format';

const FINISHED = new Set(['COMPLETED', 'CANCELLED']);

export interface OverviewFigures {
  throughput: string;
  oee: string;
  onTime: string;
  leadTime: string;
  openOrders: number;
  lateOrders: number;
  activeTransports: number;
  machines: { id: string; status: MachineStatus }[];
  bottleneck: string | null;
  alerts: NexusEvent[];
}

/**
 * The overview's figures, formatted. A figure without a basis (no finished order yet) is "–", never NaN or a
 * flattering 100 %: the core reports onTimeRate 1 and lead time 0 when nothing was finished.
 */
export function overviewFigures(frame: Frame | null, kpis: Kpis | null, events: NexusEvent[]): OverviewFigures {
  const open = frame ? frame.orders.filter((o) => !FINISHED.has(o.status)) : [];
  const finishedInWindow = kpis ? kpis.throughputPerHour > 0 : false;
  const oees = kpis ? Object.values(kpis.machines).map((m) => m.oee).filter((v) => Number.isFinite(v)) : [];
  return {
    // below 10 one decimal ("2,5"), a bare zero stays "0"
    throughput: kpis ? nf(kpis.throughputPerHour, kpis.throughputPerHour < 10 ? 1 : 0).replace(/,0$/, '') : '–',
    oee: oees.length ? `${nf((oees.reduce((a, b) => a + b, 0) / oees.length) * 100)} %` : '–',
    onTime: kpis && finishedInWindow ? `${nf(kpis.onTimeRate * 100)} %` : '–',
    leadTime: kpis && finishedInWindow ? `${nf(kpis.avgLeadTimeS / 60)} min` : '–',
    openOrders: open.length,
    lateOrders: frame ? open.filter((o) => o.dueAtMs < frame.simTimeMs).length : 0,
    activeTransports: frame ? frame.transports.length : 0,
    machines: frame ? frame.machines.map((m) => ({ id: m.id, status: m.status })) : [],
    bottleneck: kpis?.bottleneckMachineId ?? null,
    alerts: events.filter((e) => e.severity === 'ERROR' || e.severity === 'WARNING').slice(-8).reverse(),
  };
}
