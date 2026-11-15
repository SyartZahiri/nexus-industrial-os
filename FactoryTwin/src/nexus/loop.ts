const STEP_MS = 100;
export const MAX_STEPS_PER_TICK = 50;

/**
 * The clock of the browser plant, as SimulationLoop on the server: real time times speed in whole steps. After a
 * stall (a throttled background tab) at most MAX_STEPS_PER_TICK steps run and the rest is dropped.
 */
export class Loop {
  running = false;
  speed = 1;
  private owedMs = 0;

  constructor(private readonly advance: (ms: number) => void) {}

  setSpeed(speed: number): void {
    if (!(speed >= 0.25 && speed <= 25)) throw new Error('Tempo muss zwischen 0,25 und 25 liegen');
    this.speed = speed;
  }

  /** realMs of real time passed; returns the steps run. */
  tick(realMs: number): number {
    if (!this.running) {
      this.owedMs = 0;
      return 0;
    }
    this.owedMs += realMs * this.speed;
    let steps = Math.floor(this.owedMs / STEP_MS);
    if (steps > MAX_STEPS_PER_TICK) {
      steps = MAX_STEPS_PER_TICK;
      this.owedMs = 0;
    } else {
      this.owedMs -= steps * STEP_MS;
    }
    for (let i = 0; i < steps; i++) this.advance(STEP_MS);
    return steps;
  }
}
