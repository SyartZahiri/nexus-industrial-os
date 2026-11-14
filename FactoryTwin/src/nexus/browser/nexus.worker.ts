/// <reference lib="webworker" />
import * as core from '@nexus-core';
import { BrowserEngine, type WorkerRequest } from './engine';

const TICK_MS = 50;
const seedParam = Number(new URLSearchParams(self.location.search).get('seed'));
const engine = new BrowserEngine(core, Number.isInteger(seedParam) && seedParam > 0 ? seedParam : 4711);
let last = performance.now();

setInterval(() => {
  const now = performance.now();
  const messages = engine.tick(now - last);
  last = now;
  for (const m of messages) postMessage({ message: m });
}, TICK_MS);

onmessage = (e: MessageEvent<WorkerRequest>) => {
  let answer: unknown;
  let messages: ReturnType<BrowserEngine['handle']>['messages'] = [];
  try {
    ({ answer, messages } = engine.handle(e.data));
  } catch (err) {
    answer = { ok: false, status: 400, message: err instanceof Error ? err.message : 'Befehl fehlgeschlagen' };
  }
  postMessage({ id: e.data.id, answer });
  for (const m of messages) postMessage({ message: m });
};
