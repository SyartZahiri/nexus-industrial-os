import type { NexusClient } from '../client';
import { matches, PAGE as EVENT_PAGE, type EventFilter, type EventPage } from '../eventFilter';
import type { Answer, Command, NexusEvent, NexusMessage, Query } from '../types';

interface Deps {
  WebSocket: typeof WebSocket;
  fetch: typeof fetch;
  setTimeout: typeof setTimeout;
}

/** The server greets each connection with its run; a reset names the new one. */
type Incoming =
  | Exclude<NexusMessage, { type: 'reset' }>
  | { type: 'hello'; run: string; seed: number }
  | { type: 'reset'; seed: number; run?: string };

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
  /** Server process and run the events so far belong to; null before the first greeting. */
  private runKey: string | null = null;
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
    };
    socket.onmessage = (e: MessageEvent<string>) => this.receive(JSON.parse(e.data) as Incoming);
    socket.onclose = () => {
      this.listener({ type: 'connection', state: 'closed' });
      if (this.stopped) return;
      const wait = this.backoffMs;
      this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS);
      this.deps.setTimeout(() => this.open(), wait);
    };
  }

  private receive(m: Incoming): void {
    if (m.type === 'hello') {
      // a reconnect to a different run (server restarted, or reset while we were away) starts over;
      // to the same run, the events missed while away are fetched first
      if (this.runKey !== null && this.runKey !== m.run) this.startOver(m.seed);
      else if (this.runKey === m.run && this.lastSeq > 0) void this.fillGap();
      this.runKey = m.run;
    } else if (m.type === 'reset') {
      if (m.run !== undefined) this.runKey = m.run;
      this.startOver(m.seed);
    } else if (m.type === 'events') {
      if (this.filling) this.held.push(...m.items);
      else this.pass(m.items);
    } else {
      this.listener(m);
    }
  }

  private startOver(seed: number): void {
    this.lastSeq = 0;
    this.held = [];
    this.listener({ type: 'reset', seed });
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
    let filled = false;
    try {
      for (;;) {
        const r = await this.deps.fetch(`${this.base}/api/v1/events?after=${this.lastSeq}&limit=${PAGE}`, {
          headers: { Accept: 'application/json' },
        });
        if (!r.ok) break;
        const page = (await r.json()) as NexusEvent[];
        this.pass(page);
        if (page.length < PAGE) {
          filled = true;
          break;
        }
      }
    } catch {
      // handled below: the gap stays open
    } finally {
      this.filling = false;
      const held = this.held;
      this.held = [];
      // Passing the held events over an unfilled gap would move lastSeq past it for good. Instead this connection is
      // dropped: the reconnect greets again and the gap is fetched once more.
      if (filled) this.pass(held);
      else this.socket?.close();
    }
  }

  private async post(path: string, body: unknown): Promise<Answer> {
    try {
      const r = await this.deps.fetch(this.base + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(body),
      });
      const answer = (await r.json().catch(() => ({}))) as Partial<Answer> & { detail?: string };
      if (typeof answer.ok === 'boolean') return answer as Answer;
      // not the core's answer (a filter, a proxy or Spring refused it): still a refusal the user must see
      return { ok: false, status: r.status, message: answer.detail ?? `Fehler ${r.status}` };
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

  /** The server filters type exactly and knows no severity: prefix and severity are filtered here. */
  async events(f: EventFilter): Promise<EventPage> {
    const limit = f.limit ?? EVENT_PAGE;
    const after = f.after ?? 0;
    const params = new URLSearchParams({ after: String(after), limit: String(limit) });
    if (f.source) params.set('source', f.source);
    if (f.correlationId) params.set('correlationId', f.correlationId);
    if (f.type && !f.type.endsWith('.')) params.set('type', f.type);
    const r = await this.deps.fetch(`${this.base}/api/v1/events?${params}`, { headers: { Accept: 'application/json' } });
    if (!r.ok) throw new Error(`Ereignisse nicht lesbar (${r.status})`);
    const page = (await r.json()) as NexusEvent[];
    return { items: page.filter((e) => matches(e, f)), more: page.length === limit, next: page.at(-1)?.seq ?? after };
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
