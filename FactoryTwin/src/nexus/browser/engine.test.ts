import { describe, expect, it } from 'vitest';
import * as core from '@nexus-core';
import { BrowserEngine } from './engine';
import type { NexusMessage } from '../types';

function ofType<T extends NexusMessage['type']>(ms: NexusMessage[], t: T) {
  return ms.filter((m): m is Extract<NexusMessage, { type: T }> => m.type === t);
}

describe('BrowserEngine', () => {
  it('greets with reset, sim and frame, then sends frames and numbered events while running', () => {
    const engine = new BrowserEngine(core, 7);
    const hello = engine.handle({ id: 1, kind: 'hello' }).messages;
    expect(hello.slice(0, 3).map((m) => m.type)).toEqual(['reset', 'sim', 'frame']);
    engine.handle({ id: 2, kind: 'speed', speed: 25 });
    engine.handle({ id: 3, kind: 'play' });
    const seqs: number[] = [];
    for (let i = 0; i < 200; i++) {
      for (const m of ofType(engine.tick(50), 'events')) seqs.push(...m.items.map((e) => e.seq));
    }
    expect(seqs.length).toBeGreaterThan(0);
    expect(seqs).toEqual(seqs.map((_, i) => i + 1)); // 1, 2, 3 … without gaps or repeats
  });

  it('answers a refused command with the core message and status', () => {
    const engine = new BrowserEngine(core, 7);
    const { answer } = engine.handle({
      id: 1,
      kind: 'command',
      body: { command: 'machineRepair', machineId: 'CNC-01' },
    });
    expect(answer).toMatchObject({ ok: false, status: 409 });
    expect((answer as { message: string }).message).toContain('CNC-01');
  });

  it('starts a new run on reset and numbers its events from 1 again', () => {
    const engine = new BrowserEngine(core, 7);
    engine.handle({ id: 1, kind: 'speed', speed: 25 });
    engine.handle({ id: 2, kind: 'play' });
    for (let i = 0; i < 50; i++) engine.tick(50);
    const after = engine.handle({ id: 3, kind: 'reset', seed: 9 }).messages;
    expect(after[0]).toEqual({ type: 'reset', seed: 9 });
    let first: number | undefined;
    for (let i = 0; i < 200 && first === undefined; i++) {
      first = ofType(engine.tick(50), 'events')[0]?.items[0]?.seq;
    }
    expect(first).toBe(1);
  });

  it('answers an event query with the run’s events, filtered', () => {
    const engine = new BrowserEngine(core, 7);
    engine.handle({ id: 1, kind: 'speed', speed: 25 });
    engine.handle({ id: 2, kind: 'play' });
    for (let i = 0; i < 200; i++) engine.tick(50);
    const { answer } = engine.handle({ id: 3, kind: 'events', filter: { type: 'order.' } });
    const page = answer as { items: { type: string }[]; more: boolean };
    expect(page.items.length).toBeGreaterThan(0);
    expect(page.items.every((e) => e.type.startsWith('order.'))).toBe(true);
  });
});
