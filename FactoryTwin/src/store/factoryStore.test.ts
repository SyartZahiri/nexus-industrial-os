import { describe, expect, it } from 'vitest';
import type { NexusClient } from '../nexus/client';
import type { Answer, NexusMessage } from '../nexus/types';
import { send, startNexus, twin, useFactory } from './factoryStore';

function fakeClient(answer: Answer): { client: NexusClient; push: (m: NexusMessage) => void } {
  let listener: (m: NexusMessage) => void = () => {};
  const client: NexusClient = {
    mode: 'browser',
    connect: (l) => {
      listener = l;
      return () => {};
    },
    command: async () => answer,
    query: async () => ({ ok: true, result: {} }) as Answer<never>,
    events: async () => ({ items: [], more: false, next: 0 }),
    play: async () => {},
    pause: async () => {},
    setSpeed: async () => {},
    reset: async () => {},
  };
  return { client, push: (m) => listener(m) };
}

describe('factoryStore', () => {
  it('keeps the simulation state, the newest 500 events and the frame', () => {
    const { client, push } = fakeClient({ ok: true, result: null });
    startNexus(client);
    push({ type: 'sim', running: true, speed: 5, seed: 7 });
    push({
      type: 'events',
      items: Array.from({ length: 600 }, (_, i) => ({
        seq: i + 1,
        eventId: `1-${i + 1}`,
        simTimeMs: i,
        type: 'order.created',
        source: 'smartfactory',
        severity: 'INFO' as const,
        correlationId: null,
        payload: {},
      })),
    });
    expect(useFactory.getState().sim).toEqual({ running: true, speed: 5, seed: 7 });
    expect(useFactory.getState().events).toHaveLength(500);
    expect(useFactory.getState().events.at(-1)!.seq).toBe(600);
  });

  it('shows the reason when the plant refuses a command', async () => {
    const { client } = fakeClient({ ok: false, status: 409, message: 'Ungültiger Zustandsübergang für CNC-01' });
    startNexus(client);
    await expect(send({ command: 'machineRepair', machineId: 'CNC-01' })).resolves.toBe(false);
    expect(useFactory.getState().notice).toContain('CNC-01');
  });

  it('keeps machine settings until the plant accepts them', async () => {
    const { client } = fakeClient({ ok: false, status: 400, message: 'Override muss zwischen 50 und 130 % liegen' });
    startNexus(client);
    await send({ command: 'machineConfigure', machineId: 'CNC-04', override: 200, coolant: false });
    expect(twin.configs['CNC-04']).toBeUndefined();
  });

  it('forgets the old run on reset', () => {
    const { client, push } = fakeClient({ ok: true, result: null });
    startNexus(client);
    push({
      type: 'events',
      items: [
        {
          seq: 1,
          eventId: '1-1',
          simTimeMs: 0,
          type: 'x',
          source: 's',
          severity: 'INFO',
          correlationId: null,
          payload: {},
        },
      ],
    });
    push({ type: 'reset', seed: 9 });
    expect(useFactory.getState().events).toEqual([]);
    expect(twin.latest).toBeNull();
  });
});
