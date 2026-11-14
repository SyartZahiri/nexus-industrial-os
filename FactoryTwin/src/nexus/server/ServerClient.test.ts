import { describe, expect, it, vi } from 'vitest';
import { ServerClient } from './ServerClient';
import type { NexusEvent, NexusMessage } from '../types';

class FakeSocket {
  static all: FakeSocket[] = [];
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  constructor(readonly url: string) {
    FakeSocket.all.push(this);
  }
  open() {
    this.onopen?.();
  }
  receive(m: unknown) {
    this.onmessage?.({ data: JSON.stringify(m) });
  }
  drop() {
    this.onclose?.();
  }
  close() {}
}

const event = (seq: number): NexusEvent => ({
  seq,
  eventId: `1-${seq}`,
  simTimeMs: seq * 100,
  type: 'order.created',
  source: 'smartfactory',
  severity: 'INFO',
  correlationId: null,
  payload: {},
});

function setup(gap: NexusEvent[] = []) {
  FakeSocket.all = [];
  const timers: (() => void)[] = [];
  const fetch = vi.fn(
    async (url: string) => new Response(JSON.stringify(url.includes('/events') ? gap : {}), { status: 200 }),
  );
  const client = new ServerClient('http://host:5173', {
    WebSocket: FakeSocket as unknown as typeof WebSocket,
    fetch: fetch as unknown as typeof globalThis.fetch,
    setTimeout: ((fn: () => void) => {
      timers.push(fn);
      return 0;
    }) as unknown as typeof setTimeout,
  });
  const seen: NexusMessage[] = [];
  client.connect((m) => seen.push(m));
  return { client, seen, fetch, timers, socket: () => FakeSocket.all[FakeSocket.all.length - 1] };
}

const seqs = (seen: NexusMessage[]) => seen.flatMap((m) => (m.type === 'events' ? m.items.map((e) => e.seq) : []));

describe('ServerClient', () => {
  it('connects to /ws of the page host', () => {
    const { socket } = setup();
    expect(socket().url).toBe('ws://host:5173/ws');
  });

  it('drops events it has already seen', () => {
    const { seen, socket } = setup();
    socket().open();
    socket().receive({ type: 'events', items: [event(1), event(2)] });
    socket().receive({ type: 'events', items: [event(2), event(3)] });
    expect(seqs(seen)).toEqual([1, 2, 3]);
  });

  it('aReconnectFillsTheGapWithoutDuplicates', async () => {
    const { seen, socket, timers, fetch } = setup([event(3), event(4)]);
    socket().open();
    socket().receive({ type: 'events', items: [event(1), event(2)] });
    socket().drop();
    expect(seen.at(-1)).toEqual({ type: 'connection', state: 'closed' });
    timers.shift()!(); // backoff over: reconnect
    socket().open();
    await vi.waitFor(() => expect(seqs(seen)).toEqual([1, 2, 3, 4]));
    expect(fetch).toHaveBeenCalledWith('http://host:5173/api/v1/events?after=2&limit=1000', expect.anything());
    socket().receive({ type: 'events', items: [event(4), event(5)] });
    expect(seqs(seen)).toEqual([1, 2, 3, 4, 5]);
  });

  it('aResetDropsTheOldRun', () => {
    const { seen, socket } = setup();
    socket().open();
    socket().receive({ type: 'events', items: [event(1), event(2)] });
    socket().receive({ type: 'reset', seed: 9 });
    socket().receive({ type: 'events', items: [event(1)] });
    expect(seqs(seen)).toEqual([1, 2, 1]);
  });

  it('passes a refused command through with its status', async () => {
    const { client, fetch } = setup();
    fetch.mockResolvedValueOnce(new Response('{"ok":false,"status":409,"message":"nein"}', { status: 409 }));
    await expect(client.command({ command: 'machineRepair', machineId: 'CNC-01' })).resolves.toEqual({
      ok: false,
      status: 409,
      message: 'nein',
    });
  });
});
