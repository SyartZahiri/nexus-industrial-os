import type { DomainEvent, DomainEventType } from './contracts';

type Handler = (e: DomainEvent) => void;

/** Minimal typed publish/subscribe bus for domain events. */
export class EventBus {
  private handlers = new Map<string, Set<Handler>>();

  on<T extends DomainEventType>(type: T | '*', handler: (e: Extract<DomainEvent, { type: T }>) => void): () => void {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    const set = this.handlers.get(type)!;
    set.add(handler as Handler);
    return () => set.delete(handler as Handler);
  }

  emit(e: DomainEvent): void {
    this.handlers.get(e.type)?.forEach((h) => h(e));
    this.handlers.get('*')?.forEach((h) => h(e));
  }

  clear(): void {
    this.handlers.clear();
  }
}
