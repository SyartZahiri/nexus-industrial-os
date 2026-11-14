import { describe, expect, it } from 'vitest';
import { Loop } from './loop';

describe('Loop', () => {
  it('advances real time times speed in whole 100 ms steps and keeps the rest', () => {
    const steps: number[] = [];
    const loop = new Loop((ms) => steps.push(ms));
    loop.running = true;
    loop.setSpeed(2);
    expect(loop.tick(120)).toBe(2); // 240 ms owed: two steps, 40 ms kept
    expect(loop.tick(30)).toBe(1); // 40 + 60 = 100
    expect(steps).toEqual([100, 100, 100]);
  });

  it('aLongPauseOfTheTimerRunsAtMostFiftySteps', () => {
    let n = 0;
    const loop = new Loop(() => n++);
    loop.running = true;
    loop.setSpeed(25);
    expect(loop.tick(60_000)).toBe(50);
    expect(loop.tick(50)).toBe(12); // the rest was dropped, not carried
    expect(n).toBe(62);
  });

  it('does nothing while paused and forgets what was owed', () => {
    let n = 0;
    const loop = new Loop(() => n++);
    loop.tick(1_000);
    loop.running = true;
    expect(loop.tick(50)).toBe(0);
    expect(n).toBe(0);
  });

  it('refuses speeds outside 0.25 to 25', () => {
    const loop = new Loop(() => {});
    expect(() => loop.setSpeed(0.1)).toThrow();
    expect(() => loop.setSpeed(30)).toThrow();
  });
});
