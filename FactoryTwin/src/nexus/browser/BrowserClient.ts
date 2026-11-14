import type { NexusClient } from '../client';
import type { Answer, Command, NexusMessage, Query } from '../types';
import type { WorkerRequest } from './engine';

type Pending = (answer: unknown) => void;
type WithoutId<T> = T extends unknown ? Omit<T, 'id'> : never;

/** NexusClient against the Java core in a web worker (Spec 14, mode browser). */
export class BrowserClient implements NexusClient {
  readonly mode = 'browser' as const;
  private readonly worker: Worker;
  private readonly pending = new Map<number, Pending>();
  private nextId = 1;
  private listener: (m: NexusMessage) => void = () => {};

  constructor() {
    const url = new URL('./nexus.worker.ts', import.meta.url);
    url.search = window.location.search; // ?seed= reaches the worker
    this.worker = new Worker(url, { type: 'module' });
    this.worker.onmessage = (e: MessageEvent<{ id?: number; answer?: unknown; message?: NexusMessage }>) => {
      if (e.data.message) this.listener(e.data.message);
      if (e.data.id !== undefined) {
        this.pending.get(e.data.id)?.(e.data.answer);
        this.pending.delete(e.data.id);
      }
    };
  }

  private request(r: WithoutId<WorkerRequest>): Promise<unknown> {
    const id = this.nextId++;
    return new Promise((resolve) => {
      this.pending.set(id, resolve);
      this.worker.postMessage({ ...r, id });
    });
  }

  connect(onMessage: (m: NexusMessage) => void): () => void {
    this.listener = onMessage;
    onMessage({ type: 'connection', state: 'open' });
    void this.request({ kind: 'hello' });
    return () => {
      this.listener = () => {};
      this.worker.terminate();
    };
  }

  command(c: Command): Promise<Answer> {
    return this.request({ kind: 'command', body: c }) as Promise<Answer>;
  }

  query<T>(q: Query): Promise<Answer<T>> {
    return this.request({ kind: 'query', body: q }) as Promise<Answer<T>>;
  }

  async play() {
    await this.request({ kind: 'play' });
  }

  async pause() {
    await this.request({ kind: 'pause' });
  }

  async setSpeed(speed: number) {
    await this.request({ kind: 'speed', speed });
  }

  async reset(seed?: number) {
    await this.request({ kind: 'reset', seed });
  }
}
