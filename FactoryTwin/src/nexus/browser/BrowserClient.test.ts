import { describe, expect, it, vi } from 'vitest';
import { BrowserClient } from './BrowserClient';
import type { NexusMessage } from '../types';

class FakeWorker {
  onmessage: ((e: { data: unknown }) => void) | null = null;
  onerror: ((e: { message: string }) => void) | null = null;
  posted: { id: number; kind: string }[] = [];
  postMessage(m: { id: number; kind: string }) {
    this.posted.push(m);
  }
  terminate() {}
  answer(id: number, answer: unknown) {
    this.onmessage?.({ data: { id, answer } });
  }
  fail() {
    this.onerror?.({ message: 'Modul nicht ladbar' });
  }
}

function setup() {
  const worker = new FakeWorker();
  const client = new BrowserClient(() => worker as unknown as Worker);
  const states: string[] = [];
  client.connect((m: NexusMessage) => {
    if (m.type === 'connection') states.push(m.state);
  });
  return { worker, client, states };
}

describe('BrowserClient', () => {
  it('reports the plant as reachable only after the worker answered its greeting', async () => {
    const { worker, states } = setup();
    expect(states).toEqual(['connecting']);
    worker.answer(worker.posted[0].id, null);
    await vi.waitFor(() => expect(states).toEqual(['connecting', 'open']));
  });

  it('aBrokenWorkerShowsAsClosedAndAnswersWhatWasAsked', async () => {
    const { worker, client, states } = setup();
    const pending = client.command({ command: 'machineStop', machineId: 'CNC-01' });
    worker.fail();
    expect(states.at(-1)).toBe('closed');
    await expect(pending).resolves.toMatchObject({ ok: false, status: 0 });
  });

  it('a greeting the core refuses leaves the plant closed', async () => {
    const { worker, states } = setup();
    worker.answer(worker.posted[0].id, { ok: false, status: 400, message: 'Kern startet nicht' });
    await vi.waitFor(() => expect(states.at(-1)).toBe('closed'));
  });
});
