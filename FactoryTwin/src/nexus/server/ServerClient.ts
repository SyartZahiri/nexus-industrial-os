import type { NexusClient } from '../client';
import type { Answer, Command, NexusEvent, NexusMessage, Query } from '../types';

interface Deps {
  WebSocket: typeof WebSocket;
  fetch: typeof fetch;
  setTimeout: typeof setTimeout;
}

const PAGE = 1000;
const MAX_BACKOFF_MS = 30_000;

/**
 * NexusClient against the server (Spec 14, mode server): commands over REST, frames and events over the WebSocket.
 * After a lost connection it reconnects with backoff and fetches the events it missed before passing new ones on.
 */
export class ServerClient implements NexusClient {
  readonly mode = 'server' as const;
  private readonly deps: Deps;
  private readonly base: string;
  private socket: WebSocket | null = null;
  private listener: (m: NexusMessage) => void = () => {};
  private lastSeq = 0;
  private backoffMs = 1_000;
  private stopped = false;
  private filling = false;
  private held: NexusEvent[] = [];

  constructor(base: string, deps: Partial<Deps> = {}) {
    this.base = base || window.location.origin;
    this.deps = {
      WebSocket: deps.WebSocket ?? globalThis.WebSocket,
      fetch: deps.fetch ?? globalThis.fetch.bind(globalThis),
      setTimeout: deps.setTimeout ?? globalThis.setTimeout.bind(globalThis),
    };
  }

  connect(onMessage: (m: NexusMessage) => void): () => void {
    this.listener = onMessage;
    this.stopped = false;
    this.open();
    return () => {
      this.stopped = true;
      this.socket?.close();
    };
  }

  private open(): void {
    this.listener({ type: 'connection', state: 'connecting' });
    const socket = new this.deps.WebSocket(this.base.replace(/^http/, 'ws') + '/ws');
    this.socket = socket;
    socket.onopen = () => {
      this.backoffMs = 1_000;
      this.listener({ type: 'connection', state: 'open' });
      if (this.lastSeq > 0) void this.fillGap();
    };
    socket.onmessage = (e: MessageEvent<string>) => this.receive(JSON.parse(e.data) as NexusMessage);
    socket.onclose = () => {
      this.listener({ type: 'connection', state: 'closed' });
      if (this.stopped) return;
      const wait = this.backoffMs;
      this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS);
      this.deps.setTimeout(() => this.open(), wait);
    };
  }

  private receive(m: NexusMessage): void {
    if (m.type === 'reset') {
      this.lastSeq = 0;
      this.held = [];
      this.listener(m);
    } else if (m.type === 'events') {
      if (this.filling) this.held.push(...m.items);
      else this.pass(m.items);
    } else {
      this.listener(m);
    }
  }

  /** Passes on events in seq order, each once. */
  private pass(items: NexusEvent[]): void {
    const fresh = items.filter((e) => e.seq > this.lastSeq).sort((a, b) => a.seq - b.seq);
    if (fresh.length === 0) return;
    this.lastSeq = fresh[fresh.length - 1].seq;
    this.listener({ type: 'events', items: fresh });
  }

  /** Events missed while the socket was closed; new ones from the socket wait until the gap is filled. */
  private async fillGap(): Promise<void> {
    this.filling = true;
    try {
      for (;;) {
        const r = await this.deps.fetch(`${this.base}/api/v1/events?after=${this.lastSeq}&limit=${PAGE}`, {
          headers: { Accept: 'application/json' },
        });
        if (!r.ok) break;
        const page = (await r.json()) as NexusEvent[];
        this.pass(page);
        if (page.length < PAGE) break;
      }
    } catch {
      // the next reconnect tries again
    } finally {
      this.filling = false;
      const held = this.held;
      this.held = [];
      this.pass(held);
    }
  }

  private async post(path: string, body: unknown): Promise<Answer> {
    try {
      const r = await this.deps.fetch(this.base + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(body),
      });
      return (await r.json()) as Answer;
    } catch {
      return { ok: false, status: 0, message: 'Server nicht erreichbar' };
    }
  }

  command(c: Command): Promise<Answer> {
    return this.post('/api/v1/sim/commands', c);
  }

  query<T>(q: Query): Promise<Answer<T>> {
    return this.post('/api/v1/sim/queries', q) as Promise<Answer<T>>;
  }

  private async control(method: string, path: string, body?: unknown): Promise<void> {
    const r = await this.deps.fetch(this.base + path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok) {
      const problem = (await r.json().catch(() => ({}))) as { detail?: string };
      throw new Error(problem.detail ?? `Fehler ${r.status}`);
    }
  }

  play() {
    return this.control('POST', '/api/v1/sim/play');
  }

  pause() {
    return this.control('POST', '/api/v1/sim/pause');
  }

  setSpeed(speed: number) {
    return this.control('PUT', '/api/v1/sim/speed', { speed });
  }

  reset(seed?: number) {
    return this.control('POST', '/api/v1/sim/reset', seed === undefined ? {} : { seed });
  }
}
