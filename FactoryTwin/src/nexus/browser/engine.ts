import { Loop } from '../loop';
import type { Answer, Command, Frame, NexusEvent, NexusMessage, Query } from '../types';

export interface NexusCore {
  plantStart(seed: number): void;
  plantAdvance(simMs: number): number;
  plantFrame(): string;
  plantEventsFrom(from: number): string;
  plantCommand(json: string): string;
  plantQuery(json: string): string;
}

export type WorkerRequest =
  | { id: number; kind: 'hello'; seed?: number }
  | { id: number; kind: 'command'; body: Command }
  | { id: number; kind: 'query'; body: Query }
  | { id: number; kind: 'play' }
  | { id: number; kind: 'pause' }
  | { id: number; kind: 'speed'; speed: number }
  | { id: number; kind: 'reset'; seed?: number };

/**
 * The plant in the browser: the Java core (TeaVM) driven by a Loop. Pure, so it runs in a worker and in tests; the
 * worker only adds the timer and postMessage. Messages have the same form as the server's WebSocket.
 */
export class BrowserEngine {
  private readonly loop: Loop;
  private seed: number;
  private sentEvents = 0;

  constructor(
    private readonly core: NexusCore,
    seed: number,
  ) {
    this.seed = seed;
    this.loop = new Loop((ms) => core.plantAdvance(ms));
    core.plantStart(seed);
  }

  tick(realMs: number): NexusMessage[] {
    if (this.loop.tick(realMs) === 0) return [];
    return [this.frame(), ...this.events()];
  }

  handle(r: WorkerRequest): { answer: unknown; messages: NexusMessage[] } {
    switch (r.kind) {
      case 'hello':
        return { answer: null, messages: [{ type: 'reset', seed: this.seed }, this.sim(), this.frame(), ...this.events()] };
      case 'command': {
        const answer = JSON.parse(this.core.plantCommand(JSON.stringify(r.body))) as Answer;
        return { answer, messages: [this.frame(), ...this.events()] };
      }
      case 'query':
        return { answer: JSON.parse(this.core.plantQuery(JSON.stringify(r.body))), messages: [] };
      case 'play':
        this.loop.running = true;
        return { answer: null, messages: [this.sim()] };
      case 'pause':
        this.loop.running = false;
        return { answer: null, messages: [this.sim()] };
      case 'speed':
        this.loop.setSpeed(r.speed);
        return { answer: null, messages: [this.sim()] };
      case 'reset':
        this.seed = r.seed ?? this.seed;
        this.core.plantStart(this.seed);
        this.sentEvents = 0;
        return { answer: null, messages: [{ type: 'reset', seed: this.seed }, this.sim(), this.frame()] };
    }
  }

  private sim(): NexusMessage {
    return { type: 'sim', running: this.loop.running, speed: this.loop.speed, seed: this.seed };
  }

  private frame(): NexusMessage {
    return { type: 'frame', frame: JSON.parse(this.core.plantFrame()) as Frame };
  }

  private events(): NexusMessage[] {
    const items = JSON.parse(this.core.plantEventsFrom(this.sentEvents)) as NexusEvent[];
    if (items.length === 0) return [];
    this.sentEvents += items.length;
    return [{ type: 'events', items }];
  }
}
