import type { NexusEvent } from './types';

export interface EventFilter {
  type?: string;
  source?: string;
  severity?: NexusEvent['severity'];
  correlationId?: string;
  after?: number;
  limit?: number;
}

/** next is where the following page starts (after = next); it moves on even when a page found nothing. */
export type EventPage = { items: NexusEvent[]; more: boolean; next: number };

export const PAGE = 500;

/** type is a prefix ("machine." covers machine.status and machine.fault); the others match exactly. */
export function matches(e: NexusEvent, f: EventFilter): boolean {
  return (
    (!f.type || e.type.startsWith(f.type)) &&
    (!f.source || e.source === f.source) &&
    (!f.severity || e.severity === f.severity) &&
    (!f.correlationId || e.correlationId === f.correlationId)
  );
}

/** At most one page of matching events after f.after, oldest first; more says whether another page follows. */
export function filterEvents(all: NexusEvent[], f: EventFilter): EventPage {
  const limit = f.limit ?? PAGE;
  const items: NexusEvent[] = [];
  for (const e of all) {
    if (f.after !== undefined && e.seq <= f.after) continue;
    if (!matches(e, f)) continue;
    if (items.length === limit) return { items, more: true, next: items[items.length - 1].seq };
    items.push(e);
  }
  return { items, more: false, next: items.at(-1)?.seq ?? f.after ?? 0 };
}
