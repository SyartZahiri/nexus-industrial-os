import type { NexusClient } from '../client';
import type { Answer, Command, NexusMessage, Query } from '../types';
import type { WorkerRequest } from './engine';

type Pending = (answer: unknown) => void;
type WithoutId<T> = T extends unknown ? Omit<T, 'id'> : never;

const UNREACHABLE: Answer = { ok: false, status: 0, message: 'Java-Kern im Browser läuft nicht' };

/** Vite bundles the worker only for exactly this form of new Worker(new URL(...)). */
const startWorker = () => new Worker(new URL('./nexus.worker.ts', import.meta.url), { type: 'module' });

/**
 * NexusClient against the Java core in a web worker (Spec 14, mode browser). The plant counts as reachable once the
 * worker answered the greeting; a worker that fails to load shows as closed and answers every open request.
 */
export class BrowserClient implements NexusClient {
  readonly mode = 'browser' as const;
  private readonly worker: Worker;
  private readonly pending = new Map<number, Pending>();
  private nextId = 1;
  private broken = false;
  private listener: (m: NexusMessage) => void = () => {};

  constructor(createWorker: () => Worker = startWorker) {
    this.worker = createWorker();
    this.worker.onmessage = (e: MessageEvent<{ id?: number; answer?: unknown; message?: NexusMessage }>) => {
      if (e.data.message) this.listener(e.data.message);
      if (e.data.id !== undefined) {
        this.pending.get(e.data.id)?.(e.data.answer);
        this.pending.delete(e.data.id);
      }
    };
    this.worker.onerror = () => this.fail();
  }

  private fail(): void {
    this.broken = true;
    this.listener({ type: 'connection', state: 'closed' });
    for (const resolve of this.pending.values()) resolve(UNREACHABLE);
    this.pending.clear();
  }

  private request(r: WithoutId<WorkerRequest>): Promise<unknown> {
    if (this.broken) return Promise.resolve(UNREACHABLE);
    const id = this.nextId++;
    return new Promise((resolve) => {
      this.pending.set(id, resolve);
      this.worker.postMessage({ ...r, id });
    });
  }

  connect(onMessage: (m: NexusMessage) => void): () => void {
    this.listener = onMessage;
    onMessage({ type: 'connection', state: 'connecting' });
    const search = typeof window === 'undefined' ? '' : window.location.search;
    const seed = Number(new URLSearchParams(search).get('seed'));
    void this.request({ kind: 'hello', seed: Number.isInteger(seed) && seed > 0 ? seed : undefined }).then((answer) => {
      const refused = answer !== null && typeof answer === 'object' && (answer as Answer).ok === false;
      if (refused) this.fail();
      else if (!this.broken) this.listener({ type: 'connection', state: 'open' });
    });
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
